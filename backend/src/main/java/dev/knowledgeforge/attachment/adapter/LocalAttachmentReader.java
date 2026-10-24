package dev.knowledgeforge.attachment.adapter;

import dev.knowledgeforge.attachment.application.AttachmentReader;
import dev.knowledgeforge.ingestion.application.DocumentAnalyzer;
import dev.knowledgeforge.library.application.LibraryException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.stereotype.Component;

@Component
public class LocalAttachmentReader implements AttachmentReader {
    private static final Set<String> DOCUMENTS = Set.of("pdf", "docx", "txt", "md", "csv", "json", "java", "py", "js", "ts", "tsx", "jsx", "xml", "yaml", "yml", "log", "sql", "html", "css");
    private final DocumentAnalyzer analyzer;
    public LocalAttachmentReader(DocumentAnalyzer analyzer) { this.analyzer = analyzer; }
    public Parsed read(String name, byte[] content) {
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (Set.of("png", "jpg", "jpeg").contains(extension)) return image(content);
        if (!DOCUMENTS.contains(extension)) throw new LibraryException(415, "PNG·JPEG 이미지, PDF·DOCX 문서, 텍스트·코드 파일을 첨부해 주세요.");
        if (!Set.of("pdf", "docx", "html").contains(extension)) {
            try {
                String text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(content)).toString().replace("\r\n", "\n").replace('\r', '\n');
                if (text.codePoints().anyMatch(value -> Character.isISOControl(value) && value != '\n' && value != '\t')) {
                    throw new LibraryException(415, "텍스트 파일에 바이너리 데이터가 있습니다. UTF-8 파일로 첨부해 주세요.");
                }
                return excerpt("text/plain", content, text.strip());
            } catch (java.nio.charset.CharacterCodingException exception) {
                throw new LibraryException(415, "텍스트·코드 파일은 UTF-8 형식으로 첨부해 주세요.");
            }
        }
        var analysis = analyzer.analyze(name, content);
        if (!Set.of("READY", "PARTIAL").contains(analysis.status())) {
            throw new LibraryException(415, "이 파일의 글을 읽을 수 없습니다. 스캔 문서는 PNG·JPEG 이미지로 첨부해 주세요.");
        }
        return excerpt(analysis.mediaType(), content, analysis.text());
    }
    private Parsed excerpt(String mediaType, byte[] content, String text) {
        if (text.isBlank()) throw new LibraryException(415, "첨부 파일에서 읽을 수 있는 글을 찾지 못했습니다.");
        int end = Math.min(text.length(), 3000);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return new Parsed(mediaType, content, text.substring(0, end), end < text.length());
    }
    private Parsed image(byte[] content) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(content))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new java.io.IOException();
            var reader = readers.next();
            try {
                if (!Set.of("png", "jpeg").contains(reader.getFormatName().toLowerCase(Locale.ROOT))) throw new java.io.IOException();
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 12000000) {
                    throw new LibraryException(413, "이미지는 1,200만 화소 이하, 한 변 8,192픽셀 이하여야 합니다.");
                }
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(reader.read(0), "png", output)) throw new java.io.IOException();
                return new Parsed("image/png", output.toByteArray(), "첨부 이미지 " + width + "×" + height, false);
            } finally { reader.dispose(); }
        } catch (LibraryException exception) { throw exception; }
        catch (java.io.IOException | RuntimeException exception) { throw new LibraryException(415, "손상되었거나 지원하지 않는 이미지입니다. PNG·JPEG 파일을 확인해 주세요."); }
    }
}
