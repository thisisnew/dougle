package dev.dougle.knowledge.application;

public interface CliDiagnostics {
    record Status(String provider, String status, String version, String requestedModel,
                  String authentication, String remoteConnection) {}
    Status probe(CuratorModel.Provider provider);
}
