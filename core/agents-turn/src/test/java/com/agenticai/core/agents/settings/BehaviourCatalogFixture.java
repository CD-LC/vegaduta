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
import com.agenticai.core.chat.LlmRetryProperties;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.GuardrailTuningProperties;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.settings.DefaultSettingsResolver;
import com.agenticai.core.settings.InMemorySettingsStore;
import com.agenticai.core.settings.SettingOverride;
import com.agenticai.core.settings.SettingScope;
import com.agenticai.core.settings.SettingsProperties;
import com.agenticai.core.settings.SettingsRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The real behaviour catalog ({@link BehaviourSettingsCatalog}) over the same property objects a test hands to the
 * runtime, an in-memory store partitioned by tenant like the real one, and the real resolver (no cache, so a write is seen
 * at once). A test sets an override the way an admin's write would leave it, and watches the runtime change.
 */
public final class BehaviourCatalogFixture {

    public final ApprovalContinuationProperties approvalContinuation = new ApprovalContinuationProperties();
    public final ApprovalExpiryProperties approvalExpiry = new ApprovalExpiryProperties();
    public final ChatFinishProperties finish = new ChatFinishProperties();
    public final LlmRetryProperties retry = new LlmRetryProperties();
    public final ChatCacheProperties cache = new ChatCacheProperties();
    public final FollowUpProperties followUp = new FollowUpProperties();
    public final ContinuationProperties continuation = new ContinuationProperties();
    public final ToolLoopProperties toolLoop = new ToolLoopProperties();
    public final RoutingSimpleProperties routing = new RoutingSimpleProperties();
    public final MemoryLimitsProperties memory = new MemoryLimitsProperties();
    public final GuardrailTuningProperties guardrails = new GuardrailTuningProperties();
    public final AuxiliaryModelProperties auxiliary = new AuxiliaryModelProperties();
    public final InMemorySettingsStore store = new InMemorySettingsStore();
    private DefaultSettingsResolver resolver;

    public BehaviourSettingsCatalog catalog() {
        return new BehaviourSettingsCatalog(
                approvalContinuation, approvalExpiry, finish, retry, cache, followUp, continuation, toolLoop, routing,
                memory, guardrails, auxiliary);
    }

    /** The resolver over the properties as they are NOW: call after tuning the property objects. */
    public synchronized DefaultSettingsResolver resolver() {
        if (resolver == null) {
            SettingsProperties noCache = new SettingsProperties();
            noCache.setCacheTtl(Duration.ZERO);
            resolver =
                    new DefaultSettingsResolver(
                            new SettingsRegistry(List.of(catalog())), store, List.of(), noCache, null);
        }
        return resolver;
    }

    /** A tenant-scope override, as the tenant admin's write leaves it. */
    public BehaviourCatalogFixture tenant(String tenantId, String key, Object value) {
        inTenant(
                tenantId,
                () ->
                        store.saveCurrent(
                                new SettingOverride(SettingScope.TENANT, "", key, value, 1, "admin", Instant.now())));
        return this;
    }

    /** An agent-scope override. */
    public BehaviourCatalogFixture agent(String tenantId, UUID agentId, String key, Object value) {
        inTenant(
                tenantId,
                () ->
                        store.saveCurrent(
                                new SettingOverride(
                                        SettingScope.AGENT, agentId.toString(), key, value, 1, "editor", Instant.now())));
        return this;
    }

    public static void inTenant(String tenantId, Runnable work) {
        TenantContext previous = TenantContext.current().orElse(null);
        TenantContext.set(TenantContext.of(tenantId, "u", Set.of()));
        try {
            work.run();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }
}
