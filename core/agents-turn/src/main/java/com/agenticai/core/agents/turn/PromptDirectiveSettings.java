package com.agenticai.core.agents.turn;

import com.agenticai.core.prompts.PromptContext;
import com.agenticai.core.prompts.PromptResolver;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The chat path's door to the prompt registry for the standing directives. A turn calls {@link
 * #forTurn} once; the answer reads through the registry's {@link PromptResolver}, which caches the
 * stored overrides in process (platform, tenant and agent scope; TTL {@code
 * app.prompts.cache.ttl}, evicted on every local write), so no turn waits on the database unless
 * the cache is cold. With no {@link PromptResolver} bean, or if a lookup fails, the answer is the
 * shipped default text: today's built-in prompt.
 */
@Component
public class PromptDirectiveSettings {

    private final ObjectProvider<PromptResolver> resolver;

    @Autowired
    public PromptDirectiveSettings(ObjectProvider<PromptResolver> resolver) {
        this.resolver = resolver;
    }

    /** No resolver bean: every turn gets the built-in prompt. The default of hand-built fixtures. */
    static PromptDirectiveSettings defaultsOnly() {
        return new PromptDirectiveSettings(null);
    }

    /** Test seam: a resolver given directly. */
    static PromptDirectiveSettings forTest(PromptResolver resolver) {
        return new PromptDirectiveSettings(
                new org.springframework.beans.factory.support.StaticListableBeanFactory(
                                java.util.Map.of("resolver", resolver))
                        .getBeanProvider(PromptResolver.class));
    }

    /** The current tenant's directives for a turn on {@code today} (UTC), no agent. Never throws. */
    public ResolvedDirectives forToday(LocalDate today) {
        return forTurn(today, null);
    }

    /**
     * The directives for {@code agentId} (null = no agent-scope override) on {@code today} (UTC) in
     * the current tenant. Never throws.
     */
    public ResolvedDirectives forTurn(LocalDate today, UUID agentId) {
        PromptResolver r = resolver == null ? null : resolver.getIfAvailable();
        if (r == null) {
            r = PromptResolver.defaultsOnly();
        }
        PromptContext ctx = PromptContext.none();
        if (agentId != null) {
            ctx = ctx.withAgent(agentId);
        }
        if (today != null) {
            ctx = ctx.withVariable("date", today.toString());
        }
        return new ResolvedDirectives(r, ctx);
    }
}
