package com.agenticai.core.agents.core;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * An agent's response-cache opt-ins: exact-match and the separate, stronger semantic one. Embedded
 * in {@link Agent}; the columns keep their names. Both default to off.
 */
@Embeddable
public class CachingPolicy {

    // Master Roadmap 2026 item #18 (cost optimization: routing + semantic
    // caching, §G) - same "off by default, explicit per-agent opt-in" shape
    // as sarvamEnabled/shareMemoryWithTenant above. When true,
    // AgentChatService checks LlmResponseCacheService for an exact/near-exact
    // match of this turn's user message BEFORE calling the LLM at all, and
    // caches the reply afterward. Deliberately opt-in rather than "smart"
    // auto-detection of which agents are cacheable: identical questions
    // should get identical CACHED answers only for agents whose replies
    // don't depend on live/per-caller data (FAQ/informational agents) - a
    // tenant, not a heuristic, is the one who knows which of their agents
    // that's true for. See LlmResponseCacheService's javadoc for the full
    // reasoning, including why this ships as EXACT-match caching (GPTCache's
    // simplest, lowest-risk tier) rather than embedding-based semantic
    // similarity in this first pass. Migration V95 backfills every
    // pre-existing agent to false.
    @Column(name = "response_caching_enabled", nullable = false)
    private boolean responseCachingEnabled;

    // Semantic (embedding-similarity) response caching - a SEPARATE, stronger
    // opt-in than responseCachingEnabled above, never implied by it. Exact-match
    // caching only returns a stored answer for the literal same question;
    // semantic caching can return one for a DIFFERENT-but-similar question that
    // scores above a high similarity threshold - a real wrong-answer risk if
    // misapplied, so it is its own explicit flag (default false). Only effective
    // under the "knowledge" profile (needs the embedding sidecar + pgvector) and
    // only ever caches turns that used no tools. See SemanticResponseCacheService.
    // Migration V115 backfills every pre-existing agent to false.
    @Column(name = "semantic_response_caching_enabled", nullable = false)
    private boolean semanticResponseCachingEnabled;

    /** JPA, and the all-off starting state. */
    protected CachingPolicy() {}

    public static CachingPolicy off() {
        return new CachingPolicy();
    }

    public CachingPolicy withResponseCaching(boolean enabled) {
        CachingPolicy p = new CachingPolicy();
        p.responseCachingEnabled = enabled;
        p.semanticResponseCachingEnabled = semanticResponseCachingEnabled;
        return p;
    }

    public CachingPolicy withSemanticResponseCaching(boolean enabled) {
        CachingPolicy p = new CachingPolicy();
        p.responseCachingEnabled = responseCachingEnabled;
        p.semanticResponseCachingEnabled = enabled;
        return p;
    }

    public boolean isResponseCachingEnabled() {
        return responseCachingEnabled;
    }

    public boolean isSemanticResponseCachingEnabled() {
        return semanticResponseCachingEnabled;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CachingPolicy c
                && responseCachingEnabled == c.responseCachingEnabled
                && semanticResponseCachingEnabled == c.semanticResponseCachingEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(responseCachingEnabled, semanticResponseCachingEnabled);
    }
}
