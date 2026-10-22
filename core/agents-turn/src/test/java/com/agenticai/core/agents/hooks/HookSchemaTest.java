package com.agenticai.core.agents.hooks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The save-time contract: what a hook list may contain, and that everything else is a precise 400.
 */
class HookSchemaTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<HookDefinition> parse(String text) {
        return HookSchema.parse(json(text), null);
    }

    private static void assertRejects(String text, String messageFragment) {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> parse(text), text);
        assertTrue(
                e.getMessage().contains(messageFragment),
                "expected '" + messageFragment + "' in: " + e.getMessage());
    }

    @Test
    void parsesEveryActionAtAPointWhereItIsAllowed() {
        List<HookDefinition> hooks =
                parse(
                        """
            [
              {"id":"no-delete","point":"BEFORE_TOOL","match":{"tool":"delete_*"},"action":"block","params":{"reason":"Deletes are not allowed"}},
              {"id":"ask-egress","point":"BEFORE_TOOL","match":{"sensitivity":"EGRESS"},"action":"require_approval"},
              {"id":"scrub-args","point":"BEFORE_TOOL","action":"redact","params":{"patterns":["email","phone"]}},
              {"id":"cap-run","point":"BEFORE_TOOL","match":{"tool":"search_*"},"action":"limit","params":{"maxPerRun":3,"maxPerMinute":30}},
              {"id":"note","point":"AFTER_TOOL","action":"append_context","params":{"note":"Verify before acting."}},
              {"id":"vet","point":"BEFORE_MODEL","action":"webhook","params":{"url":"https://hooks.example.com/vet","includeData":true},"timeoutMs":1500},
              {"id":"audit","point":"ON_RUN_END","action":"log","params":{"label":"run finished"}}
            ]""");

        assertEquals(7, hooks.size());
        HookDefinition block = hooks.get(0);
        assertEquals(HookActionType.BLOCK, block.action());
        assertEquals("delete_*", block.match().tool());
        assertEquals("Deletes are not allowed", block.str("reason"));
        assertEquals("EGRESS", hooks.get(1).match().sensitivity());
        assertEquals(List.of("email", "phone"), hooks.get(2).strList("patterns"));
        assertEquals(3, hooks.get(3).intParam("maxPerRun"));
        assertEquals(30, hooks.get(3).intParam("maxPerMinute"));
        HookDefinition vet = hooks.get(5);
        assertTrue(vet.bool("includeData"));
        assertEquals(1500, vet.timeoutMs());
        assertEquals(
                FailMode.CLOSED,
                vet.failMode(),
                "a webhook before the model defaults to fail-closed");
        assertTrue(hooks.get(6).enabled());
    }

    @Test
    void failModeDefaultsToClosedBeforeAndOpenAfter() {
        List<HookDefinition> hooks =
                parse(
                        """
            [
              {"id":"a","point":"BEFORE_TOOL","action":"webhook","params":{"url":"https://hooks.example.com/a"}},
              {"id":"b","point":"AFTER_TOOL","action":"webhook","params":{"url":"https://hooks.example.com/b"}},
              {"id":"c","point":"AFTER_TOOL","action":"webhook","params":{"url":"https://hooks.example.com/c"},"failMode":"closed"}
            ]""");

        assertEquals(FailMode.CLOSED, hooks.get(0).failMode());
        assertEquals(FailMode.OPEN, hooks.get(1).failMode());
        assertEquals(FailMode.CLOSED, hooks.get(2).failMode());
        assertEquals(HookSchema.DEFAULT_TIMEOUT_MS, hooks.get(0).timeoutMs());
    }

    @Test
    void missingOrNullMeansNoHooks() {
        assertTrue(HookSchema.parse(null, null).isEmpty());
        assertTrue(HookSchema.parse(json("null"), null).isEmpty());
        assertTrue(parse("[]").isEmpty());
    }

    @Test
    void roundTripsThroughItsOwnSerialisation() {
        List<HookDefinition> hooks =
                parse(
                        """
            [
              {"id":"x","point":"BEFORE_TOOL","match":{"tool":"a_*","trust":"UNTRUSTED_EXTERNAL","irreversible":true},"action":"block"},
              {"id":"y","enabled":false,"point":"AFTER_MODEL","action":"redact","params":{"regex":["secret-\\\\d+"],"replacement":"[X]"}},
              {"id":"z","point":"AFTER_TOOL","action":"webhook","params":{"url":"https://hooks.example.com/z"},"failMode":"open","timeoutMs":900}
            ]""");

        List<HookDefinition> again = HookSchema.parseStored(HookSchema.toJson(hooks));

        assertEquals(hooks, again);
        assertFalse(again.get(1).enabled());
    }

    @Test
    void rejectsEverythingMalformedWithAFieldLevelMessage() {
        assertRejects("{}", "hooks must be an array");
        assertRejects("[1]", "each hook must be an object");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"log\",\"bogus\":1}]",
                "unknown field 'bogus'");
        assertRejects("[{\"point\":\"BEFORE_TOOL\",\"action\":\"log\"}]", "id: required");
        assertRejects(
                "[{\"id\":\"Bad ID\",\"point\":\"BEFORE_TOOL\",\"action\":\"log\"}]", "a-z, 0-9");
        assertRejects("[{\"id\":\"a\",\"point\":\"SOMETIMES\",\"action\":\"log\"}]", ".point");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"explode\"}]", ".action");
        assertRejects(
                "[{\"id\":\"a\",\"enabled\":\"yes\",\"point\":\"BEFORE_TOOL\",\"action\":\"log\"}]",
                ".enabled");
    }

    @Test
    void rejectsAnActionThatMakesNoSenseAtItsPoint() {
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"AFTER_MODEL\",\"action\":\"limit\",\"params\":{\"maxPerMinute\":5}}]",
                "cannot be used at AFTER_MODEL");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"AFTER_TOOL\",\"action\":\"require_approval\"}]",
                "cannot be used at AFTER_TOOL");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"ON_ERROR\",\"action\":\"block\"}]",
                "cannot be used at ON_ERROR");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_MODEL\",\"action\":\"redact\",\"params\":{\"patterns\":[\"email\"]}}]",
                "cannot be used at BEFORE_MODEL");
    }

    @Test
    void rejectsDuplicateIdsAndTooManyHooks() {
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"ON_RUN_END\",\"action\":\"log\"},{\"id\":\"a\",\"point\":\"ON_RUN_END\",\"action\":\"log\"}]",
                "duplicate id 'a'");

        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i <= HookSchema.MAX_HOOKS; i++) {
            many.append(i == 0 ? "" : ",")
                    .append("{\"id\":\"h")
                    .append(i)
                    .append("\",\"point\":\"ON_RUN_END\",\"action\":\"log\"}");
        }
        many.append("]");
        assertRejects(many.toString(), "at most " + HookSchema.MAX_HOOKS + " hooks");
    }

    @Test
    void acceptsExactlyTheMaximumNumberOfHooks() {
        StringBuilder max = new StringBuilder("[");
        for (int i = 0; i < HookSchema.MAX_HOOKS; i++) {
            max.append(i == 0 ? "" : ",")
                    .append("{\"id\":\"h")
                    .append(i)
                    .append("\",\"point\":\"ON_RUN_END\",\"action\":\"log\"}");
        }
        max.append("]");

        assertEquals(HookSchema.MAX_HOOKS, parse(max.toString()).size());
    }

    @Test
    void rejectsAnOversizedHookListEvenWhenEveryFieldIsWithinItsOwnCap() {
        String longPath = "a".repeat(1_900);
        StringBuilder big = new StringBuilder("[");
        for (int i = 0; i < HookSchema.MAX_HOOKS; i++) {
            big.append(i == 0 ? "" : ",")
                    .append("{\"id\":\"h")
                    .append(i)
                    .append(
                            "\",\"point\":\"ON_RUN_END\",\"action\":\"webhook\",\"params\":{\"url\":\"https://hooks.example.com/")
                    .append(longPath)
                    .append("\"}}");
        }
        big.append("]");

        assertRejects(big.toString(), "too large");
    }

    @Test
    void validatesParamsPerAction() {
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"redact\",\"params\":{}}]",
                "at least one built-in pattern");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"redact\",\"params\":{\"patterns\":[\"nope\"]}}]",
                "unknown pattern 'nope'");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"redact\",\"params\":{\"regex\":[\"a*\"]}}]",
                "empty string");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"redact\",\"params\":{\"regex\":[\"(\"]}}]",
                "not a valid regular expression");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"redact\",\"params\":{\"regex\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}}]",
                "at most 5");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_MODEL\",\"action\":\"limit\",\"params\":{\"maxPerRun\":5}}]",
                "per minute only");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"limit\",\"params\":{}}]",
                "set maxPerMinute");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"limit\",\"params\":{\"maxPerRun\":0}}]",
                "between 1 and");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"AFTER_TOOL\",\"action\":\"append_context\",\"params\":{}}]",
                "note: required");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"AFTER_TOOL\",\"action\":\"append_context\",\"params\":{\"note\":\"two\\nlines\"}}]",
                "single line");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"block\",\"params\":{\"reason\":\""
                        + "r".repeat(301)
                        + "\"}}]",
                "at most 300");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"block\",\"params\":{\"why\":\"x\"}}]",
                "unknown field 'why'");
    }

    @Test
    void validatesWebhookShapeAndTheInjectedAllowListCheck() {
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"webhook\",\"params\":{}}]",
                "url: required");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"webhook\",\"params\":{\"url\":\"http://hooks.example.com/x\"}}]",
                "https");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"webhook\",\"params\":{\"url\":\"https://user:pw@hooks.example.com/x\"}}]",
                "credentials");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"log\",\"failMode\":\"open\"}]",
                "only webhook hooks");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"webhook\",\"params\":{\"url\":\"https://hooks.example.com/x\"},\"timeoutMs\":50}]",
                "between");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"webhook\",\"params\":{\"url\":\"https://hooks.example.com/x\"},\"failMode\":\"maybe\"}]",
                "'open' or 'closed'");

        IllegalArgumentException refused =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                HookSchema.parse(
                                        json(
                                                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"webhook\",\"params\":{\"url\":\"https://evil.example.net/x\"}}]"),
                                        url -> "the host is not on the webhook allow-list"));
        assertTrue(refused.getMessage().contains("allow-list"), refused.getMessage());

        assertEquals(
                1,
                HookSchema.parse(
                                json(
                                        "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"action\":\"webhook\",\"params\":{\"url\":\"https://hooks.example.com/x\"}}]"),
                                url -> null)
                        .size());
    }

    @Test
    void validatesMatchers() {
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_MODEL\",\"match\":{\"tool\":\"x\"},\"action\":\"log\"}]",
                "only applies to tool hooks");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"match\":{\"tool\":\"has space\"},\"action\":\"log\"}]",
                "tool name glob");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"match\":{\"sensitivity\":\"SPICY\"},\"action\":\"log\"}]",
                "is not one of");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"match\":{\"irreversible\":\"yes\"},\"action\":\"log\"}]",
                "true or false");
        assertRejects(
                "[{\"id\":\"a\",\"point\":\"BEFORE_TOOL\",\"match\":{\"colour\":\"red\"},\"action\":\"log\"}]",
                "unknown field 'colour'");

        List<HookDefinition> ok =
                parse(
                        """
            [{"id":"a","point":"BEFORE_TOOL","match":{"tool":"mcp__github__*","trust":"untrusted_external","sensitivity":"UNCLASSIFIED"},"action":"log"},
             {"id":"b","point":"ON_ERROR","match":{"tool":"*"},"action":"log"},
             {"id":"c","point":"BEFORE_TOOL","match":{},"action":"log"}]""");
        assertEquals(
                "UNTRUSTED_EXTERNAL",
                ok.get(0).match().trust(),
                "enum values are normalised to upper case");
        assertEquals(HookMatch.UNCLASSIFIED, ok.get(0).match().sensitivity());
        assertTrue(ok.get(2).match().isAny());
    }

    @Test
    void storedGarbageIsReportedNotSwallowed() {
        assertThrows(IllegalArgumentException.class, () -> HookSchema.parseStored("{not json"));
        assertTrue(HookSchema.parseStored("  ").isEmpty());
        assertTrue(HookSchema.parseStored(null).isEmpty());
    }
}
