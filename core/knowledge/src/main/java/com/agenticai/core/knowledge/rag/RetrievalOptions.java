package com.agenticai.core.knowledge.rag;

import java.util.Locale;

/**
 * The optional retrieval-quality stages a single search runs, beyond today's vector + keyword + RRF
 * (+ cross-encoder when {@code app.knowledge.rerank.enabled}) pipeline. A {@code null} options value
 * handed to {@code KnowledgeSearchService#search} means "run exactly the legacy path"; an options
 * value for which {@link #isLegacy()} is true is treated the same way.
 *
 * <p>When {@code fusion} is false the fusion parameters are forced back to the legacy values
 * (k = 60, both weights 1.0, no MMR) so a caller that only wants query rewriting or parent expansion
 * still gets today's exact fusion maths.
 *
 * @param fusion         use the configured RRF k / weights / MMR instead of the fixed legacy fusion
 * @param rrfK           RRF constant (legacy 60)
 * @param vectorWeight   weight of every vector list (0 skips the vector leg entirely)
 * @param keywordWeight  weight of every keyword list (0 skips the keyword leg entirely)
 * @param mmrLambda      MMR relevance/diversity trade-off in [0, 1]; null = no MMR
 * @param rewriteMode    query expansion mode
 * @param maxVariants    cap on rewritten variants (multi mode); HyDE always adds one passage
 * @param expandParents  return the parent passage of parent-child hits, de-duplicated by parent
 */
public record RetrievalOptions(
    boolean fusion,
    int rrfK,
    double vectorWeight,
    double keywordWeight,
    Double mmrLambda,
    RewriteMode rewriteMode,
    int maxVariants,
    boolean expandParents
) {

    /** The fusion constant and weights today's fixed pipeline uses. */
    public static final int LEGACY_RRF_K = 60;

    public enum RewriteMode {
        OFF, MULTI, HYDE;

        /** Lenient parse for config/request strings; unknown or blank = OFF. */
        public static RewriteMode parse(String value) {
            if (value == null) {
                return OFF;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "multi", "multi-query", "multiquery" -> MULTI;
                case "hyde", "hyde-lite" -> HYDE;
                default -> OFF;
            };
        }
    }

    public RetrievalOptions {
        if (rewriteMode == null) {
            rewriteMode = RewriteMode.OFF;
        }
        if (!fusion) {
            rrfK = LEGACY_RRF_K;
            vectorWeight = 1.0;
            keywordWeight = 1.0;
            mmrLambda = null;
        }
        if (rrfK < 1 || rrfK > 10_000) {
            throw new IllegalArgumentException("fusion k must be between 1 and 10000 (got " + rrfK + ")");
        }
        if (!isFiniteNonNegative(vectorWeight) || !isFiniteNonNegative(keywordWeight) || vectorWeight + keywordWeight <= 0.0) {
            throw new IllegalArgumentException(
                "fusion weights must be finite, not negative, and not both zero (got vector " + vectorWeight
                    + ", keyword " + keywordWeight + ")"
            );
        }
        if (mmrLambda != null && (mmrLambda.isNaN() || mmrLambda < 0.0 || mmrLambda > 1.0)) {
            throw new IllegalArgumentException("mmrLambda must be within [0, 1] (got " + mmrLambda + ")");
        }
        if (maxVariants < 1) {
            maxVariants = 1;
        }
        if (maxVariants > QueryRewriter.MAX_VARIANTS_CAP) {
            maxVariants = QueryRewriter.MAX_VARIANTS_CAP;
        }
    }

    /** True when this value changes nothing relative to today's pipeline. */
    public boolean isLegacy() {
        return !fusion && rewriteMode == RewriteMode.OFF && !expandParents;
    }

    private static boolean isFiniteNonNegative(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && v >= 0.0;
    }
}
