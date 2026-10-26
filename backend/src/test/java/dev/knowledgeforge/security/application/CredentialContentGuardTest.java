package dev.knowledgeforge.security.application;

import dev.knowledgeforge.library.application.LibraryException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class CredentialContentGuardTest {
    @Test void knownPatternsAreBlockedWithoutEchoingTheMatchedContent() {
        for (var secret : List.of("password=Synthet1c-Only-Value-7823", "{\"api_key\":\"Synthet1c-Only-Value-7823\"}",
                "Authorization: Bearer Synthet1c-Only-Value-7823", "Basic c3ludGhldGljOnRlc3Q=", "Cookie: session=Synthet1c-Only-Value-7823",
                "-----BEGIN RSA PRIVATE KEY-----", "eyJsynthetic.eyJpayload.signature",
                "xoxb-synthetic-secret", "xapp-synthetic-secret", "AKIAABCDEFGHIJKLMNOP",
                "ghp_abcdefghijklmnopqrst", "github_pat_abcdefghijklmnopqrst", "sk-proj-abcdefghijklmnopqrst",
                "https://reader:synthetic@example.invalid", "refresh_token=Synthet1c-Only-Value-7823")) {
            assertThat(CredentialContentGuard.containsCredential(secret)).isTrue();
            assertThatThrownBy(() -> CredentialContentGuard.requireSafe(secret))
                    .isInstanceOf(LibraryException.class).hasMessageNotContaining(secret).hasCause(null);
        }
    }

    @Test void ordinaryContentAndExistingRedactionMarkersRemainUnchanged() {
        for (var safe : List.of("Explain token rotation", "The password field is required", "person@example.invalid",
                "https://example.invalid/docs", "token=[REDACTED]", "authorization: [REDACTED_AUTH]",
                "Synthetic Strategy delegates work.", "token=id", "password=example", "const token = id;",
                "api_key=process.env.API_KEY_12345", "password=${MY_PASSWORD_1234}", "secret=<YOUR_SECRET_12345>",
                "password=your_password_12345", "Authorization: Bearer token")) {
            assertThat(CredentialContentGuard.containsCredential(safe)).isFalse();
            CredentialContentGuard.requireSafe(safe);
        }
    }

    @Test void decodedNestedStringsAndEscapedJsonAreCheckedWithoutChangingTree() {
        var json = JsonMapper.builder().build();
        var tree = json.readTree("{\"history\":[{\"answer\":\"api_key=Synthet1c-Only-Value-7823\"}]}");
        String original = tree.toString();
        assertThatThrownBy(() -> CredentialContentGuard.requireSafeTree(tree)).isInstanceOf(LibraryException.class);
        assertThat(tree.toString()).isEqualTo(original);
        assertThat(CredentialContentGuard.containsCredential("{\\\"password\\\":\\\"Synthet1c-Only-Value-7823\\\"}")).isTrue();
    }
}
