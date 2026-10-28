package dev.dougle.knowledge.application;

public interface ProviderConnection {
    record Status(String provider, String state, String checkedAt, String remoteConnection, String reason) {
        public Status(String provider, String state, String checkedAt, String remoteConnection) {
            this(provider, state, checkedAt, remoteConnection, null);
        }
    }
    Status check(CuratorModel.Provider provider);
    Status checkCommand(CuratorModel.Provider provider, String executable);
}
