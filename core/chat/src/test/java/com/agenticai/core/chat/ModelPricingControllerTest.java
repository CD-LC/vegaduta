package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direct unit test (no MockMvc/Spring context - this module has no
 * controller-slice test infra, matching every existing {@code core/chat}
 * controller: none of them have a test class of their own). {@link
 * ModelPricingController} adds no pricing logic of its own, so this mostly
 * confirms the comma-split/trim/dedupe request handling and that the
 * response for each id matches {@link LlmPricingCatalog} exactly - see
 * {@link LlmPricingCatalogTest} for the rate-table coverage itself.
 */
class ModelPricingControllerTest {

    private final ModelPricingController controller = new ModelPricingController(new LlmPricingCatalog());

    @Test
    void returnsOneEntryPerRequestedModelInOrder() {
        List<ModelPricingController.ModelPriceEntry> result = controller.pricing("claude-opus-4-8,gpt-4o-mini");

        assertEquals(2, result.size());
        assertEquals("claude-opus-4-8", result.get(0).modelId());
        assertEquals(0, result.get(0).inputPerMillionUsd().compareTo(new BigDecimal("5")));
        assertEquals(0, result.get(0).outputPerMillionUsd().compareTo(new BigDecimal("25")));
        assertEquals("gpt-4o-mini", result.get(1).modelId());
        assertEquals(0, result.get(1).inputPerMillionUsd().compareTo(new BigDecimal("0.15")));
        assertEquals(0, result.get(1).outputPerMillionUsd().compareTo(new BigDecimal("0.60")));
    }

    @Test
    void blankEntriesAreDroppedAndWhitespaceIsTrimmed() {
        List<ModelPricingController.ModelPriceEntry> result = controller.pricing(" claude-opus-4-8 , , gpt-4o-mini ,   ");

        assertEquals(List.of("claude-opus-4-8", "gpt-4o-mini"), result.stream().map(ModelPricingController.ModelPriceEntry::modelId).toList());
    }

    @Test
    void duplicateIdsAreDeduped() {
        List<ModelPricingController.ModelPriceEntry> result = controller.pricing("gpt-4o-mini,gpt-4o-mini");

        assertEquals(1, result.size());
    }

    @Test
    void anUnrecognizedModelStillComesBackAtTheConservativeDefaultRateRatherThanBeingOmitted() {
        List<ModelPricingController.ModelPriceEntry> result = controller.pricing("totally-unknown-model-v9");

        assertEquals(1, result.size());
        assertTrue(result.get(0).inputPerMillionUsd().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void postBodyFormGivesTheSameAnswerAsTheQueryForm() {
        // ~500 ids in a query string blew past the request-line limit in prod;
        // the body form must behave identically (trim, drop blanks/nulls, dedupe).
        List<ModelPricingController.ModelPriceEntry> result = controller.pricing(
            new ModelPricingController.ModelPricingRequest(
                java.util.Arrays.asList(" claude-opus-4-8 ", "", null, "gpt-4o-mini", "gpt-4o-mini")));

        assertEquals(2, result.size());
        assertEquals("claude-opus-4-8", result.get(0).modelId());
        assertEquals("gpt-4o-mini", result.get(1).modelId());
        assertEquals(0, controller.pricing(new ModelPricingController.ModelPricingRequest(null)).size());
    }
}
