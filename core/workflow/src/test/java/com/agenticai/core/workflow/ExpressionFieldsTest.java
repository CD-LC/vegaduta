package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ExpressionFields}: the per-type field table, modes and the conditional {@code when}
 * predicates.
 */
class ExpressionFieldsTest {

    private static ExpressionFields.Field onlyField(String type) {
        List<ExpressionFields.Field> fields = ExpressionFields.of(type);
        assertEquals(1, fields.size());
        return fields.get(0);
    }

    @Test
    void unknownAndNullTypesResolveNothing() {
        assertTrue(ExpressionFields.of(null).isEmpty());
        assertTrue(ExpressionFields.of("does_not_exist").isEmpty());
    }

    @Test
    void typeNamesAreCaseInsensitive() {
        assertEquals(ExpressionFields.of("agent"), ExpressionFields.of("AGENT"));
    }

    @Test
    void theAgentQueryIsTextWithAnInputOnlyFallback() {
        ExpressionFields.Field f = onlyField("agent");
        assertEquals("inputMapping.query", f.path());
        assertEquals(ExpressionFields.Mode.TEXT, f.mode());
        assertEquals(ExpressionFields.Legacy.INPUT_ONLY, f.legacy());
        assertTrue(f.appliesTo(Map.of()), "an unconditional field always applies");
    }

    @Test
    void theConditionExpressionIsBare() {
        ExpressionFields.Field f = onlyField("condition");
        assertEquals("expression", f.path());
        assertEquals(ExpressionFields.Mode.BARE, f.mode());
    }

    @Test
    void theCodeTemplateOnlyAppliesToTheTemplateOperation() {
        ExpressionFields.Field f = onlyField("code");
        assertEquals("template", f.path());
        assertTrue(f.appliesTo(Map.of("operation", "template")));
        assertFalse(f.appliesTo(Map.of("operation", "extract")));
        assertFalse(f.appliesTo(Map.of()), "a missing operation is not template");
    }

    @Test
    void theHttpBodySkipsTheNoneBodyType() {
        List<ExpressionFields.Field> fields = ExpressionFields.of("http");
        assertEquals(
                List.of("url", "body", "headers[].value", "query[].value"),
                ExpressionFields.paths("http"));
        assertEquals(4, fields.size());
        ExpressionFields.Field body = fields.get(1);
        assertFalse(body.appliesTo(Map.of("bodyType", "none")));
        assertFalse(body.appliesTo(Map.of("bodyType", "NONE")));
        assertFalse(body.appliesTo(Map.of()), "a missing body type defaults to none");
        assertFalse(body.appliesTo(Map.of("bodyType", "  ")), "a blank body type defaults to none");
        assertTrue(body.appliesTo(Map.of("bodyType", "json")));
        assertTrue(body.appliesTo(Map.of("bodyType", " raw ")));
    }

    @Test
    void perItemAndJsonTextModesLandOnTheRightTypes() {
        ExpressionFields.Field subject = ExpressionFields.of("bulk_email").get(0);
        assertEquals("subject", subject.path());
        assertEquals(ExpressionFields.Mode.PER_ITEM, subject.mode());
        assertEquals(ExpressionFields.Mode.JSON_TEXT, onlyField("tool").mode());
        assertEquals("argsJson", onlyField("tool").path());
    }

    @Test
    void everyDeclaredTypeIsInTheTypeSet() {
        for (String type :
                List.of(
                        "agent",
                        "condition",
                        "code",
                        "http",
                        "tool",
                        "device_control",
                        "output",
                        "bulk_email",
                        "wait",
                        "crm_lead",
                        "set",
                        "filter",
                        "switch",
                        "limit",
                        "merge",
                        "question_classifier",
                        "parameter_extractor",
                        "variable_aggregator",
                        "document_extractor",
                        "data_table")) {
            assertTrue(ExpressionFields.types().contains(type), type);
            assertFalse(ExpressionFields.of(type).isEmpty(), type);
        }
    }

    @Test
    void crmLeadFieldsAllCarryTheCrmLegacy() {
        for (ExpressionFields.Field f : ExpressionFields.of("crm_lead")) {
            assertEquals(ExpressionFields.Legacy.CRM, f.legacy(), f.path());
            assertEquals(ExpressionFields.Mode.TEXT, f.mode(), f.path());
        }
    }
}
