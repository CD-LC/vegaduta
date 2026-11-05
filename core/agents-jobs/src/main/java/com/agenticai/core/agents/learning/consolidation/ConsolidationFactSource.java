package com.agenticai.core.agents.learning.consolidation;

import com.agenticai.core.agents.memory.MemoryTrust;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The read-only surface the nightly consolidation sees (phase 1 design 03, section 5 "no
 * auto-approve"). It deliberately exposes only two questions - who has facts, and what those facts
 * are - so the sweep structurally cannot approve, promote, rewrite or delete anything: the
 * {@code AgentMemoryService} / {@code TenantMemoryService} methods that do that
 * ({@code approve}, {@code forget*}, {@code update*}) are simply not on this type, and the sweep
 * never holds the services themselves.
 */
public interface ConsolidationFactSource {

    /** Up to {@code limit} subject keys with facts in the current tenant, for background sweeps. */
    List<String> subjects(int limit);

    /** Every remembered fact of one subject in the current tenant, both tiers. */
    List<Fact> facts(String subjectKey);

    /**
     * One remembered fact as the consolidation planner needs it.
     *
     * @param scope {@code "agent"} (private to {@code agentId}) or {@code "shared"} (tenant-wide);
     *     consolidation only ever operates inside one scope, so merging can never change who can
     *     recall a fact
     * @param trust the stored trust; anything not TRUSTED is treated as QUARANTINED downstream
     * @param importance the stored importance prior (0..1, neutral 0.5; V684)
     * @param pinned a person asked to keep it: never planned for removal
     * @param personAuthored typed by a person rather than learned by the agent
     */
    record Fact(
            String scope,
            UUID id,
            UUID agentId,
            String content,
            MemoryTrust trust,
            int useCount,
            Instant createdAt,
            Instant lastUsedAt,
            double importance,
            boolean pinned,
            boolean personAuthored) {

        /** A fact with no recorded signals: neutral importance, not pinned, not person-authored. */
        public Fact(
                String scope,
                UUID id,
                UUID agentId,
                String content,
                MemoryTrust trust,
                int useCount,
                Instant createdAt,
                Instant lastUsedAt) {
            this(scope, id, agentId, content, trust, useCount, createdAt, lastUsedAt, 0.5, false, false);
        }

        public static final String AGENT = "agent";
        public static final String SHARED = "shared";

        /** Facts only ever merge or drop within one tier: the same agent's, or the shared ones. */
        public String tier() {
            return AGENT.equals(scope) ? AGENT + ":" + agentId : SHARED;
        }
    }
}
