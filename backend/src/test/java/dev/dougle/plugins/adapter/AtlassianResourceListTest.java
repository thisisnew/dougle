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
    @ParameterizedTest
    @ValueSource(strings = {"fixture.atlassian.net", "https://fixture.atlassian.net/", "https://FIXTURE.atlassian.net"})
    void normalizesCloudIdAndSiteAuthority(String url) {
        var value = json.createObjectNode().put("cloudId", "cloud-one").put("url", url);
        var site = AtlassianResourceList.site(value);
        assertThat(site.path("id").asText()).isEqualTo("cloud-one");
        assertThat(site.path("url").asText()).isEqualTo("https://fixture.atlassian.net");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://fixture.atlassian.net", "https://fixture.atlassian.net.evil.test", "fixture.atlassian.net@evil.test",
            "https://evil@fixture.atlassian.net", "https://fixture.atlassian.net:443", "https://fixture.atlassian.net?x=1",
            "https://fixture.atlassian.net#x", "https://fixture.atlassian.net/other", "//fixture.atlassian.net",
            "localhost", "127.0.0.1", "atlassian.net", "https://nested.fixture.atlassian.net", "https://fixture.atlassian.net/%2e%2e"})
    void rejectsUnsafeOrNonSiteAuthorities(String url) {
        assertThat(AtlassianResourceList.site(json.createObjectNode().put("cloudId", "cloud-one").put("url", url))).isNull();
    }

    @org.junit.jupiter.api.Test void rejectsAmbiguousAndMalformedIdentifiers() {
        var value = json.createObjectNode().put("cloudId", "one").put("id", "two").put("url", "fixture.atlassian.net");
        assertThat(AtlassianResourceList.site(value)).isNull();
        value.put("id", "one");
        assertThat(AtlassianResourceList.site(value).path("id").asText()).isEqualTo("one");
        value.put("cloudId", 12);
        assertThat(AtlassianResourceList.site(value)).isNull();
    }

    @org.junit.jupiter.api.Test void acceptsSingleCloudIdTextBlock() {
        var value = json.createObjectNode().put("cloudId", "one").put("url", "fixture.atlassian.net");
        var reply = json.createObjectNode();
        reply.putArray("content").addObject().put("type", "text").put("text", json.writeValueAsString(value));
        assertThat(AtlassianResourceList.decode(reply).get(0).path("cloudId").asText()).isEqualTo("one");
    }
}
