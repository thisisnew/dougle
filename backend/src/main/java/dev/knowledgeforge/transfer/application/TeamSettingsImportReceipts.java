package dev.knowledgeforge.transfer.application;

public interface TeamSettingsImportReceipts {
    record Receipt(String digest, String previewId, String resultJson) {}
    boolean reserve(String requestId, String digest, String previewId);
    Receipt get(String requestId);
    void complete(String requestId, String resultJson);
}
