package dev.dougle.plugins.adapter;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class AtlassianResourceListTest {
    private final JsonMapper json = new JsonMapper();
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"structuredContent\":[{\"id\":\"one\",\"url\":\"https://fixture.atlassian.net\"}]}",
        "{\"structuredContent\":{\"result\":[{\"id\":\"one\",\"url\":\"https://fixture.atlassian.net\"}]} }",
        "{\"structuredContent\":{\"resources\":[]}}",
        "{\"structuredContent\":{\"result\":{\"resources\":[]}}}",
        "{\"content\":[{\"type\":\"text\",\"text\":\"[]\"}]}",
        "{\"structuredContent\":null,\"content\":[{\"type\":\"text\",\"text\":\"[]\"}]}",
        "{\"structuredContent\":{},\"content\":[{\"type\":\"text\",\"text\":\"[]\"}]}"
    })
    void decodesKnownListEnvelopes(String wire) {
        assertThat(AtlassianResourceList.decode(json.readTree(wire)).isArray()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}", "{\"structuredContent\":true}", "{\"structuredContent\":{\"unknown\":[]}}",
        "{\"structuredContent\":{\"result\":[],\"resources\":[]}}",
        "{\"structuredContent\":{\"result\":null},\"content\":[{\"type\":\"text\",\"text\":\"[]\"}]}",
        "{\"content\":[{\"type\":\"text\",\"text\":\"PRIVATE invalid JSON\"}]}",
        "{\"content\":[{\"type\":\"text\",\"text\":\"[] []\"}]}",
        "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"result\\\":[],\\\"result\\\":[]}\"}]}",
        "{\"structuredContent\":{\"result\":{\"result\":{\"result\":{\"result\":[]}}}}}"
    })
    void rejectsUnknownAmbiguousAndMalformedShapesWithoutLeakingBody(String wire) {
        assertThatThrownBy(() -> AtlassianResourceList.decode(json.readTree(wire)))
                .hasMessageContaining("목록 형식").hasMessageNotContaining("PRIVATE");
    }
}
