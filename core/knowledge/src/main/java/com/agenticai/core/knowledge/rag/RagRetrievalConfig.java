package com.agenticai.core.knowledge.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The deployment-level switches of the RAG-quality features (all default OFF, so today's retrieval is
 * byte-identical until an operator opts in):
 *
 * <pre>
 * app.knowledge.parent-child.{enabled, child-tokens, parent-tokens}
 * app.knowledge.fusion.{enabled, k, vector-weight, keyword-weight, mmr-lambda}
 * app.knowledge.query-rewrite.{enabled, mode, max-variants, timeout-ms}
 * </pre>
 *
 * Config is validated/clamped once here so a typo can never throw per search: a bad value logs a
 * warning and falls back to the legacy-equivalent default.
 */
@Component
@Profile("knowledge")
public class RagRetrievalConfig {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalConfig.class);

    private final boolean parentChildEnabled;
    private final ParentChildChunker.Settings parentChildSettings;
    private final RetrievalOptions defaultOptions;
    private final RetrievalOptions fusionPreset;

    public RagRetrievalConfig(
        @Value("${app.knowledge.parent-child.enabled:false}") boolean parentChildEnabled,
        @Value("${app.knowledge.parent-child.child-tokens:200}") int childTokens,
        @Value("${app.knowledge.parent-child.parent-tokens:1000}") int parentTokens,
        @Value("${app.knowledge.fusion.enabled:false}") boolean fusionEnabled,
        @Value("${app.knowledge.fusion.k:60}") int fusionK,
        @Value("${app.knowledge.fusion.vector-weight:1.0}") double vectorWeight,
        @Value("${app.knowledge.fusion.keyword-weight:1.0}") double keywordWeight,
        @Value("${app.knowledge.fusion.mmr-lambda:}") String mmrLambda,
        @Value("${app.knowledge.query-rewrite.enabled:false}") boolean rewriteEnabled,
        @Value("${app.knowledge.query-rewrite.mode:multi}") String rewriteMode,
        @Value("${app.knowledge.query-rewrite.max-variants:3}") int maxVariants
    ) {
        this.parentChildEnabled = parentChildEnabled;
        this.parentChildSettings = ParentChildChunker.Settings.clamped(childTokens, parentTokens);
        if (parentChildEnabled && (parentChildSettings.childWords() != childTokens || parentChildSettings.parentWords() != parentTokens)) {
            log.warn("app.knowledge.parent-child sizes ({}/{}) were clamped to child={} parent={} words",
                childTokens, parentTokens, parentChildSettings.childWords(), parentChildSettings.parentWords());
        }

        RetrievalOptions options;
        try {
            options = new RetrievalOptions(
                fusionEnabled,
                fusionK,
                vectorWeight,
                keywordWeight,
                parseLambda(mmrLambda),
                rewriteEnabled ? RetrievalOptions.RewriteMode.parse(rewriteMode) : RetrievalOptions.RewriteMode.OFF,
                maxVariants,
                parentChildEnabled
            );
        } catch (IllegalArgumentException e) {
            log.warn("Invalid app.knowledge.fusion / query-rewrite config ({}) - fusion and rewrite stay OFF", e.getMessage());
            options = new RetrievalOptions(
                false, RetrievalOptions.LEGACY_RRF_K, 1.0, 1.0, null, RetrievalOptions.RewriteMode.OFF, 3, parentChildEnabled
            );
        }
        this.defaultOptions = options;

        RetrievalOptions preset;
        try {
            preset = new RetrievalOptions(
                true, fusionK, vectorWeight, keywordWeight, parseLambda(mmrLambda), RetrievalOptions.RewriteMode.OFF, 1, false
            );
        } catch (IllegalArgumentException e) {
            preset = new RetrievalOptions(
                true, RetrievalOptions.LEGACY_RRF_K, 1.0, 1.0, null, RetrievalOptions.RewriteMode.OFF, 1, false
            );
        }
        this.fusionPreset = preset;
    }

    private static Double parseLambda(String raw) {
        if (raw == null || raw.isBlank() || raw.trim().equalsIgnoreCase("null")) {
            return null;
        }
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("mmr-lambda is not a number: " + raw);
        }
    }

    /** Whether new documents of opted-in collections are ingested parent-child. */
    public boolean isParentChildEnabled() {
        return parentChildEnabled;
    }

    public ParentChildChunker.Settings parentChildSettings() {
        return parentChildSettings;
    }

    /** The options every normal search runs with, or null when every stage is off (the exact legacy path). */
    public RetrievalOptions defaultOptions() {
        return defaultOptions.isLegacy() ? null : defaultOptions;
    }

    /** What the deployment's flags resolve to, never null (read by the settings endpoint to show what is switched on). */
    public RetrievalOptions deploymentOptions() {
        return defaultOptions;
    }

    /**
     * The configured fusion parameters (k, weights, MMR) with fusion forced ON, regardless of whether
     * {@code app.knowledge.fusion.enabled} is set - what the eval harness runs for a "fusion" config.
     */
    public RetrievalOptions fusionPreset() {
        return fusionPreset;
    }
}
