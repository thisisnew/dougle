package dev.knowledgeforge.ingestion.adapter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import dev.knowledgeforge.ingestion.application.DocumentAnalyzer;
import dev.knowledgeforge.ingestion.domain.Analysis;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

@Component
public class TikaJavaAnalyzer implements DocumentAnalyzer {
    private static final int MAX_TEXT = 32_000_000;

    @Override
    public Analysis analyze(String name, byte[] content) {
        return analyzeStream(name, new ByteArrayInputStream(content));
    }

    @Override
    public Analysis analyzeStream(String name, java.io.InputStream content) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
        try {
            if (name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.html?")) {
                byte[] prefix = content.readNBytes(10 * 1024 * 1024 + 1);
                if (prefix.length <= 10 * 1024 * 1024) {
                    content = new ByteArrayInputStream(WebContent.readableHtml(prefix)
                            .getBytes(StandardCharsets.UTF_8));
                } else {
                    content = new java.io.SequenceInputStream(new ByteArrayInputStream(prefix), content);
                }
            }
            BodyContentHandler handler = new BodyContentHandler(MAX_TEXT) {
                private int pages;
                private long characters;
                private long reported;
                @Override
                public void characters(char[] buffer, int start, int length) throws org.xml.sax.SAXException {
                    characters += length;
                    if (pages == 0 && characters - reported >= 64 * 1024) {
                        dev.knowledgeforge.ingestion.application.ProcessingProgress.report("EXTRACTING_CHARS", name, characters, 0);
                        reported = characters;
                    }
                    super.characters(buffer, start, length);
                }
                @Override
                public void startElement(String uri, String localName, String qualifiedName,
                                         org.xml.sax.Attributes attributes) throws org.xml.sax.SAXException {
                    if ("div".equals(localName) && "page".equals(attributes.getValue("class"))) {
                        dev.knowledgeforge.ingestion.application.ProcessingProgress.report("PAGES", name, ++pages, 0);
                    }
                    super.startElement(uri, localName, qualifiedName, attributes);
                }
            };
            ParseContext context = new ParseContext();
            context.set(Parser.class, new org.apache.tika.parser.EmptyParser());
            var ocr = new org.apache.tika.parser.ocr.TesseractOCRConfig();
            ocr.setSkipOcr(true);
            context.set(org.apache.tika.parser.ocr.TesseractOCRConfig.class, ocr);
            var pdf = new org.apache.tika.parser.pdf.PDFParserConfig();
            pdf.setOcrStrategy(org.apache.tika.parser.pdf.PDFParserConfig.OCR_STRATEGY.NO_OCR);
            context.set(org.apache.tika.parser.pdf.PDFParserConfig.class, pdf);
            boolean truncated = false;
            String javaText = null;
            if (name.endsWith(".java")) {
                var reader = new java.io.InputStreamReader(content, StandardCharsets.UTF_8);
                var builder = new StringBuilder();
                char[] buffer = new char[16 * 1024];
                for (int count; (count = reader.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                    int remaining = MAX_TEXT - builder.length();
                    builder.append(buffer, 0, Math.min(count, remaining));
                    dev.knowledgeforge.ingestion.application.ProcessingProgress.report("EXTRACTING_CHARS", name, builder.length(), 0);
                    if (count > remaining) { truncated = true; break; }
                }
                javaText = builder.toString();
            } else {
                try (var resources = new org.apache.tika.io.TemporaryResources();
                     var input = org.apache.tika.io.TikaInputStream.get(content, resources, metadata)) {
                    try {
                        new AutoDetectParser().parse(input, handler, metadata, context);
                    } catch (org.xml.sax.SAXException exception) {
                        if (!org.apache.tika.exception.WriteLimitReachedException.isWriteLimitReached(exception)) throw exception;
                        truncated = true;
                    }
                }
            }
            String text = Normalizer.normalize(handler.toString().replace("\r\n", "\n").replace('\r', '\n'),
                    Normalizer.Form.NFC).replace("\u0000", "").strip();
            List<Analysis.Symbol> symbols = new ArrayList<>();
            String status = text.isBlank() ? "UNSUPPORTED" : "READY";
            String error = text.isBlank() ? "추출 가능한 텍스트가 없습니다." : null;
            if (name.endsWith(".java")) {
                text = javaText;
                if (text.length() > 2_000_000) {
                    return new Analysis(text, "text/x-java-source", "PARTIAL",
                            truncated ? "텍스트가 길어 앞부분만 검색합니다. 파일을 나눠 등록하세요."
                                    : "큰 소스 파일은 심볼 분석 없이 본문을 검색합니다.", List.of());
                }
                var parsed = new JavaParser(new ParserConfiguration()
                        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(text);
                if (parsed.isSuccessful() && parsed.getResult().isPresent()) {
                    var unit = parsed.getResult().orElseThrow();
                    unit.walk(node -> {
                        if (node instanceof TypeDeclaration<?> type) symbols.add(new Analysis.Symbol(
                            type.getNameAsString(), "TYPE", type.getBegin().map(position -> position.line).orElse(1),
                            type.getFullyQualifiedName().orElse(type.getNameAsString())));
                        if (node instanceof CallableDeclaration<?> callable) symbols.add(new Analysis.Symbol(
                            callable.getNameAsString(), "CALLABLE",
                            callable.getBegin().map(position -> position.line).orElse(1), callable.getSignature().asString()));
                    });
                    status = "READY";
                    error = null;
                } else {
                    status = "PARTIAL";
                    error = "Java 구문을 해석할 수 없어 심볼을 생략했습니다. 원문 검색은 가능합니다.";
                }
            }
            return new Analysis(text, metadata.get("Content-Type"), truncated ? "PARTIAL" : status,
                    truncated ? "텍스트가 길어 앞부분만 검색합니다. 파일을 나눠 등록하세요." : error, List.copyOf(symbols));
        } catch (java.util.concurrent.CancellationException exception) {
            throw exception;
        } catch (Exception exception) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            return new Analysis("", metadata.get("Content-Type"), "ERROR",
                    "파일을 읽지 못했습니다. 손상·암호 설정을 확인하세요.", List.of());
        }
    }
}
