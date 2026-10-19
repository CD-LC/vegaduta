package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Hosted-form schema parsing and the server-side validation of every field type. */
class WorkflowFormSchemaTest {

    private static Map<String, Object> field(Object... kv) {
        Map<String, Object> f = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            f.put((String) kv[i], kv[i + 1]);
        }
        return f;
    }

    @SafeVarargs
    private static Map<String, Object> triggerConfig(Map<String, Object>... fields) {
        Map<String, Object> form = new HashMap<>();
        form.put("title", "Contact us");
        form.put("fields", List.of(fields));
        Map<String, Object> config = new HashMap<>();
        config.put("triggerType", "form");
        config.put("form", form);
        return config;
    }

    @SafeVarargs
    private static WorkflowFormSchema.Form form(Map<String, Object>... fields) {
        return WorkflowFormSchema.parse(triggerConfig(fields));
    }

    private static Map<String, Object> values(Object... kv) {
        return field(kv);
    }

    // ---- config parsing -----------------------------------------------------------------------------------------

    @Test
    void recognisesAFormTriggerByItsTriggerType() {
        assertTrue(WorkflowFormSchema.isFormTrigger(Map.of("triggerType", "form")));
        assertTrue(WorkflowFormSchema.isFormTrigger(Map.of("triggerType", " FORM ")));
        assertFalse(WorkflowFormSchema.isFormTrigger(Map.of("triggerType", "webhook")));
        assertFalse(WorkflowFormSchema.isFormTrigger(Map.of()));
        assertFalse(WorkflowFormSchema.isFormTrigger(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesEverySupportedFieldType() {
        WorkflowFormSchema.Form form = form(
            field("key", "name", "type", "text", "label", "Your name", "required", true),
            field("key", "message", "type", "long_text"),
            field("key", "legacy", "type", "textarea"),
            field("key", "age", "type", "number", "min", 0, "max", 120),
            field("key", "email", "type", "email", "required", true),
            field("key", "topic", "type", "select", "options", List.of("sales", "support")),
            field("key", "agree", "type", "checkbox", "required", true),
            field("key", "when", "type", "date"));

        assertEquals(8, form.fields().size());
        assertEquals("Your name", form.fields().get(0).label());
        assertEquals("message", form.fields().get(1).label(), "label defaults to the key");
        assertEquals("long_text", form.fields().get(2).type(), "textarea is an alias of long_text");
        assertEquals(WorkflowFormSchema.DEFAULT_TEXT_MAX, form.fields().get(0).maxLength());
        assertEquals(WorkflowFormSchema.DEFAULT_LONG_TEXT_MAX, form.fields().get(1).maxLength());
        assertEquals(List.of("sales", "support"), form.fields().get(5).options());
    }

    @Test
    void rejectsBadSchemasWithClearMessages() {
        assertProblem(Map.of("triggerType", "form"), "'form' definition is missing");
        assertProblem(triggerConfig(), "at least one field");
        assertProblem(triggerConfig(field("key", "1bad", "type", "text")), "must start with a letter");
        assertProblem(triggerConfig(field("key", "a b", "type", "text")), "must start with a letter");
        assertProblem(triggerConfig(field("key", "x", "type", "color")), "unknown type");
        assertProblem(triggerConfig(field("key", "x", "type", "text"), field("key", "x", "type", "email")), "duplicate field key");
        assertProblem(triggerConfig(field("key", "s", "type", "select")), "needs between 1 and");
        assertProblem(triggerConfig(field("key", "s", "type", "select", "options", List.of("a", "a"))), "duplicate");
        assertProblem(triggerConfig(field("key", "n", "type", "number", "min", 5, "max", 1)), "min greater than max");
    }

    @Test
    void redirectMustBeHttps() {
        Map<String, Object> config = triggerConfig(field("key", "x", "type", "text"));
        @SuppressWarnings("unchecked")
        Map<String, Object> form = (Map<String, Object>) config.get("form");

        form.put("redirectUrl", "https://example.com/thanks");
        assertEquals("https://example.com/thanks", WorkflowFormSchema.parse(config).redirectUrl());

        for (String bad : List.of("http://example.com", "javascript:alert(1)", "//example.com", "https:///x",
            "https://user:pw@example.com/", "/relative", "ftp://example.com")) {
            form.put("redirectUrl", bad);
            assertProblem(config, "redirectUrl must be an absolute https:// URL");
        }
    }

    @Test
    void tooManyFieldsAreRejected() {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i <= WorkflowFormSchema.MAX_FIELDS; i++) {
            many.add(field("key", "f" + i, "type", "text"));
        }
        Map<String, Object> form = new HashMap<>();
        form.put("fields", many);
        assertProblem(Map.of("triggerType", "form", "form", form), "at most " + WorkflowFormSchema.MAX_FIELDS);
    }

    @Test
    void validateConfigCollectsEveryProblemForTheSaveTimeLint() {
        List<String> errors = new ArrayList<>();
        WorkflowFormSchema.validateConfig(
            triggerConfig(field("key", "1x", "type", "text"), field("key", "y", "type", "nope")), "Form", errors);
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).startsWith("Trigger node 'Form' form: "), errors.get(0));
    }

    private static void assertProblem(Map<String, Object> config, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> WorkflowFormSchema.parse(config));
        assertTrue(e.getMessage().contains(expected), "expected '" + expected + "' in: " + e.getMessage());
    }

    // ---- validation: text -----------------------------------------------------------------------------------------

    @Test
    void textFieldsAreTrimmedRequiredAndLengthCapped() {
        WorkflowFormSchema.Form form = form(
            field("key", "name", "type", "text", "required", true, "maxLength", 5),
            field("key", "note", "type", "text"));

        WorkflowFormSchema.Result ok = WorkflowFormSchema.validate(form, values("name", "  Asha  "));
        assertTrue(ok.ok());
        assertEquals("Asha", ok.values().get("name"));
        assertEquals("", ok.values().get("note"), "an absent optional text is an empty string, not missing");

        assertEquals("required", WorkflowFormSchema.validate(form, values("name", "   ")).errors().get("name"));
        assertEquals("required", WorkflowFormSchema.validate(form, values()).errors().get("name"));
        assertEquals("too_long", WorkflowFormSchema.validate(form, values("name", "Ashaaa")).errors().get("name"));
        assertEquals("invalid_type", WorkflowFormSchema.validate(form, values("name", 42)).errors().get("name"));
    }

    @Test
    void controlCharactersAreRefusedAndOnlyLongTextMayHoldNewlines() {
        WorkflowFormSchema.Form form = form(
            field("key", "one", "type", "text"), field("key", "many", "type", "long_text"));

        assertEquals("invalid_characters", WorkflowFormSchema.validate(form, values("one", "a\nb")).errors().get("one"));
        assertEquals("invalid_characters", WorkflowFormSchema.validate(form, values("one", "a\u0000b")).errors().get("one"));
        assertEquals("invalid_characters", WorkflowFormSchema.validate(form, values("many", "a\u0007b")).errors().get("many"));
        WorkflowFormSchema.Result ok = WorkflowFormSchema.validate(form, values("many", "line1\r\nline2\tx"));
        assertTrue(ok.ok(), ok.errors().toString());
        assertEquals("line1\nline2\tx", ok.values().get("many"));
    }

    @Test
    void maxLengthIsClampedToTheAbsoluteCeiling() {
        WorkflowFormSchema.Form form = form(
            field("key", "t", "type", "text", "maxLength", 999_999),
            field("key", "l", "type", "long_text", "maxLength", 999_999));
        assertEquals(WorkflowFormSchema.ABSOLUTE_TEXT_MAX, form.fields().get(0).maxLength());
        assertEquals(WorkflowFormSchema.ABSOLUTE_LONG_TEXT_MAX, form.fields().get(1).maxLength());
    }

    // ---- validation: number ----------------------------------------------------------------------------------------

    @Test
    void numbersAreParsedRangeCheckedAndTyped() {
        WorkflowFormSchema.Form form = form(field("key", "qty", "type", "number", "required", true, "min", 1, "max", 10));

        assertEquals(5L, WorkflowFormSchema.validate(form, values("qty", "5")).values().get("qty"));
        assertEquals(5L, WorkflowFormSchema.validate(form, values("qty", 5)).values().get("qty"));
        assertEquals(2.5d, WorkflowFormSchema.validate(form, values("qty", "2.5")).values().get("qty"));
        assertEquals(7L, WorkflowFormSchema.validate(form, values("qty", "7.0")).values().get("qty"));

        assertEquals("out_of_range", WorkflowFormSchema.validate(form, values("qty", 0)).errors().get("qty"));
        assertEquals("out_of_range", WorkflowFormSchema.validate(form, values("qty", "11")).errors().get("qty"));
        assertEquals("invalid_number", WorkflowFormSchema.validate(form, values("qty", "abc")).errors().get("qty"));
        assertEquals("invalid_number", WorkflowFormSchema.validate(form, values("qty", "NaN")).errors().get("qty"));
        assertEquals("invalid_number", WorkflowFormSchema.validate(form, values("qty", Double.POSITIVE_INFINITY)).errors().get("qty"));
        assertEquals("invalid_number", WorkflowFormSchema.validate(form, values("qty", "1".repeat(60))).errors().get("qty"));
        assertEquals("required", WorkflowFormSchema.validate(form, values()).errors().get("qty"));
        assertEquals("required", WorkflowFormSchema.validate(form, values("qty", "")).errors().get("qty"));
    }

    @Test
    void anOptionalNumberLeftBlankIsNull() {
        WorkflowFormSchema.Form form = form(field("key", "qty", "type", "number"));
        WorkflowFormSchema.Result r = WorkflowFormSchema.validate(form, values());
        assertTrue(r.ok());
        assertNull(r.values().get("qty"));
        assertTrue(r.values().containsKey("qty"));
    }

    // ---- validation: email / select / checkbox / date ---------------------------------------------------------------

    @Test
    void emailsMustLookLikeEmails() {
        WorkflowFormSchema.Form form = form(field("key", "mail", "type", "email", "required", true));

        assertEquals("a.b+tag@example.co.in",
            WorkflowFormSchema.validate(form, values("mail", " a.b+tag@example.co.in ")).values().get("mail"));
        for (String bad : List.of("plain", "a@b", "@example.com", "a b@example.com", "a@@example.com", "a@example.c")) {
            assertEquals("invalid_email", WorkflowFormSchema.validate(form, values("mail", bad)).errors().get("mail"), bad);
        }
        assertEquals("too_long",
            WorkflowFormSchema.validate(form, values("mail", "a".repeat(250) + "@x.com")).errors().get("mail"));
    }

    @Test
    void selectMustBeOneOfTheOptions() {
        WorkflowFormSchema.Form form = form(field("key", "topic", "type", "select", "required", true,
            "options", List.of("sales", "support")));

        assertEquals("sales", WorkflowFormSchema.validate(form, values("topic", "sales")).values().get("topic"));
        assertEquals("invalid_option", WorkflowFormSchema.validate(form, values("topic", "billing")).errors().get("topic"));
        assertEquals("invalid_option", WorkflowFormSchema.validate(form, values("topic", "SALES")).errors().get("topic"));
        assertEquals("required", WorkflowFormSchema.validate(form, values()).errors().get("topic"));
    }

    @Test
    void checkboxesAcceptBooleansAndCommonStringsAndEnforceRequiredConsent() {
        WorkflowFormSchema.Form optional = form(field("key", "news", "type", "checkbox"));
        assertEquals(Boolean.TRUE, WorkflowFormSchema.validate(optional, values("news", true)).values().get("news"));
        assertEquals(Boolean.TRUE, WorkflowFormSchema.validate(optional, values("news", "on")).values().get("news"));
        assertEquals(Boolean.FALSE, WorkflowFormSchema.validate(optional, values()).values().get("news"));
        assertEquals(Boolean.FALSE, WorkflowFormSchema.validate(optional, values("news", false)).values().get("news"));
        assertEquals("invalid_type", WorkflowFormSchema.validate(optional, values("news", "maybe")).errors().get("news"));
        assertEquals("invalid_type", WorkflowFormSchema.validate(optional, values("news", 7)).errors().get("news"));

        WorkflowFormSchema.Form consent = form(field("key", "agree", "type", "checkbox", "required", true));
        assertEquals("required", WorkflowFormSchema.validate(consent, values("agree", false)).errors().get("agree"));
        assertTrue(WorkflowFormSchema.validate(consent, values("agree", "true")).ok());
    }

    @Test
    void datesMustBeIsoCalendarDates() {
        WorkflowFormSchema.Form form = form(field("key", "when", "type", "date", "required", true));

        assertEquals("2026-10-05", WorkflowFormSchema.validate(form, values("when", "2026-10-05")).values().get("when"));
        assertEquals("invalid_date", WorkflowFormSchema.validate(form, values("when", "05/10/2026")).errors().get("when"));
        assertEquals("invalid_date", WorkflowFormSchema.validate(form, values("when", "2026-02-30")).errors().get("when"));
        assertEquals("invalid_date", WorkflowFormSchema.validate(form, values("when", "tomorrow")).errors().get("when"));
    }

    // ---- shape of the result ------------------------------------------------------------------------------------------

    @Test
    void unknownKeysAreDroppedAndEverySchemaFieldIsPresent() {
        WorkflowFormSchema.Form form = form(
            field("key", "name", "type", "text"), field("key", "agree", "type", "checkbox"));

        WorkflowFormSchema.Result r = WorkflowFormSchema.validate(form,
            values("name", "Asha", "isAdmin", true, "__proto__", "x", WorkflowFormSchema.HONEYPOT_FIELD, "bot"));

        assertTrue(r.ok());
        assertEquals(List.of("name", "agree"), new ArrayList<>(r.values().keySet()));
    }

    @Test
    void aNullSubmissionIsJustAllMissing() {
        WorkflowFormSchema.Form form = form(field("key", "name", "type", "text", "required", true));
        assertEquals("required", WorkflowFormSchema.validate(form, null).errors().get("name"));
    }

    @Test
    void theHoneypotNameCannotCollideWithAFieldKey() {
        // keys must start with a letter, the honeypot starts with an underscore
        assertFalse(WorkflowFormSchema.HONEYPOT_FIELD.matches("^[A-Za-z][A-Za-z0-9_]{0,63}$"));
    }
}
