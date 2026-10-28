package dev.dougle.ingestion.application;

import dev.dougle.ingestion.domain.Analysis;

public interface DocumentAnalyzer {
    Analysis analyze(String name, byte[] content);

    default Analysis analyzeStream(String name, java.io.InputStream content) throws java.io.IOException {
        return analyze(name, content.readAllBytes());
    }
}
