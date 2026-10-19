package com.agenticai.core.knowledge.rag;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Request/response shapes of the RAG-quality endpoints (collection retrieval settings and the retrieval eval). */
public final class RagDtos {

    private RagDtos() {
    }

    /**
     * What the console shows for a collection's retrieval-quality settings.
     *
     * @param parentChildAvailable   {@code app.knowledge.parent-child.enabled} - false means the toggle cannot be turned on here
     * @param parentChildEnabled     this collection opted in (affects documents ingested from now on only)
     * @param childTokens            child chunk size in words (what is embedded)
     * @param parentTokens           parent passage size in words (what the model receives)
     * @param parentChunkCount       stored parent passages in this collection
     * @param fusionEnabled          deployment-wide: configured RRF k / weights / MMR are applied to every search
     * @param queryRewriteMode       deployment-wide: "off", "multi" or "hyde"
     * @param schemaReady            false when the tenant schema has not applied migration V432
     */
    public record RagSettingsResponse(
        boolean parentChildAvailable,
        boolean parentChildEnabled,
        int childTokens,
        int parentTokens,
        long parentChunkCount,
        boolean fusionEnabled,
        String queryRewriteMode,
        boolean schemaReady
    ) {}

    /** Null fields are left unchanged. */
    public record RagSettingsUpdateRequest(Boolean parentChildEnabled) {}

    /**
     * One eval question. A hit counts as relevant if its {@code documentId} is in
     * {@code expectedDocumentIds} OR its text contains any of {@code expectedSubstrings}
     * (case-insensitive, whitespace-normalised). At least one of the two lists must be non-empty.
     */
    public record RagEvalQuestion(String question, Set<UUID> expectedDocumentIds, List<String> expectedSubstrings) {}

    /**
     * One retrieval configuration to measure. {@code preset} is one of {@code baseline} (today's fixed
     * pipeline, whatever the deployment flags say), {@code fusion} (configured RRF k/weights/MMR),
     * {@code parent-child} (baseline ranking, parent passages returned), {@code fusion+parent-child},
     * {@code rewrite-multi}, {@code rewrite-hyde}. The optional fields override the preset's fusion
     * parameters; null keeps the deployment's configured value.
     */
    public record RagEvalConfig(
        String name,
        String preset,
        Integer rrfK,
        Double vectorWeight,
        Double keywordWeight,
        Double mmrLambda
    ) {}

    public record RagEvalRequest(List<RagEvalQuestion> questions, List<RagEvalConfig> configs, Integer k) {}

    /** Interval estimate for a proportion (Wilson score, 95%). */
    public record Interval(double low, double high) {}

    /** Per-question result for one config (capped in the response). */
    public record RagEvalQuestionResult(
        String question, boolean hit, Integer firstRelevantRank, double recall, int returned, int returnedChars
    ) {}

    /**
     * Aggregate metrics of one config over all questions. {@code hitRate} = share of questions with at
     * least one relevant hit in the top k; {@code recallAtK} = mean share of expected targets found;
     * {@code mrr} = mean reciprocal rank of the first relevant hit (0 when none). {@code avgReturnedChars}
     * is the mean size of the returned context - read it next to the quality numbers, because a config
     * that returns larger passages (parent-child) can raise substring recall simply by returning more text.
     */
    public record RagEvalConfigResult(
        String name,
        String preset,
        int questions,
        int k,
        double hitRate,
        Interval hitRateCi,
        double recallAtK,
        double mrr,
        double avgReturnedChars,
        double avgLatencyMs,
        int failedQuestions,
        List<RagEvalQuestionResult> perQuestion
    ) {}

    public record RagEvalResponse(
        UUID collectionId,
        int k,
        int questions,
        long childChunksInCollection,
        long parentChunksInCollection,
        boolean truncated,
        List<String> notes,
        List<RagEvalConfigResult> results
    ) {}
}
