package com.agenticai.core.agents.core;

import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TtlCache;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Per-pod, tenant-keyed cache of {@link AgentProjection}, the cache-aside read path behind the
 * {@link AgentService} projection getters.
 *
 * <p>Consistency contract: a write to an agent on THIS pod evicts its entry at flush and again
 * after commit ({@link AgentProjectionEviction}), so the next turn on this pod sees it. Another pod
 * sees the change after at most {@code app.chat.hotpath.agent-projection-ttl} (default 3 s, capped
 * at 30 s). Authorization never reads this cache. Set the TTL to {@code 0} for today's
 * read-the-row-every-time behaviour.
 */
@Component
class AgentProjectionCache {

    static final Duration MAX_TTL = Duration.ofSeconds(30);
    private static final int MAX_ENTRIES = 20_000;

    private final AgentProjectionLoader loader;
    private final TtlCache<AgentProjection> cache;

    AgentProjectionCache(
            AgentProjectionLoader loader,
            ObjectProvider<MeterRegistry> registry,
            @Value("${app.chat.hotpath.agent-projection-ttl:3s}") Duration ttl) {
        this.loader = loader;
        this.cache = new TtlCache<>(HotpathCaches.AGENT_PROJECTION, clamp(ttl), MAX_ENTRIES);
        MeterRegistry meters = registry.getIfAvailable();
        if (meters != null) {
            FunctionCounter.builder("agentic.chat.hotpath.cache", cache, TtlCache::hits)
                    .tag("cache", "agent")
                    .tag("result", "hit")
                    .register(meters);
            FunctionCounter.builder("agentic.chat.hotpath.cache", cache, TtlCache::misses)
                    .tag("cache", "agent")
                    .tag("result", "miss")
                    .register(meters);
        }
    }

    static Duration clamp(Duration ttl) {
        if (ttl == null || ttl.isNegative()) {
            return Duration.ZERO;
        }
        return ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl;
    }

    /** The agent's projection for the current tenant; loads (one transaction) on a miss. */
    AgentProjection get(UUID agentId) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (!cache.isEnabled() || tenantId == null) {
            return loader.load(agentId);
        }
        return cache.get(TtlCache.key(tenantId, agentId.toString()), () -> loader.load(agentId));
    }
}
