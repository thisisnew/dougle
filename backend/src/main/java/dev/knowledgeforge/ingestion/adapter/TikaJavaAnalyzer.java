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
    private static final int MAX_TEXT = 500_000;

    @Override
    public Analysis analyze(String name, byte[] content) {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
        try {
            BodyContentHandler handler = new BodyContentHandler(MAX_TEXT);
            ParseContext context = new ParseContext();
            context.set(Parser.class, new org.apache.tika.parser.EmptyParser());
            var ocr = new org.apache.tika.parser.ocr.TesseractOCRConfig();
            ocr.setSkipOcr(true);
            context.set(org.apache.tika.parser.ocr.TesseractOCRConfig.class, ocr);
            var pdf = new org.apache.tika.parser.pdf.PDFParserConfig();
            pdf.setOcrStrategy(org.apache.tika.parser.pdf.PDFParserConfig.OCR_STRATEGY.NO_OCR);
            context.set(org.apache.tika.parser.pdf.PDFParserConfig.class, pdf);
            new AutoDetectParser().parse(new ByteArrayInputStream(content), handler, metadata, context);
            String text = Normalizer.normalize(handler.toString().replace("\r\n", "\n").replace('\r', '\n'),
                    Normalizer.Form.NFC).replace("\u0000", "").strip();
            List<Analysis.Symbol> symbols = new ArrayList<>();
            String status = text.isBlank() ? "UNSUPPORTED" : "READY";
            String error = text.isBlank() ? "추출 가능한 텍스트가 없습니다." : null;
            if (name.endsWith(".java")) {
                text = new String(content, StandardCharsets.UTF_8);
                if (text.length() > MAX_TEXT) {
                    return new Analysis("", "text/x-java-source", "ERROR", "Java 텍스트 크기 제한을 초과했습니다.", List.of());
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
            return new Analysis(text, metadata.get("Content-Type"), status, error, List.copyOf(symbols));
        } catch (Exception exception) {
            return new Analysis("", metadata.get("Content-Type"), "ERROR",
                    "텍스트 추출 실패 또는 500,000자 제한 초과: " + exception.getClass().getSimpleName(), List.of());
        }
    }
}
