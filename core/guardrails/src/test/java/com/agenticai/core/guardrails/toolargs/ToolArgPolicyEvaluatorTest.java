package com.agenticai.core.guardrails.toolargs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolArgPolicyEvaluatorTest {

    private static ToolArgPolicyEvaluator evaluator(String rulesJson) {
        return evaluatorFor("t", rulesJson);
    }

    private static ToolArgPolicyEvaluator evaluatorFor(String tool, String rulesJson) {
        return new ToolArgPolicyEvaluator(ToolArgPolicySet.parse(
            "{\"policies\":[{\"tool\":\"" + tool + "\",\"rules\":" + rulesJson + "}]}"));
    }

    private static ToolArgPolicyEvaluator.Result run(String rulesJson, String args) {
        return evaluator(rulesJson).evaluate("t", args);
    }

    private static void assertAllowed(ToolArgPolicyEvaluator.Result r) {
        assertTrue(r.allowed(), "expected allowed but: " + r.reason());
        assertTrue(r.violations().isEmpty());
        assertEquals("", r.reason());
    }

    private static void assertDenied(ToolArgPolicyEvaluator.Result r, String rule) {
        assertFalse(r.allowed(), "expected a violation");
        assertTrue(r.violations().stream().anyMatch(v -> v.rule().equals(rule)),
            "expected a '" + rule + "' violation but got: " + r.violations());
    }

    // ------------------------------------------------------------------ scoping

    @Test
    void aToolNoPolicyMatchesIsAllowedWithoutEvenParsingTheArguments() {
        ToolArgPolicyEvaluator ev = evaluatorFor("other_tool", "[{\"path\":\"x\",\"type\":\"string\"}]");
        assertAllowed(ev.evaluate("t", "this is { not json"));
        assertFalse(ev.appliesTo("t"));
        assertTrue(ev.appliesTo("other_tool"));
    }

    @Test
    void anEmptyPolicySetAllowsEverything() {
        ToolArgPolicyEvaluator ev = new ToolArgPolicyEvaluator(ToolArgPolicySet.empty());
        assertAllowed(ev.evaluate("anything", "{\"a\":1}"));
        assertFalse(ev.appliesTo("anything"));
        assertFalse(new ToolArgPolicyEvaluator(null).appliesTo("anything"));
    }

    @Test
    void globsMatchAndEveryMatchingPolicyMustPass() {
        ToolArgPolicyEvaluator ev = new ToolArgPolicyEvaluator(ToolArgPolicySet.parse("{\"policies\":["
            + "{\"tool\":\"mcp__files__*\",\"rules\":[{\"path\":\"path\",\"pathUnder\":[\"/workspace\"]}]},"
            + "{\"tool\":\"mcp__files__read\",\"rules\":[{\"path\":\"limit\",\"type\":\"integer\",\"max\":10}]}]}"));
        assertAllowed(ev.evaluate("mcp__files__read", "{\"path\":\"/workspace/a\",\"limit\":5}"));
        assertDenied(ev.evaluate("mcp__files__read", "{\"path\":\"/etc/passwd\",\"limit\":5}"), "pathUnder");
        assertDenied(ev.evaluate("mcp__files__read", "{\"path\":\"/workspace/a\",\"limit\":50}"), "max");
        // The glob-only tool is not subject to the second policy.
        assertAllowed(ev.evaluate("mcp__files__write", "{\"path\":\"/workspace/a\",\"limit\":50}"));
        // Both policies fail -> both reasons are reported.
        ToolArgPolicyEvaluator.Result both = ev.evaluate("mcp__files__read", "{\"path\":\"/etc\",\"limit\":50}");
        assertEquals(2, both.violations().size());
    }

    // ------------------------------------------------------------------ type / enum

    @Test
    void typeConstraintsAreStrict() {
        String rule = "[{\"path\":\"v\",\"type\":\"%s\"}]";
        assertAllowed(run(String.format(rule, "string"), "{\"v\":\"x\"}"));
        assertDenied(run(String.format(rule, "string"), "{\"v\":1}"), "type");
        assertAllowed(run(String.format(rule, "integer"), "{\"v\":3}"));
        assertAllowed(run(String.format(rule, "integer"), "{\"v\":3.0}"));
        assertDenied(run(String.format(rule, "integer"), "{\"v\":3.5}"), "type");
        assertDenied(run(String.format(rule, "integer"), "{\"v\":\"3\"}"), "type");
        assertAllowed(run(String.format(rule, "number"), "{\"v\":3.5}"));
        assertAllowed(run(String.format(rule, "boolean"), "{\"v\":true}"));
        assertDenied(run(String.format(rule, "boolean"), "{\"v\":\"true\"}"), "type");
        assertAllowed(run(String.format(rule, "array"), "{\"v\":[1]}"));
        assertAllowed(run(String.format(rule, "object"), "{\"v\":{}}"));
        assertDenied(run(String.format(rule, "object"), "{\"v\":[]}"), "type");
    }

    @Test
    void enumMatchesStringsAndNumbersByValue() {
        String rule = "[{\"path\":\"mode\",\"enum\":[\"fast\",\"slow\",2,true]}]";
        assertAllowed(run(rule, "{\"mode\":\"fast\"}"));
        assertAllowed(run(rule, "{\"mode\":2}"));
        assertAllowed(run(rule, "{\"mode\":2.0}"));
        assertAllowed(run(rule, "{\"mode\":true}"));
        assertDenied(run(rule, "{\"mode\":\"medium\"}"), "enum");
        assertDenied(run(rule, "{\"mode\":\"2\"}"), "enum");
    }

    @Test
    void anAbsentOptionalArgumentIsFineButARequiredOneIsNot() {
        String optional = "[{\"path\":\"mode\",\"enum\":[\"a\"]}]";
        String required = "[{\"path\":\"mode\",\"enum\":[\"a\"],\"required\":true}]";
        assertAllowed(run(optional, "{}"));
        assertAllowed(run(optional, ""));
        assertAllowed(run(optional, null));
        assertDenied(run(required, "{}"), "required");
        assertDenied(run(required, null), "required");
        // An explicit null counts as not provided.
        assertAllowed(run(optional, "{\"mode\":null}"));
        assertDenied(run(required, "{\"mode\":null}"), "required");
    }

    @Test
    void forbiddenArgumentsMustBeAbsent() {
        String rule = "[{\"path\":\"debug\",\"forbidden\":true}]";
        assertAllowed(run(rule, "{\"x\":1}"));
        assertAllowed(run(rule, "{\"debug\":null}"));
        assertDenied(run(rule, "{\"debug\":false}"), "forbidden");
    }

    // ------------------------------------------------------------------ lengths / ranges

    @Test
    void lengthBounds() {
        String rule = "[{\"path\":\"name\",\"minLength\":2,\"maxLength\":4}]";
        assertAllowed(run(rule, "{\"name\":\"abc\"}"));
        assertDenied(run(rule, "{\"name\":\"a\"}"), "minLength");
        assertDenied(run(rule, "{\"name\":\"abcde\"}"), "maxLength");
        // A scalar of another type is measured by its text, so changing the JSON type cannot dodge the bound.
        assertDenied(run(rule, "{\"name\":123456}"), "maxLength");
        assertDenied(run(rule, "{\"name\":{\"a\":1}}"), "text");
        assertDenied(run(rule, "{\"name\":[\"a\"]}"), "text");
    }

    @Test
    void numericRangeIncludingNumbersSmuggledAsStrings() {
        String rule = "[{\"path\":\"limit\",\"min\":1,\"max\":10.5}]";
        assertAllowed(run(rule, "{\"limit\":1}"));
        assertAllowed(run(rule, "{\"limit\":10.5}"));
        assertDenied(run(rule, "{\"limit\":0}"), "min");
        assertDenied(run(rule, "{\"limit\":10.6}"), "max");
        assertDenied(run(rule, "{\"limit\":\"9999\"}"), "max");
        assertAllowed(run(rule, "{\"limit\":\" 5 \"}"));
        assertDenied(run(rule, "{\"limit\":\"lots\"}"), "number");
        assertDenied(run(rule, "{\"limit\":true}"), "number");
        assertDenied(run(rule, "{\"limit\":[3]}"), "number");
    }

    @Test
    void maxItemsBoundsAnArray() {
        String rule = "[{\"path\":\"ids\",\"maxItems\":2}]";
        assertAllowed(run(rule, "{\"ids\":[1,2]}"));
        assertDenied(run(rule, "{\"ids\":[1,2,3]}"), "maxItems");
        assertDenied(run(rule, "{\"ids\":\"x\"}"), "maxItems");
    }

    // ------------------------------------------------------------------ pattern

    @Test
    void patternIsAnAllowListMatchedAgainstTheWholeValue() {
        String rule = "[{\"path\":\"name\",\"pattern\":\"[a-z0-9_-]{1,10}\"}]";
        assertAllowed(run(rule, "{\"name\":\"my-tool_1\"}"));
        assertDenied(run(rule, "{\"name\":\"my tool\"}"), "pattern");
        // Whole-value match: a valid prefix with a hostile tail is refused, not "found".
        assertDenied(run(rule, "{\"name\":\"ok; rm -rf /\"}"), "pattern");
        assertDenied(run(rule, "{\"name\":\"0123456789a\"}"), "pattern");
    }

    @Test
    void aCatastrophicPatternOnAHostileValueIsRefusedNotHung() {
        String rule = "[{\"path\":\"v\",\"pattern\":\"(a+)+b\"}]";
        String hostile = "{\"v\":\"" + "a".repeat(60) + "\"}";
        long start = System.nanoTime();
        ToolArgPolicyEvaluator.Result r = run(rule, hostile);
        long millis = (System.nanoTime() - start) / 1_000_000L;
        // Refused either way: "cannot be verified in budget" (backtracking engine) or "does not match" (an
        // engine that memoises this shape) - what must never happen is the call being allowed or hanging.
        assertDenied(r, "pattern");
        assertTrue(millis < 5_000, "bounded evaluation took " + millis + " ms");
    }

    @Test
    void aValueTooLongForPatternCheckingIsRefused() {
        String rule = "[{\"path\":\"v\",\"pattern\":\"a*\"}]";
        String big = "{\"v\":\"" + "a".repeat(ToolArgPolicyEvaluator.MAX_PATTERN_INPUT_CHARS + 1) + "\"}";
        ToolArgPolicyEvaluator.Result r = run(rule, big);
        assertDenied(r, "pattern");
        assertTrue(r.reason().contains("too long"), r.reason());
        // At the limit it is still evaluated.
        assertAllowed(run(rule, "{\"v\":\"" + "a".repeat(ToolArgPolicyEvaluator.MAX_PATTERN_INPUT_CHARS) + "\"}"));
    }

    // ------------------------------------------------------------------ url host

    @Test
    void urlHostAllowList() {
        String rule = "[{\"path\":\"url\",\"urlHostIn\":[\"example.com\",\"*.docs.example.org\"]}]";
        assertAllowed(run(rule, "{\"url\":\"https://example.com/a?b=c\"}"));
        assertAllowed(run(rule, "{\"url\":\"http://EXAMPLE.com:8080/\"}"));
        assertAllowed(run(rule, "{\"url\":\"https://example.com./x\"}"));
        assertAllowed(run(rule, "{\"url\":\"https://v1.docs.example.org/x\"}"));
        // The wildcard is subdomains only; the apex needs its own entry.
        assertDenied(run(rule, "{\"url\":\"https://docs.example.org/x\"}"), "urlHostIn");
        // Look-alikes and URL tricks.
        assertDenied(run(rule, "{\"url\":\"https://example.com.evil.com/\"}"), "urlHostIn");
        assertDenied(run(rule, "{\"url\":\"https://evilexample.com/\"}"), "urlHostIn");
        assertDenied(run(rule, "{\"url\":\"https://example.com@evil.com/\"}"), "urlHostIn");
        assertDenied(run(rule, "{\"url\":\"https://example.com\\\\@evil.com/\"}"), "urlHostIn");
        assertDenied(run(rule, "{\"url\":\"ftp://example.com/\"}"), "urlHostIn");
        assertDenied(run(rule, "{\"url\":\"example.com/path\"}"), "urlHostIn");
        assertDenied(run(rule, "{\"url\":\"not a url at all\"}"), "urlHostIn");
        assertDenied(run(rule, "{\"url\":\"https:///nohost\"}"), "urlHostIn");
        assertDenied(run(rule, "{\"url\":[\"https://example.com\"]}"), "text");
    }

    // ------------------------------------------------------------------ path under

    @Test
    void pathUnderIsLexicalAndSegmentWise() {
        String rule = "[{\"path\":\"file\",\"pathUnder\":[\"/workspace\",\"reports\"]}]";
        assertAllowed(run(rule, "{\"file\":\"/workspace\"}"));
        assertAllowed(run(rule, "{\"file\":\"/workspace/a/b.txt\"}"));
        assertAllowed(run(rule, "{\"file\":\"/workspace/./a//b.txt\"}"));
        assertAllowed(run(rule, "{\"file\":\"/workspace/a/../b.txt\"}"));
        assertAllowed(run(rule, "{\"file\":\"reports/q3.csv\"}"));
        assertAllowed(run(rule, "{\"file\":\"\\\\workspace\\\\a.txt\"}"));
        // Escapes and siblings.
        assertDenied(run(rule, "{\"file\":\"/workspace/../etc/passwd\"}"), "pathUnder");
        assertDenied(run(rule, "{\"file\":\"/workspace2/a\"}"), "pathUnder");
        assertDenied(run(rule, "{\"file\":\"/etc/passwd\"}"), "pathUnder");
        assertDenied(run(rule, "{\"file\":\"reports/../../etc/passwd\"}"), "pathUnder");
        assertDenied(run(rule, "{\"file\":\"../reports/x\"}"), "pathUnder");
        assertDenied(run(rule, "{\"file\":\"/reports/x\"}"), "pathUnder");
        assertDenied(run(rule, "{\"file\":\"/workspace/a\\u0000.txt\"}"), "pathUnder");
    }

    @Test
    void pathUnderUnderAWindowsDrive() {
        String rule = "[{\"path\":\"file\",\"pathUnder\":[\"C:/work\"]}]";
        assertAllowed(run(rule, "{\"file\":\"c:\\\\Work\\\\x\"}".replace("Work", "work")));
        assertDenied(run(rule, "{\"file\":\"D:\\\\work\\\\x\"}"), "pathUnder");
    }

    // ------------------------------------------------------------------ nesting

    @Test
    void nestedObjectsAndArrays() {
        String rules = "[{\"path\":\"opts.retries\",\"type\":\"integer\",\"max\":3},"
            + "{\"path\":\"items[*].id\",\"type\":\"string\",\"maxLength\":3},"
            + "{\"path\":\"first[0]\",\"enum\":[\"a\"]},"
            + "{\"path\":\"deep.a.b.c\",\"required\":true,\"type\":\"boolean\"}]";
        String ok = "{\"opts\":{\"retries\":2},\"items\":[{\"id\":\"a\"},{\"id\":\"bcd\"}],\"first\":[\"a\",\"zzz\"],"
            + "\"deep\":{\"a\":{\"b\":{\"c\":true}}}}";
        assertAllowed(run(rules, ok));

        ToolArgPolicyEvaluator.Result bad = run(rules,
            "{\"opts\":{\"retries\":9},\"items\":[{\"id\":\"a\"},{\"id\":\"toolong\"},{\"id\":7}],\"first\":[\"b\"],\"deep\":{}}");
        assertFalse(bad.allowed());
        assertTrue(bad.violations().stream().anyMatch(v -> v.path().equals("opts.retries") && v.rule().equals("max")));
        assertTrue(bad.violations().stream().anyMatch(v -> v.path().equals("items[1].id") && v.rule().equals("maxLength")));
        assertTrue(bad.violations().stream().anyMatch(v -> v.path().equals("items[2].id") && v.rule().equals("type")));
        assertTrue(bad.violations().stream().anyMatch(v -> v.path().equals("first[0]") && v.rule().equals("enum")));
        assertTrue(bad.violations().stream().anyMatch(v -> v.path().equals("deep.a.b.c") && v.rule().equals("required")));
    }

    @Test
    void aWildcardOverTooManyElementsCannotBeVerified() {
        StringBuilder sb = new StringBuilder("{\"ids\":[");
        for (int i = 0; i < ToolArgPathSpec.MAX_FAN_OUT + 5; i++) {
            sb.append(i == 0 ? "" : ",").append("\"a\"");
        }
        sb.append("]}");
        assertDenied(run("[{\"path\":\"ids[*]\",\"type\":\"string\"}]", sb.toString()), "fanout");
    }

    @Test
    void aPathThroughAWrongShapeIsSimplyAbsent() {
        String rule = "[{\"path\":\"opts.retries\",\"type\":\"integer\"}]";
        assertAllowed(run(rule, "{\"opts\":\"a string, not an object\"}"));
        assertAllowed(run(rule, "{\"opts\":[1,2]}"));
    }

    // ------------------------------------------------------------------ unlisted args

    @Test
    void denyUnlistedArgsRefusesAnythingNotNamedByARule() {
        ToolArgPolicyEvaluator ev = new ToolArgPolicyEvaluator(ToolArgPolicySet.parse(
            "{\"policies\":[{\"tool\":\"t\",\"denyUnlistedArgs\":true,\"rules\":["
                + "{\"path\":\"query\",\"type\":\"string\"},{\"path\":\"opts.n\",\"type\":\"integer\"}]}]}"));
        assertAllowed(ev.evaluate("t", "{\"query\":\"x\",\"opts\":{\"n\":1}}"));
        assertAllowed(ev.evaluate("t", "{}"));
        ToolArgPolicyEvaluator.Result r = ev.evaluate("t", "{\"query\":\"x\",\"shell\":\"rm -rf /\"}");
        assertDenied(r, "unlisted");
        assertTrue(r.reason().contains("'shell'"), r.reason());
        // The value is never echoed.
        assertFalse(r.reason().contains("rm -rf"), r.reason());
    }

    // ------------------------------------------------------------------ unreadable input

    @Test
    void unreadableArgumentsAreRefusedWhenAPolicyGovernsTheTool() {
        String rule = "[{\"path\":\"v\",\"type\":\"string\"}]";
        assertDenied(run(rule, "{not json"), "json");
        assertDenied(run(rule, "[1,2,3]"), "json");
        assertDenied(run(rule, "\"just a string\""), "json");
        assertDenied(run(rule, "42"), "json");
        // Shapes where two parsers can disagree about what was said.
        assertDenied(run(rule, "{\"v\":\"good\",\"v\":\"evil\"}"), "json");
        assertDenied(run(rule, "{\"v\":\"good\"} trailing text"), "json");
    }

    @Test
    void oversizedArgumentsAreRefused() {
        String rule = "[{\"path\":\"v\",\"type\":\"string\"}]";
        String big = "{\"v\":\"" + "x".repeat(ToolArgPolicyEvaluator.MAX_INPUT_CHARS) + "\"}";
        ToolArgPolicyEvaluator.Result r = run(rule, big);
        assertDenied(r, "size");
        assertTrue(r.reason().contains("too large"), r.reason());
    }

    // ------------------------------------------------------------------ messages

    @Test
    void messagesNameTheArgumentAndConstraintButNeverEchoTheValue() {
        ToolArgPolicyEvaluator ev = evaluator("[{\"path\":\"mode\",\"enum\":[\"a\",\"b\"]},"
            + "{\"path\":\"url\",\"urlHostIn\":[\"example.com\"]},{\"path\":\"n\",\"max\":3}]");
        ToolArgPolicyEvaluator.Result r = ev.evaluate("t",
            "{\"mode\":\"S3CRET-MODE\",\"url\":\"https://S3CRET-HOST.evil/\",\"n\":987654}");
        assertFalse(r.allowed());
        assertFalse(r.reason().contains("S3CRET"), r.reason());
        assertFalse(r.reason().contains("987654"), r.reason());
        assertTrue(r.reason().contains("'mode' must be one of: a, b"), r.reason());
        assertTrue(r.reason().contains("example.com"), r.reason());
        assertTrue(r.reason().contains("'n' must be at most 3"), r.reason());
    }

    @Test
    void violationsAreCapped() {
        StringBuilder rules = new StringBuilder("[");
        StringBuilder args = new StringBuilder("{");
        for (int i = 0; i < 25; i++) {
            rules.append(i == 0 ? "" : ",").append("{\"path\":\"a").append(i).append("\",\"type\":\"string\"}");
            args.append(i == 0 ? "" : ",").append("\"a").append(i).append("\":1");
        }
        rules.append("]");
        args.append("}");
        // 25 rules is within the per-policy cap of 30.
        ToolArgPolicyEvaluator.Result r = run(rules.toString(), args.toString());
        assertEquals(ToolArgPolicyEvaluator.MAX_VIOLATIONS, r.violations().size());
    }

    @Test
    void hostMatching() {
        assertTrue(ToolArgPolicyEvaluator.hostMatches("example.com", "example.com"));
        assertFalse(ToolArgPolicyEvaluator.hostMatches("example.com", "a.example.com"));
        assertTrue(ToolArgPolicyEvaluator.hostMatches("*.example.com", "a.example.com"));
        assertTrue(ToolArgPolicyEvaluator.hostMatches("*.example.com", "a.b.example.com"));
        assertFalse(ToolArgPolicyEvaluator.hostMatches("*.example.com", "example.com"));
        assertFalse(ToolArgPolicyEvaluator.hostMatches("*.example.com", "badexample.com"));
    }
}
