package dev.knowledgeforge;

import dev.knowledgeforge.ingestion.adapter.TikaJavaAnalyzer;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AnalyzerTest {
    private final TikaJavaAnalyzer analyzer = new TikaJavaAnalyzer();

    @Test
    void respectsDeclaredWebCharacterEncoding() {
        var analysis = analyzer.analyze("legacy.html", "<html><head><meta charset='EUC-KR'></head><body><main>가상 관측 기록</main></body></html>"
                .getBytes(java.nio.charset.Charset.forName("EUC-KR")));
        assertThat(analysis.text()).contains("가상 관측 기록");
    }

    @Test
    void webExtractionKeepsArticleCodeAndDropsNavigationAndScripts() {
        var analysis = analyzer.analyze("page.html", ("<html><body><nav>Unrelated menu</nav><main><h1>Synthetic guide</h1>"
                + "<article><pre>class Greenhouse { void measure() {} }</pre></article></main>"
                + "<footer>Legal menu</footer><script>secret_script()</script></body></html>").getBytes(StandardCharsets.UTF_8));
        assertThat(analysis.text()).contains("Synthetic guide", "class Greenhouse")
                .doesNotContain("Unrelated menu", "Legal menu", "secret_script");
        assertThat(analysis.text().split("class Greenhouse", -1)).hasSize(2);
    }

    @Test
    void extractsHtmlTextAndJavaSymbolsWithLineNumbers() {
        var html = analyzer.analyze("page.html", "<html><body><h1>가상 연구</h1><p>관측 기록</p></body></html>".getBytes(StandardCharsets.UTF_8));
        assertThat(html.status()).isEqualTo("READY");
        assertThat(html.text()).contains("가상 연구", "관측 기록").doesNotContain("<h1>");
        var java = analyzer.analyze("Garden.java", "package synthetic;\nclass Garden {\n int measure() { return 1; }\n}".getBytes(StandardCharsets.UTF_8));
        assertThat(java.symbols()).anySatisfy(symbol -> {
            assertThat(symbol.name()).isEqualTo("measure");
            assertThat(symbol.line()).isEqualTo(3);
        });
        assertThat(java.symbols()).anySatisfy(symbol -> assertThat(symbol.signature()).isEqualTo("synthetic.Garden"));
    }

    @Test
    void malformedJavaKeepsSearchableOriginalButMarksPartial() {
        var analysis = analyzer.analyze("Broken.java", "class Broken { invalid syntax".getBytes(StandardCharsets.UTF_8));
        assertThat(analysis.status()).isEqualTo("PARTIAL");
        assertThat(analysis.text()).contains("Broken");
        assertThat(analysis.symbols()).isEmpty();
    }

    @Test
    void extractsTextFromSyntheticPdf() throws Exception {
        byte[] bytes;
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText("Synthetic greenhouse observations");
                content.endText();
            }
            pdf.save(output);
            bytes = output.toByteArray();
        }
        var progress = new java.util.ArrayList<dev.knowledgeforge.ingestion.application.ProcessingProgress.Step>();
        try (var observation = dev.knowledgeforge.ingestion.application.ProcessingProgress.observe(progress::add)) {
            var analysis = analyzer.analyze("synthetic.pdf", bytes);
            assertThat(analysis.status()).isEqualTo("READY");
            assertThat(analysis.text()).contains("Synthetic greenhouse observations");
        }
        assertThat(progress).anySatisfy(step -> {
            assertThat(step.stage()).isEqualTo("PAGES");
            assertThat(step.completed()).isEqualTo(1);
        });
    }
}
