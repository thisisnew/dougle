package dev.dougle.knowledge.application;

public interface ProviderConnection {
    record Status(String provider, String state, String checkedAt, String remoteConnection) {}
    Status check(CuratorModel.Provider provider);
}
