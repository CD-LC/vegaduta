package com.agenticai.core.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.knowledge.search.*}: the retrieval-pool knobs of {@link KnowledgeSearchService} that were constants.
 * How many chunks a search returns is the caller's top-k (the agent's retrieval setting); these decide how many
 * candidates the vector and keyword scans fetch so fusion has real material, and the reciprocal-rank-fusion
 * constant of the default (non-fusion-pipeline) path. Defaults are the values the code always used.
 *
 * <p>The candidate pool is {@code min(pool-max, max(pool-min, top-k * pool-multiplier))}. A larger pool costs more
 * per search (and per reranker call) and starves less when collection filters discard candidates.
 */
@ConfigurationProperties(prefix = "app.knowledge.search")
public class KnowledgeSearchProperties {

    public static final int DEFAULT_RRF_K = 60;
    public static final int DEFAULT_CANDIDATE_POOL_MIN = 20;
    public static final int DEFAULT_CANDIDATE_POOL_MAX = 50;
    public static final int DEFAULT_CANDIDATE_POOL_MULTIPLIER = 4;

    /** Reciprocal rank fusion constant: score = sum of 1/(k + rank). Larger flattens the difference between ranks. */
    private int rrfK = DEFAULT_RRF_K;

    /** Smallest candidate pool, so a top-k of 1 still fuses real candidates. */
    private int candidatePoolMin = DEFAULT_CANDIDATE_POOL_MIN;

    /** Largest candidate pool, so a very large top-k does not blow up the reranker's per-call cost. */
    private int candidatePoolMax = DEFAULT_CANDIDATE_POOL_MAX;

    /** Candidates fetched per requested chunk. */
    private int candidatePoolMultiplier = DEFAULT_CANDIDATE_POOL_MULTIPLIER;

    /** The pool for a search asking {@code topK} chunks. */
    public int candidatePoolSize(int topK) {
        return candidatePoolSize(topK, candidatePoolMultiplier, candidatePoolMax);
    }

    /**
     * The pool for a search asking {@code topK} chunks with a multiplier and a ceiling other than the configured
     * ones (a workspace's settings-catalog override); the floor stays the configured {@code candidate-pool-min}.
     */
    public int candidatePoolSize(int topK, int multiplier, int max) {
        long wanted = (long) Math.max(topK, 0) * Math.max(multiplier, 1);
        long floored = Math.max(candidatePoolMin, wanted);
        return (int) Math.min(Math.max(max, 1), floored);
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = Math.max(1, rrfK);
    }

    public int getCandidatePoolMin() {
        return candidatePoolMin;
    }

    public void setCandidatePoolMin(int candidatePoolMin) {
        this.candidatePoolMin = Math.max(1, candidatePoolMin);
    }

    public int getCandidatePoolMax() {
        return candidatePoolMax;
    }

    public void setCandidatePoolMax(int candidatePoolMax) {
        this.candidatePoolMax = Math.max(1, candidatePoolMax);
    }

    public int getCandidatePoolMultiplier() {
        return candidatePoolMultiplier;
    }

    public void setCandidatePoolMultiplier(int candidatePoolMultiplier) {
        this.candidatePoolMultiplier = Math.max(1, candidatePoolMultiplier);
    }
}
