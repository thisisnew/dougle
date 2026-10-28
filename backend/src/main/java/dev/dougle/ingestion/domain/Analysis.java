package dev.dougle.ingestion.domain;

import java.util.List;

public record Analysis(String text, String mediaType, String status, String error, List<Symbol> symbols,
                       @com.fasterxml.jackson.annotation.JsonIgnore List<String> parts) implements AutoCloseable {
    public Analysis(String text, String mediaType, String status, String error, List<Symbol> symbols) {
        this(text, mediaType, status, error, symbols, List.of());
    }
    public Analysis {
        parts = parts == null ? List.of() : parts;
    }
    public List<String> textParts() { return parts.isEmpty() ? List.of(text) : parts; }
    @com.fasterxml.jackson.annotation.JsonProperty(value = "textTruncated", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public boolean textTruncated() { return !parts.isEmpty(); }
    public void close() {
        if (parts instanceof AutoCloseable resource) {
            try { resource.close(); }
            catch (Exception exception) { throw new IllegalStateException("Cannot remove extracted text spool", exception); }
        }
    }
    public record Symbol(String name, String kind, int line, String signature) {}
}
