package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.*;

import com.agenticai.core.workflow.WorkflowAiNodeHandler.AiParam;
import com.agenticai.core.workflow.WorkflowAiNodeHandler.Coerced;
import com.agenticai.core.workflow.WorkflowAiNodeHandler.Extraction;
import com.agenticai.core.workflow.WorkflowAiNodeHandler.ParamType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Strict coercion of extractor replies: {@link AiOutputValidator}. */
class AiOutputValidatorTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static AiParam p(String name, ParamType t, String... options) {
        return new AiParam(name, t, "d", false, List.of(options));
    }

    private static Coerced c(AiParam p, String json) throws Exception {
        return AiOutputValidator.coerce(p, M.readTree(json), M.getNodeFactory(), M);
    }

    @Test
    void absentAndNullAreAbsentNotInvented() throws Exception {
        assertNull(c(p("a", ParamType.STRING), "null").value());
        assertNull(c(p("a", ParamType.STRING), "null").problem());
        assertNull(AiOutputValidator.coerce(p("a", ParamType.NUMBER), null, M.getNodeFactory(), M).problem());
    }

    @Test
    void numbersAcceptThousandsSeparatorsAndRejectJunk() throws Exception {
        assertEquals(1200.5, c(p("n", ParamType.NUMBER), "\"1,200.50\"").value().asDouble(), 1e-9);
        assertTrue(c(p("n", ParamType.NUMBER), "7").value().isIntegralNumber());
        assertEquals("expected a number", c(p("n", ParamType.NUMBER), "\"seven\"").problem());
    }

    @Test
    void booleansEnumsAndArraysCoerceOrReportAProblem() throws Exception {
        assertTrue(c(p("b", ParamType.BOOLEAN), "\"yes\"").value().asBoolean());
        assertNotNull(c(p("b", ParamType.BOOLEAN), "\"perhaps\"").problem());
        assertEquals("red", c(p("e", ParamType.ENUM, "red", "blue"), "\"RED\"").value().asText());
        assertNotNull(c(p("e", ParamType.ENUM, "red", "blue"), "\"green\"").problem());
        assertEquals(2, c(p("a", ParamType.ARRAY), "[\"x\",\"y\"]").value().size());
    }

    @Test
    void validateKeepsOnlyDeclaredKeysAndCollectsProblemsPerParameter() throws Exception {
        List<AiParam> params = List.of(p("n", ParamType.NUMBER), p("s", ParamType.STRING));
        JsonNode reply = M.readTree("{\"n\":\"x\",\"s\":\"ok\",\"extra\":1}");
        Extraction e = AiOutputValidator.validate(reply, params, M);
        assertEquals(List.of("n", "s"), new java.util.ArrayList<>(iter(e.values().fieldNames())));
        assertTrue(e.values().get("n").isNull());
        assertEquals("ok", e.values().get("s").asText());
        assertEquals(List.of("n"), List.copyOf(e.problems().keySet()));
    }

    private static List<String> iter(java.util.Iterator<String> it) {
        List<String> out = new java.util.ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }
}
