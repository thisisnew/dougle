package dev.dougle.ingestion.adapter;

import dev.dougle.sources.adapter.LocalFolderCollector;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

class LocalFolderIngestionMatrixTest {
    @TempDir Path directory;

    record Source(String name, String text) {
        @Override public String toString() { return name; }
    }

    enum Encoding {
        UTF8(StandardCharsets.UTF_8, false), UTF8_BOM(StandardCharsets.UTF_8, true),
        UTF16_LE(StandardCharsets.UTF_16LE, true), UTF16_BE(StandardCharsets.UTF_16BE, true);
        final Charset charset;
        final boolean bom;
        Encoding(Charset charset, boolean bom) { this.charset = charset; this.bom = bom; }
        byte[] bytes(String text) { return ((bom ? "\ufeff" : "") + text).getBytes(charset); }
    }

    enum Layout {
        FLAT, NESTED, KOREAN, SPACES, UPPERCASE;
        String path(String name) {
            return switch (this) {
                case FLAT -> name;
                case NESTED -> "src/main/config/" + name;
                case KOREAN -> "가상/자료/" + name;
                case SPACES -> "synthetic project/config files/" + name;
                case UPPERCASE -> "SRC/" + name.toUpperCase(Locale.ROOT);
            };
        }
    }

    enum Newline {
        LF("\n"), CRLF("\r\n"), CR("\r");
        final String value;
        Newline(String value) { this.value = value; }
    }

    static List<Source> sources() {
        return List.of(
                new Source("Sample.java", "package synthetic;\nclass Sample {\n String marker = \"MARKER\";\n int measure() { return 7; }\n}\n"),
                new Source("build.gradle", "plugins { id 'java' }\nversion = 'MARKER'\n"),
                new Source("sample.kt", "val marker = \"MARKER\"\nfun measure() = 7\n"),
                new Source("sample.js", "const marker = 'MARKER';\nexport const measure = () => 7;\n"),
                new Source("sample.ts", "const marker: string = 'MARKER';\nexport const count: number = 7;\n"),
                new Source("sample.py", "marker = 'MARKER'\ndef measure():\n    return 7\n"),
                new Source("sample.go", "package synthetic\nconst marker = \"MARKER\"\n"),
                new Source("sample.rs", "const MARKER: &str = \"MARKER\";\nfn measure() -> i32 { 7 }\n"),
                new Source("sample.c", "const char *marker = \"MARKER\";\nint measure(void) { return 7; }\n"),
                new Source("sample.swift", "let marker = \"MARKER\"\nlet count = 7\n"),
                new Source("sample.sh", "#!/bin/sh\nmarker='MARKER'\nprintf '%s' \"$marker\"\n"),
                new Source("sample.ps1", "$marker = 'MARKER'\nWrite-Output $marker\n"),
                new Source("sample.yaml", "marker: MARKER\ncount: 7\n"),
                new Source("sample.json", "{\n  \"marker\": \"MARKER\",\n  \"count\": 7\n}\n"),
                new Source("sample.toml", "[synthetic]\nmarker = 'MARKER'\ncount = 7\n"),
                new Source("sample.properties", "marker=MARKER\ncount=7\n"),
                new Source("sample.xml", "<synthetic>\n<marker>MARKER</marker>\n</synthetic>\n"),
                new Source("sample.css", "/* MARKER */\n.synthetic { color: green; }\n"),
                new Source("sample.md", "# Synthetic\nMARKER\n- seven\n"),
                new Source("sample.csv", "marker,count\nMARKER,7\n"),
                new Source("Dockerfile", "FROM scratch\nLABEL marker=\"MARKER\"\n"),
                new Source("Makefile", "MARKER := MARKER\nall:\n\t@echo $(MARKER)\n"),
                new Source(".gitignore", "# MARKER\n*.synthetic\n"),
                new Source(".editorconfig", "# MARKER\nroot = true\n[*]\ncharset = utf-8\n"));
    }

    static Stream<Arguments> roundTrips() {
        return sources().stream().flatMap(source -> Stream.of(Encoding.values()).flatMap(encoding ->
                Stream.of(Layout.values()).flatMap(layout -> Stream.of(Newline.values()).flatMap(newline ->
                        Stream.of(Normalizer.Form.NFC, Normalizer.Form.NFD).map(form ->
                                Arguments.of(source, encoding, layout, newline, form))))));
    }

    @ParameterizedTest(name = "LFI-{index}: source={0} encoding={1} layout={2} newline={3} unicode={4}")
    @MethodSource("roundTrips")
    void collectsAndAnalyzesWithoutChangingOriginal(Source source, Encoding encoding, Layout layout,
                                                   Newline newline, Normalizer.Form form) throws Exception {
        String marker = Normalizer.normalize("가상 café 😀", form);
        String originalText = source.text().replace("MARKER", marker).replace("\n", newline.value);
        String expectedText = source.text().replace("MARKER", "가상 café 😀").strip();
        byte[] original = encoding.bytes(originalText);
        String relative = layout.path(source.name());
        Path file = directory.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, original);
        var modified = Files.getLastModifiedTime(file);
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        try (var snapshot = new LocalFolderCollector().collect(directory.toString())) {
            assertThat(snapshot.failures()).isEmpty();
            assertThat(snapshot.files()).hasSize(1);
            var collected = snapshot.files().getFirst();
            assertThat(Normalizer.normalize(collected.path(), Normalizer.Form.NFC)).isEqualTo(relative);
            assertThat(collected.size()).isEqualTo(original.length);
            assertThat(collected.hash()).isEqualTo(expectedHash);
            assertThat(collected.content()).containsExactly(original);
            try (var input = collected.open(); var analysis = new TikaJavaAnalyzer().analyzeStream(collected.path(), input)) {
                assertThat(analysis.status()).isEqualTo("READY");
                assertThat(analysis.error()).isNull();
                assertThat(String.join("", analysis.textParts())).isEqualTo(expectedText);
                assertThat(analysis.mediaType()).isEqualTo("text/plain");
                if (source.name().endsWith(".java")) {
                    assertThat(analysis.symbols()).extracting(symbol -> symbol.name()).containsExactly("Sample", "measure");
                    assertThat(analysis.symbols()).extracting(symbol -> symbol.line()).containsExactly(2, 4);
                } else {
                    assertThat(analysis.symbols()).isEmpty();
                }
            }
        }
        assertThat(Files.readAllBytes(file)).containsExactly(original);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
    }
}
