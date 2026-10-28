package dev.dougle;

import static org.junit.jupiter.api.Assertions.*;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ArchitectureContractTest {
    private final Path source = Path.of("src/main/java/dev/dougle");
    private final JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    @Test
    void allDomainTypesStayIndependentOfFrameworksAndOuterLayers() throws Exception {
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(path -> path.toString().contains("/domain/") && path.toString().endsWith(".java")).toList()) {
                var unit = parser.parse(file).getResult().orElseThrow();
                for (var dependency : unit.getImports()) {
                    String name = dependency.getNameAsString();
                    assertFalse(name.contains(".adapter.") || name.contains(".application.")
                            || name.startsWith("org.springframework.") || name.startsWith("java.sql.")
                            || name.startsWith("org.apache.lucene."), file + " imports " + name);
                }
            }
        }
    }

    @Test
    void applicationImportsPortsRatherThanAdapters() throws Exception {
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(path -> path.toString().contains("/application/") && path.toString().endsWith(".java")).toList()) {
                for (var dependency : parser.parse(file).getResult().orElseThrow().getImports()) {
                    assertFalse(dependency.getNameAsString().startsWith("dev.dougle.")
                            && dependency.getNameAsString().contains(".adapter."), file.toString());
                }
            }
        }
    }

    @Test
    void everyPersistedTableAppearsInTheModelDocument() throws Exception {
        String migration = Files.readString(source.resolve("library/adapter/persistence/DatabaseConfiguration.java"));
        String document = Files.readString(Path.of("../docs/modeling.md"));
        var matcher = Pattern.compile("CREATE TABLE (?:IF NOT EXISTS )?([a-z_]+)").matcher(migration);
        var tables = new HashSet<String>();
        while (matcher.find()) tables.add(matcher.group(1));
        assertTrue(tables.size() >= 20);
        for (String table : tables) assertTrue(document.contains("`" + table + "`"), "Undocumented table: " + table);
    }
}
