package dev.knowledgeforge.investigation.adapter;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OperationalResultTextTest {
    @Test
    void approvedScopeIsReservedBeforeRowsAndCountsAgainst3000Characters() {
        var scope = OperationalResultText.provenance(java.util.Map.of("environment", "fixture", "timezone", "UTC"), java.util.List.of());
        var result = OperationalResultText.snapshot("DATADOG", "fixture", "2026-10-25T12:00:00Z", 1, "😀".repeat(4000), false, scope);
        assertTrue(result.text().contains("\"environment\":\"fixture\""));
        assertTrue(result.text().contains("\"timezone\":\"UTC\""));
        assertTrue(result.text().contains("범위 잘림: 아니요"));
        assertTrue(result.truncated());
        assertTrue(result.text().length() <= 3000);
        assertEquals(result.text(), new String(result.text().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Test
    void expandedSecretsAndOversizedScopeAreRedactedBeforeTruncationAndExplicitlyFlagged() {
        var scope = OperationalResultText.provenance(java.util.Map.of("equalities", "x".repeat(2000), "url", "https://private.invalid"), java.util.List.of("x"));
        assertFalse(scope.text().contains("x"));
        assertFalse(scope.text().contains("private.invalid"));
        assertTrue(scope.truncated());
        assertTrue(scope.text().length() <= 1400);
        var result = OperationalResultText.snapshot("MYSQL", "fixture", "2026-10-25T12:00:00Z", 1, "row", false, scope);
        assertTrue(result.text().contains("[TRUNCATED_SCOPE]"));
        assertTrue(result.text().contains("유효한 JSON이 아닌 조각일 수 있음"));
        assertTrue(result.text().contains("저장 시 추가 조회 없음"));
        assertFalse(result.text().contains("외부 전송 없음"));
        assertTrue(result.text().contains("일부만 보관: 예"));
        assertTrue(result.text().endsWith("row"));
        assertTrue(result.truncated());
        assertTrue(result.text().length() <= 3000);
    }

    @Test
    void scopeEscapesNewlinesAndMasksKnownJsonEncodedCredentials() {
        String secret = "fake\"\\\n😀";
        var scope = OperationalResultText.provenance(java.util.Map.of("predicate", secret, "value", "line\nforged header"), java.util.List.of(secret));
        assertFalse(scope.text().contains("fake"));
        assertFalse(scope.text().contains("\n"));
        assertTrue(scope.text().contains("[REDACTED]"));
        assertFalse(scope.truncated());
    }

    @Test
    void shortSecretReplacementIsBoundedAfterExpansion() {
        var result = OperationalResultText.redactAndBound("x".repeat(16000), "x", 16384, false);
        assertTrue(result.truncated());
        assertTrue(result.text().getBytes(StandardCharsets.UTF_8).length <= 16384);
        assertFalse(result.text().contains("x"));
        assertTrue(result.text().endsWith("[TRUNCATED]"));
    }

    @Test
    void boundsUtf8WithoutSplittingSupplementaryCharactersAndPreservesPriorTruncation() {
        var result = OperationalResultText.bound("가😀".repeat(4000), 16384, false);
        assertTrue(result.truncated());
        assertTrue(result.text().getBytes(StandardCharsets.UTF_8).length <= 16384);
        assertEquals(result.text(), new String(result.text().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertTrue(OperationalResultText.bound("short", 100, true).truncated());
        assertFalse(OperationalResultText.bound("short", 100, false).truncated());
    }

    @Test
    void masksRawAndMysqlJsonEncodedSecretsBeforeClipping() {
        String secret = "quote\"\\\n😀";
        String escaped = "quote\\\"\\\\\\u000a\\ud83d\\ude00";
        var result = OperationalResultText.redactAndBound(secret + " " + escaped, secret, 16384, false);
        assertEquals("[REDACTED] [REDACTED]", result.text());
    }

    @Test
    void snapshotHasExplicitScopeLimitationsAndIsAtMost3000CharactersIncludingProvenance() {
        var result = OperationalResultText.snapshot("MYSQL", "synthetic-result", "2026-10-25T12:00:00Z", 0,
                "가😀".repeat(3000), true);
        assertTrue(result.text().length() <= 3000);
        assertTrue(result.truncated());
        assertTrue(result.text().contains("조회 조건·환경 정보는 이 첨부에 포함되지 않음"));
        assertTrue(result.text().contains("환경/대상/시각 범위를 추정하지 말 것"));
        assertTrue(result.text().contains("조회 대상 시간 범위 아님"));
        assertTrue(result.text().contains("0행은 범위 전체에 일치 자료가 없음을 의미하지 않습니다"));
        assertTrue(result.text().contains("일부만 보관: 예"));
        assertEquals(result.text(), new String(result.text().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Test
    void snapshotMasksCommonSecretsAndUrlsAndCleansBinaryControls() {
        var result = OperationalResultText.snapshot("DATADOG", "fixture", "2026-10-25T12:00:00Z", 1,
                "password=fictional https://private.invalid/path user@example.invalid\u0000\r\nnormal", false);
        assertFalse(result.text().contains("fictional"));
        assertFalse(result.text().contains("private.invalid"));
        assertFalse(result.text().contains("user@example.invalid"));
        assertFalse(result.text().contains("\u0000"));
        assertFalse(result.text().contains("\r"));
        assertFalse(result.truncated());
    }
}
