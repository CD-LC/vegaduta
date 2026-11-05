package com.agenticai.core.agents.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.memory.supersession.*}: whether a corrected fact replaces the fact it corrects in the
 * legacy fact stores ({@code agent_memory}, {@code tenant_memory}). Off, the stores behave exactly
 * as before: every fact is a new row and every TRUSTED row is a recall candidate.
 *
 * <p>On, a new TRUSTED fact about the same person supersedes an existing TRUSTED fact when
 *
 * <ul>
 *   <li>it is the same text told again: the old row is re-confirmed (its {@code updated_at} moves),
 *       no second row is written; or
 *   <li>the writer named the fact it replaces ({@code remember_fact}'s optional {@code replaces},
 *       the text of a fact the agent saw in its recalled memory) and that text matches one existing
 *       fact exactly or, failing that, by embedding similarity of at least {@link
 *       #getReplaceMinSimilarity()}; or
 *   <li>its embedding is at least {@link #getMinSimilarity()} similar to the existing fact's.
 * </ul>
 *
 * A superseded row is kept (marked, never deleted): it stays in the person's list, export and
 * erasure and is only left out of recall. A QUARANTINED fact never supersedes anything until a
 * person approves it. A fact the person typed or pinned is replaced only by another person-typed
 * fact.
 */
@ConfigurationProperties(prefix = "app.memory.supersession")
public class MemorySupersessionProperties {

    /**
     * Safe starting point for sentence embeddings (the sidecar's models); calibrate per embedding
     * model.
     */
    public static final double DEFAULT_MIN_SIMILARITY = 0.85;

    public static final double DEFAULT_REPLACE_MIN_SIMILARITY = 0.60;

    private boolean enabled = true;

    private double minSimilarity = DEFAULT_MIN_SIMILARITY;

    private double replaceMinSimilarity = DEFAULT_REPLACE_MIN_SIMILARITY;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Cosine similarity at or above which a new fact supersedes an older one on similarity alone
     * (1.0 = identical).
     */
    public double getMinSimilarity() {
        return minSimilarity;
    }

    public void setMinSimilarity(double minSimilarity) {
        this.minSimilarity = Math.max(0.0, Math.min(1.0, minSimilarity));
    }

    /**
     * Cosine similarity at or above which the text named in {@code replaces} resolves to an
     * existing fact.
     */
    public double getReplaceMinSimilarity() {
        return replaceMinSimilarity;
    }

    public void setReplaceMinSimilarity(double replaceMinSimilarity) {
        this.replaceMinSimilarity = Math.max(0.0, Math.min(1.0, replaceMinSimilarity));
    }
}
