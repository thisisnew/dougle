package dev.knowledgeforge;

import dev.knowledgeforge.attachment.adapter.LocalAttachmentReader;
import dev.knowledgeforge.ingestion.adapter.TikaJavaAnalyzer;
import dev.knowledgeforge.library.application.LibraryException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QuestionAttachmentReaderTest {
    private final LocalAttachmentReader reader = new LocalAttachmentReader(new TikaJavaAnalyzer());

    @Test
    void readsPdfAndDocxTextWithoutExecutingEmbeddedContent() throws Exception {
        try (var pdf = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700); content.showText("Synthetic attachment"); content.endText();
            }
            pdf.save(output);
            assertThat(reader.read("note.pdf", output.toByteArray()).text()).contains("Synthetic attachment");
        }
        try (var docx = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            docx.createParagraph().createRun().setText("Synthetic document attachment"); docx.write(output);
            assertThat(reader.read("note.docx", output.toByteArray()).text()).contains("Synthetic document attachment");
        }
    }

    @Test
    void normalizesJpegAndPngAndRejectsExcessiveDimensions() throws Exception {
        for (String format : new String[]{"png", "jpeg"}) {
            var output = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(10, 12, BufferedImage.TYPE_INT_RGB), format, output);
            var parsed = reader.read("image." + format, output.toByteArray());
            assertThat(parsed.mediaType()).isEqualTo("image/png");
            assertThat(ImageIO.read(new ByteArrayInputStream(parsed.content())).getWidth()).isEqualTo(10);
            assertThat(parsed.text()).contains("10×12");
        }
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8193, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        assertThatThrownBy(() -> reader.read("wide.png", output.toByteArray()))
                .isInstanceOfSatisfying(LibraryException.class, error -> assertThat(error.status()).isEqualTo(413));
    }

    @Test
    void codeRemainsDataAndTruncationDoesNotSplitUnicodePair() {
        assertThatThrownBy(() -> reader.read("binary.txt", new byte[]{0, 1, 2})).isInstanceOf(LibraryException.class);
        assertThatThrownBy(() -> reader.read("invalid.txt", new byte[]{(byte) 0xff})).isInstanceOf(LibraryException.class);
        for (String extension : new String[]{"txt", "md", "csv", "json", "py", "js", "ts", "tsx", "jsx", "xml", "yaml", "yml", "log", "sql", "css"}) {
            assertThat(reader.read("synthetic." + extension, "synthetic attachment text".getBytes(StandardCharsets.UTF_8)).text()).contains("synthetic");
        }
        assertThat(reader.read("Example.java", "class Example { int value = 1; }".getBytes(StandardCharsets.UTF_8)).text()).contains("class Example");
        var parsed = reader.read("note.txt", ("a".repeat(2999) + "😀tail").getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.truncated()).isTrue();
        assertThat(parsed.text()).hasSize(2999);
    }
}
