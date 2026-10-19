package com.agenticai.core.rl.policy;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code rl.policy.*} - seeding config for {@link PolicyStore}'s default arms.
 *
 * <p>Only the MODEL_ROUTE arm set is configurable here (a small operator-
 * curated model pool); the other dimensions seed from fixed, code-defined arm
 * sets (see {@link PolicyStore}). PROMPT_VARIANT arms are NOT seeded here -
 * they reference concrete prompt-version ids the orchestrator supplies later,
 * so a fresh PROMPT_VARIANT policy starts with only a DEFAULT arm.
 */
@ConfigurationProperties(prefix = "rl.policy")
public class RlPolicyProperties {

    /**
     * The candidate model keys a MODEL_ROUTE policy explores between. Each
     * becomes a PolicyArm with arm_key = the model string and
     * arm_config_json = {"model": "..."}. Empty by default so no MODEL_ROUTE
     * arms are seeded until an operator configures a real pool.
     *
     * <p><b>Keep it empty.</b> This is a BOOT-TIME seed and it is deliberately
     * not the primary way arms are added - anything set here is applied
     * platform-wide, to every tenant's every agent, with no knowledge of which
     * providers that tenant actually holds credentials for. A model route is a
     * per-tenant decision about live traffic, so the real path is {@code POST
     * /api/rl/agents/{agentId}/policies/MODEL_ROUTE/arms}, which validates the
     * model against that agent's own provider before persisting it (see {@link
     * PolicyArmAdminService} and {@code RlArmConfigValidator}). Setting a
     * default here would bypass that check entirely - which is exactly why the
     * empty default was left alone when arm management was built.
     */
    private List<String> modelRouteArms = new ArrayList<>();

    public List<String> getModelRouteArms() {
        return modelRouteArms;
    }

    public void setModelRouteArms(List<String> modelRouteArms) {
        this.modelRouteArms = modelRouteArms;
    }
}
