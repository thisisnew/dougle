package dev.dougle.sources.adapter;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class WebSourcePolicyTest {
    @ParameterizedTest
    @ValueSource(strings = {"https://github.com/org/repo", "https://GITHUB.COM./org/repo", "https://api.github.com/repos/org/repo", "https://raw.githubusercontent.com/org/repo/main/file", "https://github.com/org/repo/blob/main/file"})
    void blocksGithubIncludingWebFileLinks(String location) {
        assertThatThrownBy(() -> WebSourcePolicy.validate(location)).hasMessageContaining("GitHub 연결은 지원하지 않습니다");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.org/document", "https://synthetic.atlassian.net/wiki/spaces/TEAM/pages/123", "https://example.org/github.com"})
    void allowsWebDocuments(String location) {
        assertThat(WebSourcePolicy.validate(location).toString()).isEqualTo(location);
    }
}
