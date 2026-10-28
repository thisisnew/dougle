package dev.dougle.plugins.domain;

import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;

class PluginProviderTest {
    @ParameterizedTest
    @ValueSource(strings = { "https://app.notion.com/p/synthetic", "https://www.notion.so/synthetic",
            "https://sample.notion.site/synthetic", "https://notion.com/p/synthetic", "https://APP.NOTION.COM./p/synthetic" })
    void identifiesNotionPages(String location) {
        assertThat(PluginProvider.forWebLink(URI.create(location))).contains(PluginProvider.NOTION);
    }

    @ParameterizedTest
    @ValueSource(strings = { "https://notion.com/help", "https://app.notion.com.example.org/p/synthetic",
            "https://example.org/notion.so", "http://app.notion.com/p/synthetic" })
    void excludesUnrelatedHostsAndDocumentation(String location) {
        assertThat(PluginProvider.forWebLink(URI.create(location))).isEmpty();
    }
}
