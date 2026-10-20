package dev.knowledgeforge.ingestion.application;

import dev.knowledgeforge.ingestion.domain.Analysis;

public interface DocumentAnalyzer {
    Analysis analyze(String name, byte[] content);
}
