package dev.dougle.investigation.adapter;

import dev.dougle.investigation.application.PlanModel;
import dev.dougle.knowledge.application.CuratorModel;
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
