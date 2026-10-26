package dev.knowledgeforge.investigation.application;

public interface PlanModel {
    String generate(String provider, String prompt, String requestedModel);
}
