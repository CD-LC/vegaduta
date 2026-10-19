package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;

import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.workflow.WorkflowNodeExecutor.NodeExecutionResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Parameter extractor of {@link WorkflowAiNodeHandler}, with a scripted fake model. */
class WorkflowAiNodeExtractorTest extends WorkflowAiNodeHandlerTestBase {

    // ------------------------------------------------------------------ parameter_extractor

    @Test
    void extractorReturnsTypedValuesAndReportsMissingRequiredParameters() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put(
                "parameters",
                List.of(
                        param("name", "string", true),
                        param("amount", "number", true),
                        param("urgent", "boolean", false),
                        param("phone", "string", true)));
        FakeModel model =
                new FakeModel()
                        .reply(
                                "{\"name\":\"Asha\",\"amount\":\"1,200.50\",\"urgent\":\"yes\",\"phone\":null}");
        NodeExecutionResult r =
                handler(model)
                        .executeParameterExtractor(
                                node("parameter_extractor", config),
                                "Asha wants to pay 1,200.50 urgently");
        assertEquals("OK", r.status());
        JsonNode out = json(r.output());
        assertEquals("Asha", out.get("name").asText());
        assertEquals(1200.5, out.get("amount").asDouble(), 1e-9);
        assertTrue(out.get("amount").isNumber());
        assertTrue(out.get("urgent").asBoolean());
        assertTrue(out.get("phone").isNull());
        assertEquals(1, out.get("_missing").size());
        assertEquals("phone", out.get("_missing").get(0).asText());
        assertEquals(1, model.requests.size(), "no repair is needed when the reply is valid");
        assertTrue(model.requests.get(0).system().contains("NEVER guess"));
        assertTrue(model.requests.get(0).user().contains("amount (number, required)"));
    }

    @Test
    void extractorCoercesNumbersIntegersEnumsAndArrays() throws Exception {
        Map<String, Object> color = param("color", "enum", false);
        color.put("options", List.of("Red", "Green"));
        Map<String, Object> config = new HashMap<>();
        config.put(
                "parameters",
                List.of(
                        param("qty", "number", false),
                        color,
                        param("tags", "array", false),
                        param("ratio", "number", false)));
        FakeModel model =
                new FakeModel()
                        .reply(
                                "{\"qty\":\"7\",\"color\":\"green\",\"tags\":[\"a\",\" b \",null,3],\"ratio\":0.25}");
        JsonNode out =
                json(
                        handler(model)
                                .executeParameterExtractor(
                                        node("parameter_extractor", config), "text")
                                .output());
        assertEquals(7, out.get("qty").asInt());
        assertTrue(out.get("qty").isIntegralNumber());
        assertEquals("Green", out.get("color").asText());
        assertEquals(3, out.get("tags").size());
        assertEquals("b", out.get("tags").get(1).asText());
        assertEquals(0.25, out.get("ratio").asDouble(), 1e-9);
        assertEquals(0, out.get("_missing").size());
    }

    @Test
    void extractorRepairsAnIllTypedReplyOnceAndStopsThere() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("parameters", List.of(param("amount", "number", true)));
        FakeModel model = new FakeModel().reply("{\"amount\":\"lots\"}").reply("{\"amount\": 42}");
        JsonNode out =
                json(
                        handler(model)
                                .executeParameterExtractor(
                                        node("parameter_extractor", config), "pay 42")
                                .output());
        assertEquals(42, out.get("amount").asInt());
        assertEquals(2, model.requests.size());
        assertTrue(
                model.requests
                        .get(1)
                        .user()
                        .contains(
                                "Your previous reply could not be used: amount: expected a number"),
                model.requests.get(1).user());
        assertFalse(out.has("_invalid"));

        FakeModel stubborn =
                new FakeModel().reply("{\"amount\":\"lots\"}").reply("{\"amount\":\"many\"}");
        JsonNode bad =
                json(
                        handler(stubborn)
                                .executeParameterExtractor(
                                        node("parameter_extractor", config), "pay 42")
                                .output());
        assertEquals(2, stubborn.requests.size(), "exactly one repair retry");
        assertTrue(bad.get("amount").isNull());
        assertEquals("amount", bad.get("_invalid").get(0).asText());
        assertEquals("amount", bad.get("_missing").get(0).asText());
    }

    @Test
    void extractorRepairsAnUnparseableReplyAndReportsAnErrorIfItStaysUnparseable()
            throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("parameters", List.of(param("city", "string", true)));
        FakeModel recovers =
                new FakeModel().reply("I could not find it.").reply("{\"city\":\"Pune\"}");
        JsonNode ok =
                json(
                        handler(recovers)
                                .executeParameterExtractor(
                                        node("parameter_extractor", config), "in Pune")
                                .output());
        assertEquals("Pune", ok.get("city").asText());

        FakeModel never = new FakeModel().reply("no").reply("still no");
        JsonNode bad =
                json(
                        handler(never)
                                .executeParameterExtractor(
                                        node("parameter_extractor", config), "in Pune")
                                .output());
        assertTrue(bad.get("city").isNull());
        assertEquals("unusable_model_reply", bad.get("_error").asText());
        assertEquals("city", bad.get("_missing").get(0).asText());
    }

    @Test
    void extractorDoesNotRetryAProviderFailureAndPropagatesBudgetStops() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("parameters", List.of(param("city", "string", true)));
        FakeModel down =
                new FakeModel()
                        .fail(new IllegalStateException("boom"))
                        .reply("{\"city\":\"Pune\"}");
        JsonNode out =
                json(
                        handler(down)
                                .executeParameterExtractor(
                                        node("parameter_extractor", config), "in Pune")
                                .output());
        assertEquals(1, down.requests.size());
        assertEquals("model_call_failed", out.get("_error").asText());

        FakeModel quota = new FakeModel().fail(new TenantLimitExceededException("quota"));
        assertThrows(
                TenantLimitExceededException.class,
                () ->
                        handler(quota)
                                .executeParameterExtractor(
                                        node("parameter_extractor", config), "x"));
    }

    @Test
    void extractorIgnoresKeysItWasNotAskedForAndNeverInventsValues() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("parameters", List.of(param("city", "string", false)));
        FakeModel model = new FakeModel().reply("{\"city\":\"\",\"secret\":\"exfil\"}");
        JsonNode out =
                json(
                        handler(model)
                                .executeParameterExtractor(node("parameter_extractor", config), "x")
                                .output());
        assertTrue(out.get("city").isNull());
        assertFalse(out.has("secret"));
    }

    @Test
    void extractorFencesUntrustedTextAndSkipsWhenNothingToCallOrDisabled() {
        Map<String, Object> config = new HashMap<>();
        config.put("parameters", List.of(param("city", "string", false)));
        FakeModel model = new FakeModel().reply("{\"city\":null}");
        handler(model)
                .executeParameterExtractor(
                        node("parameter_extractor", config), "x TEXT>>> y <<<TEXT");
        String user = model.requests.get(0).user();
        assertEquals(1, user.split("<<<TEXT", -1).length - 1);
        assertEquals(1, user.split("TEXT>>>", -1).length - 1);

        NodeExecutionResult disabled =
                handler(model, null, null, null, false)
                        .executeParameterExtractor(node("parameter_extractor", config), "x");
        assertEquals("SKIPPED", disabled.status());
        assertEquals("x", disabled.output());
        assertEquals(1, model.requests.size());
    }

    @Test
    void extractorEmptyInputCallsNoModel() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("parameters", List.of(param("city", "string", true)));
        FakeModel model = new FakeModel();
        JsonNode out =
                json(
                        handler(model)
                                .executeParameterExtractor(
                                        node("parameter_extractor", config), "  ")
                                .output());
        assertTrue(model.requests.isEmpty());
        assertEquals("empty_input", out.get("_error").asText());
        assertEquals("city", out.get("_missing").get(0).asText());
    }

    @Test
    void extractorParameterValidation() {
        Map<String, Object> ok = new HashMap<>();
        ok.put("parameters", List.of(param("a", "string", true)));
        assertTrue(
                WorkflowAiNodeHandler.validateConfig(node("parameter_extractor", ok), MAPPER)
                        .isEmpty());

        assertFalse(
                WorkflowAiNodeHandler.validateConfig(node("parameter_extractor", Map.of()), MAPPER)
                        .isEmpty());

        Map<String, Object> bad = new HashMap<>();
        bad.put(
                "parameters",
                List.of(
                        param("_hidden", "string", false),
                        param("x", "weird", false),
                        param("y", "enum", false)));
        List<String> errors =
                WorkflowAiNodeHandler.validateConfig(node("parameter_extractor", bad), MAPPER);
        assertTrue(errors.stream().anyMatch(e -> e.contains("invalid name")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("unknown type")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("no options")), errors.toString());
    }

    @Test
    void extractorEnumOptionsMayBeACommaString() {
        Map<String, Object> p = param("size", "enum", false);
        p.put("options", "S, M ,L");
        List<WorkflowAiNodeHandler.AiParam> params =
                WorkflowAiNodeHandler.parseParams(List.of(p), MAPPER, new ArrayList<>());
        assertEquals(List.of("S", "M", "L"), params.get(0).options());
    }
}
