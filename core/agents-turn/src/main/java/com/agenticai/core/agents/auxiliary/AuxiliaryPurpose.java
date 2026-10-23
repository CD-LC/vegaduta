package com.agenticai.core.agents.auxiliary;

/**
 * The background model calls that are not the agent's own answer: each is configured on its own under {@code
 * app.auxiliary-model.<key>} (see {@link AuxiliaryModelProperties}).
 */
public enum AuxiliaryPurpose {
    /** Folds the older turns of a long prompt into one summary (context compaction). */
    COMPACTION("compaction", "compaction"),
    /** Folds turns that left the replay window into the session's rolling summary. */
    SESSION_SUMMARY("session-summary", "session-summary"),
    /** The "Ask AI" assist that drafts form text and the studio's one-off completions. */
    ASSIST("assist", "assist"),
    /** Judges and verifiers that grade or check another model's answer. */
    JUDGE("judge", "eval-judge");

    private final String configKey;
    private final String featureKey;

    AuxiliaryPurpose(String configKey, String featureKey) {
        this.configKey = configKey;
        this.featureKey = featureKey;
    }

    /** The segment under {@code app.auxiliary-model}. */
    public String configKey() {
        return configKey;
    }

    /**
     * The key of the tenant's "AI Model Control" preference for this purpose (the gateway's {@code featureKey}). A
     * purpose the tenant page has no row for falls back to the tenant's default row inside the gateway.
     */
    public String featureKey() {
        return featureKey;
    }
}
