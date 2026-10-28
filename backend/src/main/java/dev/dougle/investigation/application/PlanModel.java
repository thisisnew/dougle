package dev.dougle.investigation.application;

public interface PlanModel {
    String generate(String provider, String prompt, String requestedModel);
}
