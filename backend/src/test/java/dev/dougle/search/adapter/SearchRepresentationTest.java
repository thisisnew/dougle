package dev.dougle.search.adapter;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SearchRepresentationTest {
    @Test void describesOnlyAnActualConstructorParameterAssignment() {
        String source = "class Renderer { private Policy policy; Renderer(Policy supplied) { this.policy = supplied; } }";
        assertThat(SearchRepresentation.of("Renderer.java", source))
                .contains("Renderer receives Policy from the caller and assigns it to this.policy", source);
        assertThat(SearchRepresentation.of("Renderer.java", "class Renderer { Renderer() { this.policy = create(); } }"))
                .doesNotContain("Constructor injection:");
    }

    @Test void splitsIdentifiersWithoutInventingABusinessMeaning() {
        String source = "class PurchaseOrderController { void submitPurchaseOrder() {} }";
        assertThat(SearchRepresentation.of("PurchaseOrderController.java", source))
                .contains("Purchase Order Controller", "submit Purchase Order", source).doesNotContain("입하", "Constructor injection:");
    }

    @Test void nonCodeDocumentsAreNotRewritten() {
        String source = "A constructor can receive input. this.policy = supplied;";
        assertThat(SearchRepresentation.of("guide.md", source)).isEqualTo("guide.md\n" + source);
    }

    @Test void incompleteChunkKeepsItsOriginalText() {
        String source = "return strategy.choose(input); }";
        assertThat(SearchRepresentation.of("Player.java", source)).contains(source).doesNotContain("Constructor injection:");
    }
}
