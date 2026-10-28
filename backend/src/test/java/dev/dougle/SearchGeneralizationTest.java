package dev.dougle;

import dev.dougle.search.adapter.LocalSearchModel;
import dev.dougle.search.adapter.LuceneSearchIndex;
import dev.dougle.search.application.SearchIndex;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@EnabledIfSystemProperty(named = "forge.acceptance.search", matches = "true")
class SearchGeneralizationTest {
    @TempDir Path directory;

    @Test void holdsOutDifferentCodeAndBusinessTopicsAgainstRepetitiveDistractors() throws Exception {
        try (var model = new LocalSearchModel(System.getenv("FORGE_VERIFY_SEARCH_MODEL_DIR"));
             var index = new LuceneSearchIndex(directory.toString(), model)) {
            assertThat(model.ready()).isTrue();
            index.put(document("Renderer.java", "class Renderer { private Policy policy; Renderer(Policy supplied) { this.policy = supplied; } }"));
            index.put(document("refund.md", "구독을 취소하면 남은 기간의 이용료를 환불합니다. 환불은 취소 승인일부터 영업일 기준 7일 이내에 원래 결제 수단으로 지급됩니다."));
            index.put(document("gateway.md", "The gateway forwards authenticated requests to the billing service. Requests without authentication are rejected with HTTP 401."));
            for (int number = 0; number < 64; number++) {
                index.put(document("weather-" + number + ".md", "실험실 " + number + "번 센서의 점검 주기는 30일입니다. 점검자는 배터리와 케이블 연결 상태를 확인합니다."));
            }
            index.commit();
            assertSoftly(soft -> {
                for (String question : List.of("외부에서 정책 객체를 받아 필드에 보관하는 부분은?", "Where does Renderer receive its Policy?")) {
                    soft.assertThat(index.searchQuestion("one", question, List.of(), false).stream().limit(5).map(SearchIndex.Hit::path))
                            .as(question).contains("Renderer.java");
                }
                for (String question : List.of("구독 해지하면 돈은 언제 돌려줘?", "환불받는 결제 수단과 처리 기간은?")) {
                    soft.assertThat(index.searchQuestion("one", question, List.of(), false).stream().limit(5).map(SearchIndex.Hit::path))
                            .as(question).contains("refund.md");
                }
                soft.assertThat(index.searchQuestion("one", "인증 없이 게이트웨이에 요청하면 어떻게 돼?", List.of(), false).stream().limit(5).map(SearchIndex.Hit::path))
                        .contains("gateway.md");
                for (String question : List.of("수성의 공전 주기는?", "오렌지 잼 만드는 방법", "바다의 평균 염도는?")) {
                    var hits = index.searchQuestion("one", question, List.of(), false);
                    if (!hits.isEmpty()) {
                        try {
                            var method = LuceneSearchIndex.class.getDeclaredMethod("semantic", String.class, String.class, List.class, boolean.class);
                            method.setAccessible(true);
                            System.out.println("HOLDOUT_CANDIDATES " + question + " " + method.invoke(index, "one", question, List.of(), false));
                        } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
                    }
                    soft.assertThat(hits).as(question).isEmpty();
                }
            });
        }
    }

    private SearchIndex.Document document(String name, String text) {
        return new SearchIndex.Document(name, "one", name, text, "", "synthetic", "", name);
    }
}
