package dev.knowledgeforge.knowledge.application;

public interface ProviderLogin {
    record Status(String id, String provider, String state) {}
    Status start(CuratorModel.Provider provider, boolean acknowledged);
    Status status(String id);
    Status cancel(String id);
}
