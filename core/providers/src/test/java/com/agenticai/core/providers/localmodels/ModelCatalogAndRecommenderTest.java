package com.agenticai.core.providers.localmodels;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.providers.localmodels.ModelCatalog.ToolCalling;
import com.agenticai.core.providers.localmodels.ModelRecommender.Recommendation;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelCatalogAndRecommenderTest {

    private final ModelCatalog shipped = new ModelCatalog();
    private final ModelRecommender recommender = new ModelRecommender();

    private static HardwareProfile cpu(double ramGb) {
        return new HardwareProfile(ramGb, 8, null, false);
    }

    // --- the shared catalog file ---

    @Test
    void theShippedCatalogParsesAndEveryLadderRungNamesACatalogModel() {
        assertFalse(shipped.entries().isEmpty());
        assertFalse(shipped.ladder().isEmpty());
        for (ModelCatalog.Rung rung : shipped.ladder()) {
            assertTrue(shipped.find(rung.tag()).isPresent(), "ladder names unknown model " + rung.tag());
        }
        assertEquals(0, shipped.ladder().get(shipped.ladder().size() - 1).minRamGb(), "the last rung must be 0");
    }

    @Test
    void theCatalogColumnsMapToTheApiToolCallingValues() {
        assertEquals(ToolCalling.NATIVE, shipped.find("qwen3.5:4b").orElseThrow().toolCalling());
        assertEquals(ToolCalling.NONE, shipped.find("llama3.2:3b").orElseThrow().toolCalling());
        assertEquals(ToolCalling.CONSTRAINED, shipped.find("hermes3:8b").orElseThrow().toolCalling());
        assertEquals("native", ToolCalling.NATIVE.wire());
        assertEquals("constrained", ToolCalling.CONSTRAINED.wire());
        assertEquals("none", ToolCalling.NONE.wire());
        ModelCatalog.Entry e = shipped.find("QWEN3.5:4B").orElseThrow();
        assertEquals(3.0, e.downloadGb());
        assertEquals(10.0, e.ramGb());
    }

    @Test
    void theDeployLinkAndTheClasspathFileAreTheSameFile() throws Exception {
        java.nio.file.Path link = java.nio.file.Path.of("..", "..", "deploy", "models", "catalog.txt");
        if (!java.nio.file.Files.exists(link)) {
            return; // run from a packaged tree without the deploy folder
        }
        String linked = java.nio.file.Files.readString(link);
        try (var in = ModelCatalog.class.getResourceAsStream(ModelCatalog.RESOURCE)) {
            assertEquals(linked, new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void malformedRowsAreSkippedAndTheRestKept() {
        ModelCatalog c = ModelCatalog.parse(
                "# header\n"
                        + "#ladder|8|good:1b\n"
                        + "#ladder|x|broken\n"
                        + "#ladder|0|good:1b\n"
                        + "good:1b|Fam|1|4|structured|ok\n"
                        + "short|row\n"
                        + "bad:num|Fam|abc|4|structured|no\n"
                        + "\n");
        assertEquals(1, c.entries().size());
        assertEquals(2, c.ladder().size());
        assertEquals(8, c.ladder().get(0).minRamGb());
    }

    // --- the ladder reproduces scripts/vegaduta recommended_local_model ---

    @Test
    void theLadderMatchesTheScriptForCpuAndAppleSiliconMemory() {
        record Case(double ram, String tag) {}
        for (Case c : List.of(
                new Case(4, "qwen3.5:2b"), new Case(11.9, "qwen3.5:2b"), new Case(12, "qwen3.5:4b"),
                new Case(16, "qwen3.5:4b"), new Case(24, "qwen3.5:9b"), new Case(31.9, "qwen3.5:9b"),
                new Case(32, "gpt-oss:20b"), new Case(128, "gpt-oss:20b"))) {
            assertEquals(c.tag(), recommender.recommend(cpu(c.ram()), shipped).recommended().tag(), "ram " + c.ram());
        }
    }

    @Test
    void aDiscreteGpuCountsAtOneAndAHalfTimesItsMemory() {
        HardwareProfile small = new HardwareProfile(
                16, 8, new HardwareProfile.Gpu("NVIDIA", "RTX 4090", 24), false);
        assertEquals("gpt-oss:20b", recommender.recommend(small, shipped).recommended().tag(), "24 GB card ~ 36 GB");
        HardwareProfile weakCard = new HardwareProfile(
                8, 4, new HardwareProfile.Gpu("NVIDIA", "GTX 1050", 2), false);
        assertEquals("qwen3.5:2b", recommender.recommend(weakCard, shipped).recommended().tag());
    }

    @Test
    void appleSiliconUnifiedMemoryIsPlainSystemMemory() {
        HardwareProfile m = new HardwareProfile(
                16, 10, new HardwareProfile.Gpu("Apple", "Apple M2", 16), true);
        assertEquals("qwen3.5:4b", recommender.recommend(m, shipped).recommended().tag());
    }

    // --- shape of the answer ---

    @Test
    void alternativesFitTheBudgetNeverRepeatThePickAndSkipChatOnlyModels() {
        Recommendation r = recommender.recommend(cpu(24), shipped);
        assertEquals("qwen3.5:9b", r.recommended().tag());
        assertFalse(r.alternatives().isEmpty());
        assertTrue(r.alternatives().size() <= ModelRecommender.MAX_ALTERNATIVES);
        for (ModelRecommender.Pick alt : r.alternatives()) {
            assertTrue(alt.ramGb() <= r.budgetGb(), alt.tag() + " does not fit the budget");
            assertFalse(alt.tag().equals(r.recommended().tag()));
            assertFalse("none".equals(alt.toolCalling()), alt.tag() + " cannot call tools");
        }
        assertEquals(
                r.alternatives().stream().map(ModelRecommender.Pick::tag).distinct().count(),
                r.alternatives().size());
    }

    @Test
    void aMachineBelowTheSmallestModelStillGetsAPickWithAWarning() {
        Recommendation r = recommender.recommend(cpu(2), shipped);
        assertEquals("qwen3.5:2b", r.recommended().tag());
        assertTrue(r.recommended().note().contains("expect it to be slow"), r.recommended().note());
        assertTrue(r.alternatives().isEmpty());
    }

    @Test
    void unknownMemoryGetsTheSmallestModelNotAnError() {
        Recommendation r = recommender.recommend(cpu(0), shipped);
        assertNotNull(r);
        assertEquals("qwen3.5:2b", r.recommended().tag());
    }

    @Test
    void withoutALadderTheBestModelThatFitsTheBudgetIsPicked() {
        ModelCatalog noLadder = new ModelCatalog(shipped.entries(), List.of());
        // 16 GB -> budget 12 GB -> a native model, as big as fits, never bigger.
        Recommendation r = recommender.recommend(cpu(16), noLadder);
        assertEquals("native", r.recommended().toolCalling());
        assertTrue(r.recommended().ramGb() <= 12, r.recommended().tag());
        double biggestNativeThatFits = shipped.entries().stream()
                .filter(e -> e.toolCalling() == ToolCalling.NATIVE && e.ramGb() <= 12)
                .mapToDouble(ModelCatalog.Entry::ramGb)
                .max()
                .orElseThrow();
        assertEquals(biggestNativeThatFits, r.recommended().ramGb());
    }

    // --- the whole catalog, flagged ---

    @Test
    void theCatalogListsEveryRowInFileOrderFlaggedAgainstTheBudget() {
        Recommendation r = recommender.recommend(cpu(16), shipped);
        assertEquals(shipped.entries().size(), r.catalog().size());
        assertEquals(
                shipped.entries().stream().map(ModelCatalog.Entry::tag).toList(),
                r.catalog().stream().map(ModelRecommender.Pick::tag).toList());
        for (ModelRecommender.Pick p : r.catalog()) {
            assertEquals(p.ramGb() <= r.budgetGb(), p.fits(), p.tag());
        }
        assertTrue(r.catalog().stream().anyMatch(p -> !p.fits()), "a 16 GB machine cannot run everything");
        assertTrue(r.catalog().stream().anyMatch(ModelRecommender.Pick::fits));
        assertTrue(r.recommended().fits());
        ModelRecommender.Pick big = r.catalog().stream().filter(p -> p.tag().equals("gpt-oss:120b")).findFirst().orElseThrow();
        assertFalse(big.fits());
        assertEquals(80.0, big.ramGb());
    }

    @Test
    void theDetailColumnsAreParsedAndEveryShippedRowHasThem() {
        ModelCatalog.Entry e = shipped.find("qwen3.5:4b").orElseThrow();
        assertEquals(4.0, e.paramsB());
        assertEquals(262, e.contextK());
        assertEquals(5.0, e.vramGb());
        assertEquals("Apache-2.0", e.licence());
        assertTrue(e.thinking());
        assertFalse(e.bestFor().isBlank());
        assertFalse(shipped.find("llama3.1:8b").orElseThrow().thinking());
        for (ModelCatalog.Entry row : shipped.entries()) {
            assertTrue(row.paramsB() > 0, row.tag() + " has no parameter count");
            assertTrue(row.contextK() > 0, row.tag() + " has no context window");
            assertTrue(row.vramGb() > 0, row.tag() + " has no VRAM figure");
            assertFalse(row.licence().isBlank(), row.tag() + " has no licence");
            assertFalse(row.bestFor().isBlank(), row.tag() + " has no best-for");
            assertTrue(row.vramGb() <= row.ramGb(), row.tag() + ": VRAM should not exceed the RAM budget");
        }
    }

    @Test
    void aRowWithOnlyTheSixOriginalColumnsStillParses() {
        ModelCatalog c = ModelCatalog.parse("old:1b|Fam|1|4|structured|ok\nnew:1b|Fam|1|4|text|ok|1.2|32|2|MIT|yes|tests\n");
        ModelCatalog.Entry old = c.find("old:1b").orElseThrow();
        assertEquals(0, old.paramsB());
        assertEquals("", old.licence());
        assertFalse(old.thinking());
        ModelCatalog.Entry fresh = c.find("new:1b").orElseThrow();
        assertEquals(1.2, fresh.paramsB());
        assertEquals(32, fresh.contextK());
        assertEquals(2.0, fresh.vramGb());
        assertEquals("MIT", fresh.licence());
        assertTrue(fresh.thinking());
        assertEquals("tests", fresh.bestFor());
        assertEquals(ToolCalling.CONSTRAINED, fresh.toolCalling());
    }

    @Test
    void aLadderNamingAChatOnlyModelOnlyWinsWhenNothingElseIsOffered() {
        ModelCatalog c = ModelCatalog.parse(
                "#ladder|0|chatty:1b\nchatty:1b|F|1|2|chat only|talk\ntooly:1b|F|1|2|structured|work\n");
        Recommendation r = recommender.recommend(cpu(8), c);
        assertEquals("chatty:1b", r.recommended().tag());
        assertTrue(r.alternatives().stream().anyMatch(a -> a.tag().equals("tooly:1b")));
    }

    @Test
    void anEmptyCatalogYieldsNoRecommendation() {
        assertNull(recommender.recommend(cpu(16), new ModelCatalog(List.of(), List.of())));
    }
}
