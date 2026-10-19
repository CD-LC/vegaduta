package com.agenticai.core.guardrails.toolargs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolArgPolicySetTest {

    private static String policy(String rules) {
        return "{\"policies\":[{\"tool\":\"t\",\"rules\":" + rules + "}]}";
    }

    private static String messageOf(String json) {
        return assertThrows(IllegalArgumentException.class, () -> ToolArgPolicySet.parse(json)).getMessage();
    }

    @Test
    void emptyAndBlankDocumentsAreTheEmptySet() {
        assertTrue(ToolArgPolicySet.parse((String) null).isEmpty());
        assertTrue(ToolArgPolicySet.parse("").isEmpty());
        assertTrue(ToolArgPolicySet.parse("   ").isEmpty());
        assertTrue(ToolArgPolicySet.parse("null").isEmpty());
        assertTrue(ToolArgPolicySet.parse("{}").isEmpty());
        assertTrue(ToolArgPolicySet.parse("{\"policies\":[]}").isEmpty());
        assertTrue(ToolArgPolicySet.empty().isEmpty());
    }

    @Test
    void aValidDocumentRoundTripsThroughItsCanonicalForm() {
        String doc = "{\"policies\":[{\"tool\":\"fetch_web_page\",\"rules\":["
            + "{\"path\":\"url\",\"required\":true,\"urlHostIn\":[\"Example.com\",\"*.docs.example.org\"],\"maxLength\":2048},"
            + "{\"path\":\"mode\",\"enum\":[\"a\",2,true]},"
            + "{\"path\":\"limit\",\"type\":\"integer\",\"min\":1,\"max\":50},"
            + "{\"path\":\"file\",\"pathUnder\":[\"/workspace/\",\"reports\"]},"
            + "{\"path\":\"name\",\"pattern\":\"[a-z]+\",\"minLength\":1},"
            + "{\"path\":\"ids\",\"maxItems\":3},{\"path\":\"debug\",\"forbidden\":true}]},"
            + "{\"tool\":\"mcp__*\",\"denyUnlistedArgs\":true,\"rules\":[{\"path\":\"q\",\"type\":\"string\"}]}]}";
        ToolArgPolicySet set = ToolArgPolicySet.parse(doc);
        assertEquals(2, set.policies().size());
        assertEquals(7, set.policies().get(0).rules().size());
        assertTrue(set.policies().get(1).denyUnlistedArgs());

        String canonical = set.toJson();
        ToolArgPolicySet again = ToolArgPolicySet.parse(canonical);
        assertEquals(canonical, again.toJson());
        // Hosts are lower-cased and path prefixes normalised in the canonical form.
        assertTrue(canonical.contains("\"example.com\""), canonical);
        assertTrue(canonical.contains("\"/workspace\""), canonical);
        assertFalse(canonical.contains("Example.com"), canonical);
    }

    @Test
    void unknownKeysAreRejectedSoATypoNeverBecomesASilentNoOp() {
        assertTrue(messageOf("{\"policies\":[],\"extra\":1}").contains("extra"));
        assertTrue(messageOf("{\"policies\":[{\"tool\":\"t\",\"rulez\":[]}]}").contains("rulez"));
        String m = messageOf(policy("[{\"path\":\"a\",\"maxLenght\":5}]"));
        assertTrue(m.contains("maxLenght"), m);
        assertTrue(m.contains("maxLength"), m);
    }

    @Test
    void malformedDocumentsAreRejectedWithAPreciseReason() {
        assertTrue(messageOf("{not json").contains("not valid JSON"));
        assertTrue(messageOf("[]").contains("JSON object"));
        assertTrue(messageOf("{\"policies\":{}}").contains("array"));
        assertTrue(messageOf("{\"policies\":[1]}").contains("Policy #1"));
        assertTrue(messageOf("{\"policies\":[{\"rules\":[]}]}").contains("'tool'"));
        assertTrue(messageOf("{\"policies\":[{\"tool\":\"t\"}]}").contains("at least one rule"));
        assertTrue(messageOf("{\"policies\":[{\"tool\":\"t\",\"rules\":{}}]}").contains("array"));
        assertTrue(messageOf(policy("[1]")).contains("JSON object"));
        assertTrue(messageOf(policy("[{\"type\":\"string\"}]")).contains("'path'"));
    }

    @Test
    void ruleValidation() {
        assertTrue(messageOf(policy("[{\"path\":\"a\"}]")).contains("constrains nothing"));
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"type\":\"date\"}]")).contains("'type'"));
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"required\":true,\"forbidden\":true}]")).contains("cannot both"));
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"forbidden\":true,\"type\":\"string\"}]")).contains("forbidden"));
        assertTrue(messageOf(policy("[{\"path\":\"a[*]\",\"required\":true,\"type\":\"string\"}]")).contains("[*]"));
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"min\":5,\"max\":1}]")).contains("'min' is greater"));
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"minLength\":5,\"maxLength\":1}]")).contains("'minLength' is greater"));
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"maxLength\":-1}]")).contains("maxLength"));
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"maxLength\":\"5\"}]")).contains("maxLength"));
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"min\":\"1\"}]")).contains("'min' must be a number"));
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"enum\":[]}]")).contains("non-empty"));
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"enum\":[{}]}]")).contains("strings, numbers or booleans"));
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"required\":\"yes\"}]")).contains("true or false"));
    }

    @Test
    void invalidAndHostilePatternsAreRejectedAtWriteTime() {
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"pattern\":\"([\"}]")).contains("not a valid regular expression"));
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"pattern\":\"\"}]")).contains("non-empty"));
        String longPattern = "a".repeat(ToolArgPolicySet.MAX_PATTERN_LENGTH + 1);
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"pattern\":\"" + longPattern + "\"}]")).contains("limited to"));
        // Exactly at the cap is fine.
        ToolArgPolicySet.parse(policy("[{\"path\":\"a\",\"pattern\":\"" + "a".repeat(ToolArgPolicySet.MAX_PATTERN_LENGTH) + "\"}]"));
    }

    @Test
    void hostAndPathListsAreValidated() {
        assertTrue(messageOf(policy("[{\"path\":\"u\",\"urlHostIn\":[\"exa mple.com\"]}]")).contains("urlHostIn"));
        assertTrue(messageOf(policy("[{\"path\":\"u\",\"urlHostIn\":[\"*example.com\"]}]")).contains("urlHostIn"));
        assertTrue(messageOf(policy("[{\"path\":\"u\",\"urlHostIn\":[\"a..b\"]}]")).contains("urlHostIn"));
        assertTrue(messageOf(policy("[{\"path\":\"u\",\"urlHostIn\":[]}]")).contains("non-empty"));
        assertTrue(messageOf(policy("[{\"path\":\"u\",\"urlHostIn\":[\"\"]}]")).contains("non-empty"));
        assertTrue(messageOf(policy("[{\"path\":\"p\",\"pathUnder\":[\"../etc\"]}]")).contains("'..'"));
        assertTrue(messageOf(policy("[{\"path\":\"p\",\"pathUnder\":[\"a\\u0000b\"]}]")).contains("plain paths"));
    }

    @Test
    void policyLevelValidation() {
        assertTrue(messageOf("{\"policies\":[{\"tool\":\"bad name!\",\"rules\":[{\"path\":\"a\",\"type\":\"string\"}]}]}")
            .contains("'tool' may contain"));
        String longTool = "a".repeat(ToolArgPolicySet.MAX_TOOL_PATTERN_LENGTH + 1);
        assertTrue(messageOf("{\"policies\":[{\"tool\":\"" + longTool + "\",\"rules\":[{\"path\":\"a\",\"type\":\"string\"}]}]}")
            .contains("limited to"));
        assertTrue(messageOf("{\"policies\":[{\"tool\":\"t\",\"denyUnlistedArgs\":\"y\",\"rules\":[]}]}").contains("true or false"));
        // denyUnlistedArgs alone (no rules) is a valid policy: "this tool takes no arguments".
        assertEquals(1, ToolArgPolicySet.parse("{\"policies\":[{\"tool\":\"t\",\"denyUnlistedArgs\":true}]}").policies().size());
    }

    @Test
    void sizeCaps() {
        StringBuilder many = new StringBuilder("{\"policies\":[");
        for (int i = 0; i <= ToolArgPolicySet.MAX_POLICIES; i++) {
            many.append(i == 0 ? "" : ",").append("{\"tool\":\"t").append(i).append("\",\"rules\":[{\"path\":\"a\",\"type\":\"string\"}]}");
        }
        many.append("]}");
        assertTrue(messageOf(many.toString()).contains("At most " + ToolArgPolicySet.MAX_POLICIES));

        StringBuilder rules = new StringBuilder("[");
        for (int i = 0; i <= ToolArgPolicySet.MAX_RULES_PER_POLICY; i++) {
            rules.append(i == 0 ? "" : ",").append("{\"path\":\"a").append(i).append("\",\"type\":\"string\"}");
        }
        rules.append("]");
        assertTrue(messageOf(policy(rules.toString())).contains("at most " + ToolArgPolicySet.MAX_RULES_PER_POLICY));

        StringBuilder enumValues = new StringBuilder("[");
        for (int i = 0; i <= ToolArgPolicySet.MAX_ENUM_VALUES; i++) {
            enumValues.append(i == 0 ? "" : ",").append(i);
        }
        enumValues.append("]");
        assertTrue(messageOf(policy("[{\"path\":\"a\",\"enum\":" + enumValues + "}]")).contains("limited to"));

        String huge = "{\"policies\":[],\"pad\":\"" + "x".repeat(ToolArgPolicySet.MAX_DOCUMENT_CHARS) + "\"}";
        assertTrue(messageOf(huge).contains("limited to"));
    }

    @Test
    void aPathStepBudgetAndAFieldNameGrammarKeepPathsSimple() {
        assertTrue(messageOf(policy("[{\"path\":\"a..b\",\"type\":\"string\"}]")).contains("Invalid 'path'"));
        assertTrue(messageOf(policy("[{\"path\":\"a.\",\"type\":\"string\"}]")).contains("Invalid 'path'"));
        assertTrue(messageOf(policy("[{\"path\":\"a[x]\",\"type\":\"string\"}]")).contains("Invalid 'path'"));
        assertTrue(messageOf(policy("[{\"path\":\"a b\",\"type\":\"string\"}]")).contains("Invalid 'path'"));
        assertTrue(messageOf(policy("[{\"path\":\"\",\"type\":\"string\"}]")).contains("'path'"));
    }
}
