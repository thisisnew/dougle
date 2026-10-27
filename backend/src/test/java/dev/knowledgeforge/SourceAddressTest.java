package dev.knowledgeforge;

import dev.knowledgeforge.sources.application.SourceAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SourceAddressTest {
    @Test
    void repositoryUiVariationsNormalizeWithoutChangingRepositoryPath() {
        for (String address : List.of("https://github.com/example/repo?tab=readme-ov-file",
                "https://github.com/example/repo#readme", "https://GITHUB.com/example/repo", "https://github.com:443/example/repo")) {
            assertThat(SourceAddress.resolve("URL", address)).isEqualTo(new SourceAddress("GITHUB", "https://github.com/example/repo"));
        }
    }

    @Test
    void unsafeOrScopeChangingPartsAreNotStrippedBeforeValidation() {
        for (String address : List.of("https://github.com/example/repo?token=secret", "https://github.com/example/repo?tab=issues",
                "https://github.com:444/example/repo", "https://user@github.com/example/repo",
                "https://github.com/example/repo/blob/main/App.java#L12", "https://github.com/example/../")) {
            assertThat(SourceAddress.resolve("GITHUB", address)).isEqualTo(new SourceAddress("URL", address));
        }
    }
}
