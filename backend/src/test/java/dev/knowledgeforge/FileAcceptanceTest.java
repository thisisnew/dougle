package dev.knowledgeforge;

import dev.knowledgeforge.ingestion.adapter.TikaJavaAnalyzer;
import dev.knowledgeforge.ingestion.application.ProcessingProgress;
import dev.knowledgeforge.ingestion.domain.Analysis;
import dev.knowledgeforge.search.adapter.LuceneSearchIndex;
import dev.knowledgeforge.search.application.SearchIndex;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@EnabledIfSystemProperty(named = "forge.acceptance.collection", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileAcceptanceTest {
    record Fixture(Path path, List<String> markers, boolean unreadable) {}
    @TempDir static Path directory;
    private final List<Fixture> fixtures = new ArrayList<>();
    private final Map<Path, String> fixtureHashes = new LinkedHashMap<>();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final TikaJavaAnalyzer analyzer = new TikaJavaAnalyzer();

    @BeforeAll void prepare() throws Exception {
        text("note.txt", "BLUE72\n" + "Synthetic middle filler.\n".repeat(100) + "AMBER24\n" + "Synthetic end filler.\n".repeat(100) + "GREEN12", StandardCharsets.UTF_8, List.of("BLUE72", "AMBER24", "GREEN12"));
        text("guide.md", "# BLUE72\n| Badge | Hours |\n|---|---|\n| AMBER24 | 24 |\n```java\nString value = \"GREEN12\";\n```", StandardCharsets.UTF_8, List.of("BLUE72", "AMBER24", "GREEN12"));
        text("Strategy.java", "interface Strategy { int choose(); }\nclass WinningStrategy implements Strategy { public int choose() { return 7; } }\nclass Player { Strategy strategy; int play() { return strategy.choose(); } }", StandardCharsets.UTF_8, List.of("interface Strategy", "implements Strategy", "strategy.choose"));
        StringBuilder csv = new StringBuilder("item,quantity\n");
        for (int row = 1; row <= 100; row++) csv.append("ITEM").append(row).append(',').append(row).append('\n');
        text("inventory.csv", csv.toString(), StandardCharsets.UTF_8, List.of("ITEM1", "ITEM50", "ITEM100"));
        text("local.html", "<html><body><nav>BLUE36</nav><main><h1>BLUE72</h1><p>AMBER24</p><p>GREEN12</p></main><script>WRONG_SCRIPT()</script></body></html>", StandardCharsets.UTF_8, List.of("BLUE72", "AMBER24", "GREEN12"));
        pdf("pages.pdf", 20, false, false, false);
        pdf("columns.pdf", 1, false, false, true);
        pdf("scan.pdf", 1, true, false, false);
        pdf("encrypted.pdf", 1, false, true, false);
        Path damaged = directory.resolve("damaged.pdf");
        Files.writeString(damaged, "%PDF-1.7\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n2 0 obj\nBROKEN_PAGE_OBJECT\n%%EOF");
        fixtures.add(new Fixture(damaged, List.of(), true));
        large("nine.txt", 9L * 1024 * 1024, false);
        large("ten.txt", 10L * 1024 * 1024 + 1, false);
        large("hundred.txt", 100L * 1024 * 1024, false);
        large("single-line.json", 20L * 1024 * 1024, true);
        pdf("thousand.pdf", 1000, false, false, false);
        text("한글.txt", "\ufeff입하 정답은 BLUE72입니다. 중간 AMBER24 마지막 GREEN12", StandardCharsets.UTF_8, List.of("입하", "BLUE72", "GREEN12"));
        text("업무표.txt", "\ufeff입하 정답은 BLUE72입니다. 중간 AMBER24 마지막 GREEN12", StandardCharsets.UTF_16LE, List.of("입하", "BLUE72", "GREEN12"));
        text("입하.txt", "입하 정답은 BLUE72입니다. 중간 AMBER24 마지막 GREEN12", Charset.forName("MS949"), List.of("입하", "BLUE72", "GREEN12"));
        text("보고서.md", "입하 정답은 BLUE72입니다. AMBER24 GREEN12", StandardCharsets.UTF_8, List.of("입하", "BLUE72", "GREEN12"));
        text("공백 ' 인용 🧪.txt", "입하 정답은 BLUE72입니다. AMBER24 GREEN12", StandardCharsets.UTF_8, List.of("입하", "BLUE72", "GREEN12"));
        assertThat(fixtures).hasSize(20);
        for (Fixture fixture : fixtures) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = new java.security.DigestInputStream(Files.newInputStream(fixture.path()), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            fixtureHashes.put(fixture.path(), java.util.HexFormat.of().formatHex(digest.digest()));
        }
    }

    @AfterAll void report() throws Exception {
        Files.writeString(Path.of("target/file-acceptance-results.json"), new JsonMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }

    @TestFactory Stream<DynamicTest> cases() {
        var tests = new ArrayList<DynamicTest>();
        for (int fixture = 0; fixture < 20; fixture++) {
            for (int action = 0; action < 5; action++) {
                int selectedFixture = fixture;
                int selectedAction = action;
                String id = "T%04d".formatted(101 + fixture * 5 + action);
                tests.add(DynamicTest.dynamicTest(id, () -> execute(id, selectedFixture, selectedAction)));
            }
        }
        return tests.stream();
    }

    private void execute(String id, int fixtureIndex, int action) throws Exception {
        Fixture fixture = fixtures.get(fixtureIndex);
        var result = new LinkedHashMap<String, Object>();
        result.put("id", id);
        result.put("startedAt", Instant.now().toString());
        result.put("fixtureHash", fixtureHashes.get(fixture.path()));
        result.put("scope", "REAL_STREAMING_ANALYZER_AND_LEXICAL_INDEX_SYNTHETIC_FILES");
        result.put("fullAcceptancePassed", false);
        result.put("remaining", List.of("UI upload/database registration", "Actual application restart", "Semantic search and answers", "NFC/NFD collision handling and PDF reading-order require separate checks"));
        long start = System.nanoTime();
        try {
            var before = Files.getLastModifiedTime(fixture.path());
            var steps = new ArrayList<ProcessingProgress.Step>();
            Analysis analysis;
            try (var observer = ProcessingProgress.observe(steps::add); var input = Files.newInputStream(fixture.path())) {
                analysis = analyzer.analyzeStream(fixture.path().getFileName().toString(), input);
            }
            try (analysis) {
            result.put("bytes", Files.size(fixture.path()));
            result.put("analysisStatus", analysis.status());
            result.put("analysisError", analysis.error());
            result.put("extractedCharacters", analysis.textParts().stream().mapToLong(String::length).sum());
            result.put("progressEvents", steps.size());
            result.put("lastProgress", steps.isEmpty() ? null : steps.getLast());
            assertThat(Files.getLastModifiedTime(fixture.path())).isEqualTo(before);
            if (fixture.unreadable()) {
                assertThat(analysis.status()).isNotIn("READY");
                assertThat(analysis.error()).isNotBlank();
            } else {
                assertThat(analysis.status()).isIn("READY", "PARTIAL");
                for (String marker : fixture.markers()) assertThat(containsMarker(analysis.textParts(), marker)).as("Extracted body must include %s", marker).isTrue();
                if (fixtureIndex == 4) assertThat(analysis.text()).doesNotContain("BLUE36", "WRONG_SCRIPT");
                if (fixtureIndex == 15) assertThat(analysis.text()).doesNotStartWith("\ufeff");
            }
            if (action == 2) {
                try (var input = Files.newInputStream(fixture.path());
                     var repeated = analyzer.analyzeStream(fixture.path().getFileName().toString(), input)) {
                    assertThat(repeated.status()).isEqualTo(analysis.status());
                    assertThat(repeated.error()).isEqualTo(analysis.error());
                    assertThat(repeated.mediaType()).isEqualTo(analysis.mediaType());
                    assertThat(repeated.symbols()).isEqualTo(analysis.symbols());
                    assertThat(dev.knowledgeforge.search.domain.ContentDigest.ofParts(repeated.textParts()))
                            .isEqualTo(dev.knowledgeforge.search.domain.ContentDigest.ofParts(analysis.textParts()));
                }
            }
            if (!fixture.unreadable() && (action == 1 || action == 3 || action == 4)) {
                Path indexDirectory = directory.resolve(id + "-index");
                try (var index = new LuceneSearchIndex(indexDirectory.toString())) {
                    index.put(new SearchIndex.Document("target", "one", fixture.path().getFileName().toString(), analysis.text(), "", "synthetic", "", fixture.path().getFileName().toString(),
                            "ORIGINAL", null, null, List.of(""), List.of(), analysis.parts()));
                    if (action == 4) {
                        for (int number = 0; number < 50; number++) index.put(new SearchIndex.Document("noise-" + number, "one", "noise" + number + ".txt", "Unrelated garden observations " + number, "", "noise", "", "noise" + number + ".txt"));
                    }
                    index.commit();
                    if (action != 3) questions(index, fixture);
                }
                if (action == 3) {
                    try (var reopened = new LuceneSearchIndex(indexDirectory.toString())) { questions(reopened, fixture); }
                    result.put("restartScope", "Index close/reopen only, not application restart");
                }
            }
            result.put("checkStatus", "PASS");
            }
            result.put("status", "PARTIAL");
        } catch (Throwable failure) {
            result.put("checkStatus", "FAIL");
            result.put("status", "FAIL");
            result.put("failure", failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage().substring(0, Math.min(2000, failure.getMessage().length())));
            throw failure;
        } finally {
            result.put("elapsedMillis", (System.nanoTime() - start) / 1_000_000);
            results.add(result);
            System.out.println("FILE_ACCEPTANCE " + id + " " + result.get("checkStatus"));
        }
    }

    private void questions(LuceneSearchIndex index, Fixture fixture) {
        for (String marker : fixture.markers()) {
            var hits = index.search("one", marker, List.of());
            assertThat(hits.stream().limit(5).map(SearchIndex.Hit::entryId)).contains("target");
            assertThat(hits.stream().filter(hit -> hit.entryId().equals("target")))
                    .as("Returned evidence must include complete marker %s", marker)
                    .anySatisfy(hit -> assertThat(containsMarker(hit.snippet(), marker)).isTrue());
        }
    }

    private boolean containsMarker(String text, String marker) {
        return java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(marker)
                + "(?![A-Za-z0-9_])").matcher(text).find();
    }

    private boolean containsMarker(List<String> parts, String marker) {
        var pattern = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(marker)
                + "(?![A-Za-z0-9_])");
        String tail = "";
        for (int index = 0; index < parts.size(); index++) {
            String window = tail + parts.get(index);
            var matches = pattern.matcher(window);
            while (matches.find()) {
                if ((matches.start() > 0 || index == 0)
                        && (matches.end() < window.length() || index == parts.size() - 1)) return true;
            }
            tail = window.substring(Math.max(0, window.length() - marker.length() - 2));
        }
        return false;
    }

    @org.junit.jupiter.api.Test void markerBoundariesRejectNumericSuffixesButAllowKoreanParticles() {
        assertThat(containsMarker("PAGE1000", "PAGE1")).isFalse();
        assertThat(containsMarker("ITEM500", "ITEM50")).isFalse();
        assertThat(containsMarker("PAGE1000 BLUE72", "PAGE1000")).isTrue();
        assertThat(containsMarker("정답은 BLUE72입니다.", "BLUE72")).isTrue();
    }

    private void text(String name, String content, Charset charset, List<String> markers) throws Exception {
        Path path = directory.resolve(name);
        Files.write(path, content.getBytes(charset));
        fixtures.add(new Fixture(path, markers, false));
    }

    private void large(String name, long size, boolean singleLine) throws Exception {
        Path path = directory.resolve(name);
        byte[] block = (singleLine ? "x".repeat(65536) : "Synthetic text filler.\n".repeat(3000)).getBytes(StandardCharsets.UTF_8);
        try (var output = Files.newOutputStream(path)) {
            long written = 0;
            while (written < size) {
                int count = (int) Math.min(block.length, size - written);
                output.write(block, 0, count);
                written += count;
            }
        }
        try (var file = new java.io.RandomAccessFile(path.toFile(), "rw")) {
            if (singleLine) { file.seek(0); file.write("{\"data\":\"".getBytes(StandardCharsets.UTF_8)); }
            for (long offset : new long[]{1024, size / 2, size - 128}) {
                file.seek(offset);
                String marker = offset == 1024 ? " FIRST72 " : offset == size / 2 ? " MIDDLE24 " : " FINAL72 ";
                file.write(marker.getBytes(StandardCharsets.UTF_8));
            }
            if (singleLine) { file.seek(size - 2); file.write("\"}".getBytes(StandardCharsets.UTF_8)); }
        }
        fixtures.add(new Fixture(path, List.of("FIRST72", "MIDDLE24", "FINAL72"), false));
    }

    private void pdf(String name, int pages, boolean scan, boolean encrypted, boolean columns) throws Exception {
        Path path = directory.resolve(name);
        try (var pdf = new PDDocument()) {
            for (int pageNumber = 1; pageNumber <= pages; pageNumber++) {
                var page = new PDPage();
                pdf.addPage(page);
                try (var content = new PDPageContentStream(pdf, page)) {
                    if (scan) {
                        var image = new java.awt.image.BufferedImage(200, 60, java.awt.image.BufferedImage.TYPE_INT_RGB);
                        var graphics = image.createGraphics();
                        graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, 200, 60);
                        graphics.setColor(java.awt.Color.BLACK); graphics.drawString("BLUE72", 10, 30); graphics.dispose();
                        content.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(pdf, image), 50, 650);
                    } else {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(50, 700);
                        content.showText("PAGE" + pageNumber + " BLUE72");
                        if (columns) { content.newLineAtOffset(250, 0); content.showText("AMBER24 GREEN12"); }
                        content.endText();
                    }
                }
            }
            if (encrypted) pdf.protect(new StandardProtectionPolicy("synthetic-owner", "synthetic-password", new AccessPermission()));
            pdf.save(path.toFile());
        }
        fixtures.add(new Fixture(path, scan || encrypted ? List.of() : columns ? List.of("BLUE72", "AMBER24", "GREEN12")
                : List.of("PAGE1", "PAGE" + Math.max(1, pages / 2), "PAGE" + pages), scan || encrypted));
    }
}
