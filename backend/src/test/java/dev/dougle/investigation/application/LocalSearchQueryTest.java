package dev.dougle.investigation.application;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LocalSearchQueryTest {
    @Test
    void boundsOriginalInputAndNormalizesOnlySurroundingWhitespace() {
        assertEquals("Strategy policy", new LocalSearchQuery(" Strategy policy ").question());
        assertEquals(256, new LocalSearchQuery("x".repeat(256)).question().length());
        assertThrows(IllegalArgumentException.class, () -> new LocalSearchQuery(null));
        assertThrows(IllegalArgumentException.class, () -> new LocalSearchQuery(""));
        assertThrows(IllegalArgumentException.class, () -> new LocalSearchQuery(" \n\t "));
        assertThrows(IllegalArgumentException.class, () -> new LocalSearchQuery("x".repeat(257)));
        assertThrows(IllegalArgumentException.class, () -> new LocalSearchQuery(" ".repeat(256) + "x"));
    }
}
