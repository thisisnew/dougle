package dev.knowledgeforge.investigation.adapter;

import dev.knowledgeforge.investigation.application.PlanModel;
import dev.knowledgeforge.knowledge.application.CuratorModel;
import org.springframework.stereotype.Component;

@Component
public final class CliPlanModel implements PlanModel {
    private final CuratorModel transport;

    public CliPlanModel(CuratorModel transport) { this.transport = transport; }

    @Override
    public String generate(String provider, String prompt, String requestedModel) {
        return transport.generatePlan(CuratorModel.Provider.valueOf(provider), prompt, requestedModel);
    }
}
