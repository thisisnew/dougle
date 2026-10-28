package dev.dougle.ingestion.adapter;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import dev.dougle.ingestion.application.DocumentAnalyzer;
import dev.dougle.ingestion.domain.Analysis;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
    private final long maxExtractedBytes;

    public TikaJavaAnalyzer() { this(ExtractedTextWriter.MAX_SPOOL_BYTES); }

    TikaJavaAnalyzer(long maxExtractedBytes) {
        if (maxExtractedBytes < 1) throw new IllegalArgumentException("Extraction budget must be positive");
        this.maxExtractedBytes = maxExtractedBytes;
    }

    @Override
    public Analysis analyze(String name, byte[] content) {
        return analyzeStream(name, new ByteArrayInputStream(content));
    }

    @Override
    public Analysis analyzeStream(String name, java.io.InputStream content) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
        try (var extracted = new ExtractedTextWriter(maxExtractedBytes)) {
            if (name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.html?")) {
                byte[] prefix = content.readNBytes(10 * 1024 * 1024 + 1);
                if (prefix.length <= 10 * 1024 * 1024) {
                    content = new ByteArrayInputStream(WebContent.readableHtml(prefix)
                            .getBytes(StandardCharsets.UTF_8));
                } else {
                    content = new java.io.SequenceInputStream(new ByteArrayInputStream(prefix), content);
                }
            }
            BodyContentHandler handler = new BodyContentHandler(extracted) {
                private int pages;
                private long characters;
                private long reported;
                @Override
                public void characters(char[] buffer, int start, int length) throws org.xml.sax.SAXException {
                    characters += length;
                    if (pages == 0 && characters - reported >= 64 * 1024) {
                        dev.dougle.ingestion.application.ProcessingProgress.report("EXTRACTING_CHARS", name, characters, 0);
                        reported = characters;
                    }
                    super.characters(buffer, start, length);
                }
                @Override
                public void startElement(String uri, String localName, String qualifiedName,
                                         org.xml.sax.Attributes attributes) throws org.xml.sax.SAXException {
                    if ("div".equals(localName) && "page".equals(attributes.getValue("class"))) {
                        dev.dougle.ingestion.application.ProcessingProgress.report("PAGES", name, ++pages, 0);
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
            if (name.endsWith(".java")) {
                var reader = new java.io.InputStreamReader(content, StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT));
                char[] buffer = new char[16 * 1024];
                long characters = 0;
                for (int count; (count = reader.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                    extracted.write(buffer, 0, count);
                    characters += count;
                    dev.dougle.ingestion.application.ProcessingProgress.report("EXTRACTING_CHARS", name, characters, 0);
                }
            } else {
                try (var resources = new org.apache.tika.io.TemporaryResources();
                     var input = org.apache.tika.io.TikaInputStream.get(content, resources, metadata)) {
                    new AutoDetectParser().parse(input, handler, metadata, context);
                }
            }
            List<String> parts = extracted.finish();
            String text = parts.getFirst();
            boolean large = parts.size() > 1;
            boolean blank = parts.stream().allMatch(String::isBlank);
            List<Analysis.Symbol> symbols = new ArrayList<>();
            String status = blank ? "UNSUPPORTED" : "READY";
            String error = blank ? "추출 가능한 텍스트가 없습니다." : null;
            if (name.endsWith(".java")) {
                if (parts.size() > 30) {
                    return new Analysis(text, "text/x-java-source", "PARTIAL",
                            "큰 소스 파일은 심볼 분석 없이 본문 전체를 검색합니다.", List.of(), parts);
                }
                String javaText = String.join("", parts);
                var parsed = new JavaParser(new ParserConfiguration()
                        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)).parse(javaText);
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
            return new Analysis(text, metadata.get("Content-Type"), status, error, List.copyOf(symbols),
                    large || parts instanceof AutoCloseable ? parts : List.of());
        } catch (WebContent.AccessRequiredException exception) {
            return new Analysis("", "text/html", "ERROR", exception.getMessage(), List.of());
        } catch (java.util.concurrent.CancellationException exception) {
            throw exception;
        } catch (Exception exception) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
            boolean sizeLimit = false;
            for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
                if (cause instanceof ExtractedTextWriter.SizeLimitException) sizeLimit = true;
                if (cause instanceof java.util.concurrent.CancellationException cancellation) throw cancellation;
                if (cause instanceof java.io.InterruptedIOException) {
                    var cancellation = new java.util.concurrent.CancellationException("분석이 중단되었습니다.");
                    cancellation.initCause(cause);
                    throw cancellation;
                }
            }
            if (sizeLimit) return new Analysis("", metadata.get("Content-Type"), "ERROR",
                    "추출문이 저장 용량 한도(" + maxExtractedBytes + " 바이트)를 넘었습니다. 문서를 나누거나 불필요한 내용을 줄인 뒤 다시 등록하세요.", List.of());
            return new Analysis("", metadata.get("Content-Type"), "ERROR",
                    "파일을 읽지 못했습니다. 손상·암호 설정을 확인하세요.", List.of());
        }
    }
}
