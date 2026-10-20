package dev.knowledgeforge.ingestion.domain;

import java.util.List;

public record Analysis(String text, String mediaType, String status, String error, List<Symbol> symbols) {
    public record Symbol(String name, String kind, int line, String signature) {}
}
