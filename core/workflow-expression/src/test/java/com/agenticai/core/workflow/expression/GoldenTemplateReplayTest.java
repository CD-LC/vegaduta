package com.agenticai.core.workflow.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.json.JsonMappers;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Spec 3.1 golden replay: every template string harvested from the repo
 * (golden/template-strings.json) must render byte-identically to the pre-expression algorithm in
 * the fields that always templated. A template that legitimately changes meaning belongs in an
 * explicit, reviewed exception list, not in silence.
 */
class GoldenTemplateReplayTest {

    private static final String[] INPUTS = {
        "{\"name\":\"Ada\",\"age\":36,\"tags\":[\"a\"],\"addr\":{\"city\":\"Pune\"}}",
        "plain text",
        "",
        "[1,2]",
    };

    private final ExpressionEngine engine = new ExpressionEngine();

    @Test
    void everyHarvestedTemplateRendersLikeTheOldAlgorithm() throws Exception {
        List<String> templates = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/golden/template-strings.json")) {
            assertTrue(in != null, "golden/template-strings.json must be on the test classpath");
            for (JsonNode e : JsonMappers.plain().readTree(in)) {
                templates.add(e.get("template").asString());
            }
        }
        assertTrue(templates.size() >= 15, "golden file looks truncated: " + templates.size());

        List<String> mismatches = new ArrayList<>();
        for (String template : templates) {
            for (String input : INPUTS) {
                String expected = LegacyTemplateCompatTest.oldRender(template, input);
                String actual;
                try {
                    actual = engine.render(template, BasicEvalContext.ofInput(input), true);
                } catch (ExpressionException ex) {
                    actual = "THROWS: " + ex.getMessage();
                }
                if (!expected.equals(actual)) {
                    mismatches.add(
                            "template="
                                    + template
                                    + " input="
                                    + input
                                    + "\n  old="
                                    + expected
                                    + "\n  new="
                                    + actual);
                }
            }
        }
        assertEquals(List.of(), mismatches);
    }
}
