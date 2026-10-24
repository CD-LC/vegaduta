package com.agenticai.core.agents.settings;

import com.agenticai.core.agents.auxiliary.AuxiliaryModelProperties;
import com.agenticai.core.agents.memory.MemoryLimitsProperties;
import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.agents.turn.ChatCacheProperties;
import com.agenticai.core.agents.turn.ChatFinishProperties;
import com.agenticai.core.agents.turn.ContinuationProperties;
import com.agenticai.core.agents.turn.FollowUpProperties;
import com.agenticai.core.agents.turn.quality.RoutingSimpleProperties;
import com.agenticai.core.approval.ApprovalExpiryProperties;
import com.agenticai.core.approval.ApprovalSettingDefinitions;
import com.agenticai.core.chat.LlmRetryProperties;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.GuardrailSettingDefinitions;
import com.agenticai.core.guardrails.GuardrailTuningProperties;
import com.agenticai.core.settings.SettingDefinition;
import com.agenticai.core.settings.SettingRisk;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingType;
import com.agenticai.core.settings.SettingUnit;
import com.agenticai.core.settings.SettingsContributor;
import java.util.List;

/**
 * The curated behaviour settings whose property classes live at or below the agents-turn module: approvals, the chat
 * turn (finish, retry, cache, follow-up, continuation, tool-loop), simple-message routing, memory recall, the
 * guardrail strictness knobs and the auxiliary-model switch. Each entry reads its platform value from the SAME
 * property object the runtime uses (so the catalog default can never drift from the yml); the table of which use site
 * consults {@code SettingsResolver} for which key is in {@code docs/settings.md}. The runtime limits are in
 * {@code RuntimeLimitsSettings}, delegation in the interop module and the judge in the quality module.
 *
 * <p>Security entries ({@code guardrails.*}) can only be made stricter than the platform value, by a tenant admin,
 * with a typed reason.
 */
public class BehaviourSettingsCatalog implements SettingsContributor {

    private static final SettingScope[] TENANT_AND_AGENT = {SettingScope.TENANT, SettingScope.AGENT};
    private static final SettingScope[] TENANT_ONLY = {SettingScope.TENANT};

    private final ApprovalContinuationProperties approvalContinuation;
    private final ApprovalExpiryProperties approvalExpiry;
    private final ChatFinishProperties finish;
    private final LlmRetryProperties retry;
    private final ChatCacheProperties cache;
    private final FollowUpProperties followUp;
    private final ContinuationProperties continuation;
    private final ToolLoopProperties toolLoop;
    private final RoutingSimpleProperties routing;
    private final MemoryLimitsProperties memory;
    private final GuardrailTuningProperties guardrails;
    private final AuxiliaryModelProperties auxiliary;

    public BehaviourSettingsCatalog(
            ApprovalContinuationProperties approvalContinuation,
            ApprovalExpiryProperties approvalExpiry,
            ChatFinishProperties finish,
            LlmRetryProperties retry,
            ChatCacheProperties cache,
            FollowUpProperties followUp,
            ContinuationProperties continuation,
            ToolLoopProperties toolLoop,
            RoutingSimpleProperties routing,
            MemoryLimitsProperties memory,
            GuardrailTuningProperties guardrails,
            AuxiliaryModelProperties auxiliary) {
        this.approvalContinuation = approvalContinuation;
        this.approvalExpiry = approvalExpiry;
        this.finish = finish;
        this.retry = retry;
        this.cache = cache;
        this.followUp = followUp;
        this.continuation = continuation;
        this.toolLoop = toolLoop;
        this.routing = routing;
        this.memory = memory;
        this.guardrails = guardrails;
        this.auxiliary = auxiliary;
    }

    private static SettingDefinition.Builder bool(String key, String group, SettingRisk risk, String property, SettingScope... scopes) {
        return SettingDefinition.builder(key, group, SettingType.BOOL).risk(risk).scopes(scopes).property(property).docsAnchor("behaviour-settings");
    }

    private static SettingDefinition.Builder integer(
            String key, String group, SettingUnit unit, double min, double max, SettingRisk risk, String property, SettingScope... scopes) {
        return SettingDefinition.builder(key, group, SettingType.INT)
                .unit(unit).range(min, max).risk(risk).scopes(scopes).property(property).docsAnchor("behaviour-settings");
    }

    private static SettingDefinition.Builder seconds(
            String key, String group, double min, double max, SettingRisk risk, String property, SettingScope... scopes) {
        return SettingDefinition.builder(key, group, SettingType.DURATION)
                .unit(SettingUnit.S).range(min, max).risk(risk).scopes(scopes).property(property).docsAnchor("behaviour-settings");
    }

    @Override
    public List<SettingDefinition> definitions() {
        List<SettingDefinition> own = List.of(
                // ---- approvals
                bool("approvals.continuation.enabled", "approvals", SettingRisk.MEDIUM, "app.approvals.continuation.enabled", TENANT_AND_AGENT)
                        .platform(approvalContinuation::isEnabled).build(),
                bool("approvals.continuation.jobsContinueAfterDenial", "approvals", SettingRisk.MEDIUM,
                                "app.approvals.continuation.jobs-continue-after-denial", TENANT_AND_AGENT)
                        .platform(approvalContinuation::isJobsContinueAfterDenial).build(),
                ApprovalSettingDefinitions.expiryTtl(approvalExpiry),

                // ---- chat turn
                bool("chat.finish.enabled", "chat", SettingRisk.LOW, "app.chat.finish.enabled", TENANT_AND_AGENT)
                        .platform(finish::isEnabled).build(),
                integer("chat.finish.maxContinuations", "chat", SettingUnit.COUNT, 0, 10, SettingRisk.LOW,
                                "app.chat.finish.max-continuations", TENANT_AND_AGENT)
                        .platform(finish::getMaxContinuations).build(),
                integer("chat.finish.maxToolCallRetries", "chat", SettingUnit.COUNT, 0, 5, SettingRisk.LOW,
                                "app.chat.finish.max-tool-call-retries", TENANT_AND_AGENT)
                        .platform(finish::getMaxToolCallRetries).build(),
                integer("chat.retry.maxAttempts", "chat", SettingUnit.COUNT, 1, 6, SettingRisk.LOW, "app.llm.retry.max-attempts", TENANT_ONLY)
                        .platform(retry::getMaxAttempts).build(),
                bool("chat.cache.eligibleWithTools", "chat", SettingRisk.MEDIUM, "app.chat.cache.eligible-with-tools", TENANT_AND_AGENT)
                        .platform(cache::isEligibleWithTools).build(),
                integer("chat.cache.contextTurns", "chat", SettingUnit.COUNT, 1, 10, SettingRisk.LOW, "app.chat.cache.context-turns", TENANT_AND_AGENT)
                        .platform(cache::getContextTurns).build(),
                bool("chat.followUp.stickyToolsEnabled", "chat", SettingRisk.LOW, "app.chat.follow-up.sticky-tools-enabled", TENANT_AND_AGENT)
                        .platform(followUp::isStickyToolsEnabled).build(),
                seconds("chat.followUp.stickyToolsWindow", "chat", 60, 7 * 86_400, SettingRisk.LOW, "app.chat.follow-up.sticky-tools-window", TENANT_AND_AGENT)
                        .platform(followUp::getStickyToolsWindow).build(),
                bool("chat.continuation.heuristicEnabled", "chat", SettingRisk.LOW, "app.chat.continuation.heuristic-enabled", TENANT_AND_AGENT)
                        .platform(continuation::isHeuristicEnabled).build(),
                bool("chat.toolLoop.resultClearing.enabled", "chat", SettingRisk.LOW, "app.chat.tools.result-clearing.enabled", TENANT_AND_AGENT)
                        .platform(() -> toolLoop.getResultClearing().isEnabled()).build(),
                integer("chat.toolLoop.resultClearing.thresholdChars", "chat", SettingUnit.CHARS, 500, 200_000, SettingRisk.LOW,
                                "app.chat.tools.result-clearing.threshold-chars", TENANT_AND_AGENT)
                        .platform(() -> toolLoop.getResultClearing().getThresholdChars()).build(),

                // ---- routing
                integer("routing.simple.maxChars", "routing", SettingUnit.CHARS, 0, 2_000, SettingRisk.LOW, "app.routing.simple.max-chars", TENANT_AND_AGENT)
                        .platform(routing::getMaxChars).build(),
                integer("routing.simple.maxClauses", "routing", SettingUnit.COUNT, 1, 10, SettingRisk.LOW, "app.routing.simple.max-clauses", TENANT_AND_AGENT)
                        .platform(routing::getMaxClauses).build(),

                // ---- memory
                integer("memory.recall.agentTopK", "memory", SettingUnit.COUNT, 1, 50, SettingRisk.LOW, "app.memory.limits.agent-recall-top-k", TENANT_AND_AGENT)
                        .platform(memory::getAgentRecallTopK).build(),
                integer("memory.recall.tenantTopK", "memory", SettingUnit.COUNT, 1, 50, SettingRisk.LOW, "app.memory.limits.tenant-recall-top-k", TENANT_AND_AGENT)
                        .platform(memory::getTenantRecallTopK).build(),
                integer("memory.agent.maxMemories", "memory", SettingUnit.COUNT, 1, 5_000, SettingRisk.MEDIUM, "app.memory.limits.agent-max-memories", TENANT_AND_AGENT)
                        .platform(memory::getAgentMaxMemories).build(),

                // ---- auxiliary calls
                bool("aux.useAgentModel", "aux", SettingRisk.MEDIUM, "app.auxiliary-model.use-agent-model", TENANT_ONLY)
                        .platform(auxiliary::isUseAgentModel).build()
        );
        List<SettingDefinition> all = new java.util.ArrayList<>(own);
        // ---- guardrails (security: stricter than the platform only, tenant admin, typed reason): declared next to the
        // code that reads them, in the guardrails module (GuardrailSettings)
        all.addAll(GuardrailSettingDefinitions.all(guardrails));
        return List.copyOf(all);
    }
}
