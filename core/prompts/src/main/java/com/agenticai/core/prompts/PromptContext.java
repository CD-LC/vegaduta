package com.agenticai.core.prompts;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What a lookup is for: which tenant and agent decide the overrides, which locale, and the values
 * of the key's placeholders.
 *
 * <p>The tenant is the one on the calling thread ({@code TenantContext}); the stored overrides live
 * in that tenant's schema, so a resolver can only ever serve the current tenant's rows.
 * {@code tenantId} is there to make a mismatch visible (a context built for another tenant gets
 * the platform and default text only, and a warning), never to select a tenant.
 *
 * @param tenantId expected tenant, or null for "the current one"
 * @param agentId the agent the text is for, or null (no agent-scope override applies)
 * @param locale e.g. {@code en}; null = the configured default locale
 * @param variables values for the key's declared placeholders; never null
 */
public record PromptContext(String tenantId, UUID agentId, String locale, Map<String, String> variables) {

    private static final PromptContext NONE = new PromptContext(null, null, null, Map.of());

    public PromptContext {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    /** No agent, default locale, no variables. */
    public static PromptContext none() {
        return NONE;
    }

    public static PromptContext forAgent(UUID agentId) {
        return new PromptContext(null, agentId, null, Map.of());
    }

    public PromptContext withAgent(UUID agent) {
        return new PromptContext(tenantId, agent, locale, variables);
    }

    public PromptContext withTenant(String tenant) {
        return new PromptContext(tenant, agentId, locale, variables);
    }

    public PromptContext withLocale(String newLocale) {
        return new PromptContext(tenantId, agentId, newLocale, variables);
    }

    public PromptContext withVariable(String name, String value) {
        Map<String, String> next = new HashMap<>(variables);
        next.put(name, value);
        return new PromptContext(tenantId, agentId, locale, next);
    }

    public PromptContext withVariables(Map<String, String> more) {
        Map<String, String> next = new HashMap<>(variables);
        next.putAll(more);
        return new PromptContext(tenantId, agentId, locale, next);
    }
}
