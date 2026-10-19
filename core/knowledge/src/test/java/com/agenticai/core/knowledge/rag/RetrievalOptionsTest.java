package com.agenticai.core.knowledge.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.knowledge.rag.RetrievalOptions.RewriteMode;
import org.junit.jupiter.api.Test;

/** Option normalisation and the deployment-config resolver. */
class RetrievalOptionsTest {

    private static RagRetrievalConfig config(
        boolean parentChild, boolean fusion, int k, double vw, double kw, String lambda, boolean rewrite, String mode
    ) {
        return new RagRetrievalConfig(parentChild, 200, 1000, fusion, k, vw, kw, lambda, rewrite, mode, 3);
    }

    @Test
    void withFusionOffTheFusionParametersAreForcedBackToTheLegacyValues() {
        RetrievalOptions o = new RetrievalOptions(false, 5, 0.2, 9.0, 0.5, RewriteMode.MULTI, 3, false);

        assertEquals(RetrievalOptions.LEGACY_RRF_K, o.rrfK());
        assertEquals(1.0, o.vectorWeight());
        assertEquals(1.0, o.keywordWeight());
        assertNull(o.mmrLambda());
        assertFalse(o.isLegacy(), "rewriting is still on");
    }

    @Test
    void anOptionsValueWithEveryStageOffIsLegacy() {
        assertTrue(new RetrievalOptions(false, 60, 1.0, 1.0, null, RewriteMode.OFF, 3, false).isLegacy());
        assertFalse(new RetrievalOptions(true, 60, 1.0, 1.0, null, RewriteMode.OFF, 3, false).isLegacy());
        assertFalse(new RetrievalOptions(false, 60, 1.0, 1.0, null, RewriteMode.OFF, 3, true).isLegacy());
    }

    @Test
    void invalidFusionParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RetrievalOptions(true, 0, 1.0, 1.0, null, RewriteMode.OFF, 3, false));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalOptions(true, 60, -1.0, 1.0, null, RewriteMode.OFF, 3, false));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalOptions(true, 60, 0.0, 0.0, null, RewriteMode.OFF, 3, false));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalOptions(true, 60, Double.NaN, 1.0, null, RewriteMode.OFF, 3, false));
        assertThrows(IllegalArgumentException.class, () -> new RetrievalOptions(true, 60, 1.0, 1.0, 1.5, RewriteMode.OFF, 3, false));
    }

    @Test
    void aSingleLegWeightOfZeroIsAllowed() {
        RetrievalOptions keywordOnly = new RetrievalOptions(true, 60, 0.0, 1.0, null, RewriteMode.OFF, 3, false);
        assertEquals(0.0, keywordOnly.vectorWeight());
    }

    @Test
    void rewriteModeParsesLeniently() {
        assertEquals(RewriteMode.MULTI, RewriteMode.parse(" Multi "));
        assertEquals(RewriteMode.HYDE, RewriteMode.parse("hyde"));
        assertEquals(RewriteMode.OFF, RewriteMode.parse("something else"));
        assertEquals(RewriteMode.OFF, RewriteMode.parse(null));
    }

    @Test
    void maxVariantsIsBounded() {
        assertEquals(1, new RetrievalOptions(false, 60, 1, 1, null, RewriteMode.MULTI, -4, false).maxVariants());
        assertEquals(QueryRewriter.MAX_VARIANTS_CAP, new RetrievalOptions(false, 60, 1, 1, null, RewriteMode.MULTI, 50, false).maxVariants());
    }

    @Test
    void deploymentDefaultsWithEverySwitchOffResolveToNullSoSearchRunsTheLegacyPath() {
        RagRetrievalConfig cfg = config(false, false, 60, 1.0, 1.0, "", false, "multi");

        assertNull(cfg.defaultOptions());
        assertFalse(cfg.isParentChildEnabled());
        assertTrue(cfg.deploymentOptions().isLegacy());
    }

    @Test
    void eachSwitchProducesItsOwnStage() {
        RetrievalOptions fusion = config(false, true, 30, 2.0, 1.0, "0.7", false, "multi").defaultOptions();
        assertNotNull(fusion);
        assertTrue(fusion.fusion());
        assertEquals(30, fusion.rrfK());
        assertEquals(2.0, fusion.vectorWeight());
        assertEquals(0.7, fusion.mmrLambda());
        assertFalse(fusion.expandParents());

        RetrievalOptions rewrite = config(false, false, 60, 1.0, 1.0, "", true, "hyde").defaultOptions();
        assertEquals(RewriteMode.HYDE, rewrite.rewriteMode());

        RetrievalOptions parents = config(true, false, 60, 1.0, 1.0, "", false, "multi").defaultOptions();
        assertTrue(parents.expandParents());
    }

    @Test
    void aBadConfigValueFallsBackToLegacyInsteadOfThrowingPerSearch() {
        RagRetrievalConfig badLambda = config(false, true, 60, 1.0, 1.0, "not-a-number", false, "multi");
        assertNull(badLambda.defaultOptions());

        RagRetrievalConfig badK = config(false, true, -3, 1.0, 1.0, "", true, "multi");
        // Fusion params were invalid, so the whole fusion/rewrite pair falls back (parent-child is independent of them).
        assertNull(badK.defaultOptions());

        // The fusion preset used by the eval is also safe.
        assertEquals(RetrievalOptions.LEGACY_RRF_K, badK.fusionPreset().rrfK());
    }

    @Test
    void parentChildSizesAreClamped() {
        RagRetrievalConfig cfg = new RagRetrievalConfig(true, 5, 6, false, 60, 1.0, 1.0, "", false, "multi", 3);
        assertEquals(10, cfg.parentChildSettings().childWords());
        assertEquals(20, cfg.parentChildSettings().parentWords());
    }
}
