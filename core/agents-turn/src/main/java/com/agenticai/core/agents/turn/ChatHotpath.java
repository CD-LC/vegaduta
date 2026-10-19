package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.security.TtlCache;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * The flagged, default-OFF pre-first-token trimmings of the chat path
 * (docs/plans/CORE-HOTPATH-FLAGS.md). One holder so {@code AgentChatService} needs a single setter;
 * every method degrades to exactly today's call on the collaborator when its flag is off ({@link
 * #OFF} is what a hand-constructed service gets). Caches are tenant-keyed ({@link TtlCache#key})
 * and are evicted by the write-path listeners in {@code tenancy} and {@code providers} ({@link
 * HotpathCaches}); across pods the staleness bound is the TTL.
 */
public final class ChatHotpath {

    static final ChatHotpath OFF = new ChatHotpath(Duration.ZERO, Duration.ZERO, false, false);

    private final TtlCache<TenantPlan> plans;
    private final TtlCache<Boolean> pools;
    private final TtlCache<String> keys;
    private final boolean rlBatchSelect;
    private final boolean asyncAttachmentFetch;

    public ChatHotpath(
            Duration planTtl,
            Duration credentialTtl,
            boolean rlBatchSelect,
            boolean asyncAttachmentFetch) {
        this.plans = new TtlCache<>(HotpathCaches.TENANT_PLAN, planTtl, 10_000);
        this.pools = new TtlCache<>(HotpathCaches.TENANT_POOL, planTtl, 10_000);
        this.keys = new TtlCache<>(HotpathCaches.PROVIDER_KEY, credentialTtl, 2_000);
        this.rlBatchSelect = rlBatchSelect;
        this.asyncAttachmentFetch = asyncAttachmentFetch;
    }

    /**
     * (i) The tenant's effective plan; cached per tenant for the plan TTL. Never used for the kill
     * switch.
     */
    TenantPlan plan(TenantLimits limits, String tenantId) {
        if (!plans.isEnabled() || tenantId == null) {
            return limits.plan(tenantId);
        }
        return plans.get(TtlCache.key(tenantId, "plan"), () -> limits.plan(tenantId));
    }

    /**
     * (i) {@link TenantLimits#usesPlatformPool}: FREE answers with no DB read today and stays
     * uncached.
     */
    boolean usesPlatformPool(TenantLimits limits, String tenantId, TenantPlan plan) {
        if (!pools.isEnabled() || tenantId == null || plan == TenantPlan.FREE) {
            return limits.usesPlatformPool(tenantId, plan);
        }
        return pools.get(
                TtlCache.key(tenantId, String.valueOf(plan)),
                () -> limits.usesPlatformPool(tenantId, plan));
    }

    /**
     * (iii) The decrypted BYOK key for this tenant+provider, held in memory only for the credential
     * TTL and evicted on every credential write. Sarvam has its own resolver and is never cached.
     */
    String resolveActiveKey(
            ProviderCredentialResolver resolver, String tenantId, ProviderName provider) {
        if (!keys.isEnabled()
                || tenantId == null
                || provider == null
                || provider == ProviderName.SARVAM) {
            return resolver.resolveActiveKey(provider);
        }
        return keys.get(
                TtlCache.key(tenantId, provider.name()), () -> resolver.resolveActiveKey(provider));
    }

    /**
     * (ii) Arm selector for one Playground turn; batches the per-dimension reads only when the flag
     * is on.
     */
    RlArmPrefetch rlArms(boolean autoTuned) {
        return rlArms(autoTuned, null);
    }

    /**
     * As above, handing the turn's already-resolved plan to the policy port so it is not read per
     * dimension.
     */
    RlArmPrefetch rlArms(boolean autoTuned, TenantPlan knownPlan) {
        EnumSet<PolicyDimension> dims =
                EnumSet.of(
                        PolicyDimension.MODEL_ROUTE,
                        PolicyDimension.TOOL_SUBSET,
                        PolicyDimension.PROMPT_VARIANT);
        if (autoTuned) {
            dims.add(PolicyDimension.GEN_PARAMS);
            dims.add(PolicyDimension.REASONING_DEPTH);
        }
        return new RlArmPrefetch(rlBatchSelect ? dims : null, knownPlan);
    }

    /**
     * (iv) Starts (or, flag off, eagerly runs) attachment resolution; see {@link
     * PendingAttachments}.
     */
    PendingAttachments startAttachments(
            ChatAttachments resolver,
            Executor executor,
            Agent agent,
            UUID agentId,
            String attachmentKey,
            String attachmentMimeType,
            List<ChatInlineAttachment> inlineAttachments) {
        return PendingAttachments.start(
                asyncAttachmentFetch,
                resolver,
                executor,
                agent,
                agentId,
                attachmentKey,
                attachmentMimeType,
                inlineAttachments);
    }

    long hits(String cache) {
        return cacheOf(cache).hits();
    }

    long misses(String cache) {
        return cacheOf(cache).misses();
    }

    private TtlCache<?> cacheOf(String cache) {
        return switch (cache) {
            case "plan" -> plans;
            case "pool" -> pools;
            default -> keys;
        };
    }
}
