package dev.knowledgeforge;

import dev.knowledgeforge.search.adapter.LocalSearchModel;
import dev.knowledgeforge.search.adapter.LuceneSearchIndex;
import dev.knowledgeforge.search.application.SearchIndex;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "FORGE_VERIFY_SEARCH_MODEL_DIR", matches = ".+")
class MultilingualSearchTest {
    @TempDir Path directory;

    @Test void bundledModelLoadsWithoutDownloadAndWaitsForItsIndex() throws Exception {
        Path bundle = Path.of(System.getenv("FORGE_VERIFY_SEARCH_MODEL_DIR")).resolve("search-model-e5-v1");
        try (var model = new LocalSearchModel(directory.toString(), bundle, true)) {
            assertThat(model.ready()).isFalse();
            var indexed = new java.util.concurrent.atomic.AtomicBoolean();
            model.prepare(() -> {
                assertThat(model.ready()).isTrue();
                assertThat(model.status().state()).isEqualTo("INDEXING");
                assertThat(model.preparing()).isTrue();
                indexed.set(true);
            });
            long deadline = System.nanoTime() + java.time.Duration.ofMinutes(2).toNanos();
            while (!List.of("READY", "ERROR").contains(model.status().state()) && System.nanoTime() < deadline) Thread.sleep(50);
            assertThat(model.status().state()).isEqualTo("READY");
            assertThat(model.preparing()).isFalse();
            assertThat(indexed).isTrue();
            assertThat(java.nio.file.Files.exists(directory.resolve("search-model-e5-v1"))).isFalse();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FORGE_VERIFY_SEARCH_REPO", matches = ".+")
    void registeredRepositoryFindsStrategyExamplesInKorean() throws Exception {
        Path repository = Path.of(System.getenv("FORGE_VERIFY_SEARCH_REPO"));
        try (var model = new LocalSearchModel(System.getenv("FORGE_VERIFY_SEARCH_MODEL_DIR"));
             var index = new LuceneSearchIndex(directory.toString(), model);
             var files = java.nio.file.Files.walk(repository)) {
            assertThat(model.ready()).isTrue();
            for (Path file : files.filter(java.nio.file.Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".java") || file.toString().endsWith(".md")).sorted().toList()) {
                String path = repository.relativize(file).toString();
                index.put(new SearchIndex.Document(path, "one", file.getFileName().toString(),
                        java.nio.file.Files.readString(file), "", "repo", "", path));
            }
            index.put(new SearchIndex.Document("unrelated", "one", "가상 운영.pdf",
                    "운영 패턴을 정리했습니다. 월평균 수기 보정은 120건입니다.", "", "pdf", "", "가상 운영.pdf"));
            index.commit();
            for (String question : List.of("전략패턴 예제좀", "전략패턴 어떻게짜냐", "알고리즘을 교체하는 예제")) {
                var hits = index.searchQuestion("one", question, List.of(), false);
                System.out.println("REPOSITORY_QUERY " + question + " " + hits.stream().map(hit -> hit.path() + ":" + hit.score()).toList());
                assertThat(hits).isNotEmpty();
                assertThat(hits.getFirst().path()).contains("/strategy/");
                assertThat(hits).extracting(SearchIndex.Hit::entryId).doesNotContain("unrelated");
            }
        }
    }

    @Test
    void realLocalModelFindsCrossLanguageAndParaphrasedEvidenceWithoutSendingDocuments() throws Exception {
        try (var model = new LocalSearchModel(System.getenv("FORGE_VERIFY_SEARCH_MODEL_DIR"))) {
            if (!model.ready()) {
                model.prepare(() -> {});
                long deadline = System.nanoTime() + java.time.Duration.ofMinutes(10).toNanos();
                while (!List.of("READY", "ERROR").contains(model.status().state()) && System.nanoTime() < deadline) Thread.sleep(500);
            }
            assertThat(model.status().state()).withFailMessage("Search model: %s", model.status()).isEqualTo("READY");
            try (var index = new LuceneSearchIndex(directory.toString(), model)) {
                index.put(new SearchIndex.Document("strategy", "one", "Strategy.java",
                        "package patterns.strategy;\npublic interface Strategy { int choose(int input); }\n"
                        + "class Player { private Strategy strategy; public void setStrategy(Strategy next) { strategy = next; }\n"
                        + "public int play(int value) { return strategy.choose(value); } }\n"
                        + "The Strategy pattern lets a client replace an algorithm and switch behavior at runtime.",
                        "Strategy Player choose setStrategy play", "repo", "", "patterns/strategy/Strategy.java"));
                index.put(new SearchIndex.Document("invoice", "one", "operations.pdf",
                        "가상 운영 업무에서 송장 처리 패턴을 정리했습니다. 월평균 수기 보정은 120건입니다.", "", "pdf", "", "operations.pdf"));
                index.put(new SearchIndex.Document("private", "other", "Strategy.java",
                        "Strategy design pattern replace algorithms at runtime", "", "private", "", "Strategy.java"));
                index.commit();
                for (String question : List.of("전략패턴 예제좀", "알고리즘을 상황에 따라 교체하는 코드는?")) {
                    var hits = index.searchQuestion("one", question, List.of(), false);
                    assertThat(hits).withFailMessage("Question %s returned %s", question, hits).isNotEmpty();
                    assertThat(hits.getFirst().entryId()).withFailMessage("Question %s returned %s", question, hits).isEqualTo("strategy");
                    assertThat(hits).extracting(SearchIndex.Hit::entryId).doesNotContain("private");
                    assertThat(hits.getFirst().snippet()).contains("setStrategy", "strategy.choose");
                }
                var operations = index.searchQuestion("one", "한 달에 사람이 직접 고치는 건수는?", List.of(), false);
                assertThat(operations).isNotEmpty();
                assertThat(operations.getFirst().entryId()).isEqualTo("invoice");
                assertThat(index.searchQuestion("one", "달 표면의 중력은 얼마인가?", List.of(), false)).isEmpty();
                assertThat(index.searchQuestion("one", "피자 만드는 방법은?", List.of(), false)).isEmpty();
                assertThat(index.searchQuestion("one", "전략패턴", List.of("other-domain"), false)).isEmpty();
                index.put(new SearchIndex.Document("strategy", "one", "Player.java",
                        "package patterns.strategy;\npublic class Player {\nprivate Strategy strategy;\n"
                        + "public void setStrategy(Strategy next) { strategy = next; }\n"
                        + "public int play(int value) { return strategy.choose(value); }\n}",
                        "Player setStrategy play", "repo", "", "patterns/strategy/Player.java"));
                index.commit();
                assertThat(index.searchQuestion("one", "전략패턴 예제좀", List.of(), false))
                        .isNotEmpty().first().extracting(SearchIndex.Hit::entryId).isEqualTo("strategy");
            }
        }
        try (var reopened = new LocalSearchModel(System.getenv("FORGE_VERIFY_SEARCH_MODEL_DIR"))) {
            assertThat(reopened.ready()).isTrue();
        }
    }
}
