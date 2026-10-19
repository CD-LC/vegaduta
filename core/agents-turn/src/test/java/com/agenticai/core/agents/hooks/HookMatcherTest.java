package com.agenticai.core.agents.hooks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class HookMatcherTest {

    private static HookDefinition hook(HookPoint point, HookMatch match) {
        return new HookDefinition("h", true, point, match, HookActionType.LOG, Map.of(), FailMode.OPEN, 2000);
    }

    private static HookDefinition toolHook(HookMatch match) {
        return hook(HookPoint.BEFORE_TOOL, match);
    }

    @Test
    void globsMatchStarAndQuestionMarkOnly() {
        assertTrue(HookMatcher.globMatches("delete_*", "delete_file"));
        assertTrue(HookMatcher.globMatches("*", "anything_at_all"));
        assertTrue(HookMatcher.globMatches("*", ""));
        assertTrue(HookMatcher.globMatches("", ""));
        assertTrue(HookMatcher.globMatches("a?c", "abc"));
        assertTrue(HookMatcher.globMatches("*_file", "delete_file"));
        assertTrue(HookMatcher.globMatches("*a*b*", "xxaybz"));
        assertTrue(HookMatcher.globMatches("mcp__github__*", "mcp__github__create_issue"));
        assertTrue(HookMatcher.globMatches("a**b", "ab"));

        assertFalse(HookMatcher.globMatches("delete_*", "remove_file"));
        assertFalse(HookMatcher.globMatches("a?c", "ac"));
        assertFalse(HookMatcher.globMatches("abc", "abcd"));
        assertFalse(HookMatcher.globMatches("abcd", "abc"));
        assertFalse(HookMatcher.globMatches("*b", "abc"));
        assertFalse(HookMatcher.globMatches("DELETE_*", "delete_file"), "globs are case-sensitive");
        assertFalse(HookMatcher.globMatches(null, "x"));
        assertFalse(HookMatcher.globMatches("x", null));
    }

    @Test
    void aStarInTheNameIsLiteralNotAWildcard() {
        assertTrue(HookMatcher.globMatches("a*", "a*b"));
        assertFalse(HookMatcher.globMatches("a?", "a"));
    }

    @Test
    void aMatchWithNoConstraintMatchesEveryToolButNotAToollessToolPoint() {
        assertTrue(HookMatcher.matches(toolHook(HookMatch.ANY), "anything", null));
        assertFalse(HookMatcher.matches(toolHook(HookMatch.ANY), null, null), "a tool hook needs a tool");
        assertTrue(HookMatcher.matches(hook(HookPoint.BEFORE_MODEL, HookMatch.ANY), null, null));
    }

    @Test
    void toolGlobConstrainsTheName() {
        HookDefinition h = toolHook(new HookMatch("delete_*", null, null, null));

        assertTrue(HookMatcher.matches(h, "delete_row", null));
        assertFalse(HookMatcher.matches(h, "read_row", null));
        assertFalse(HookMatcher.matches(h, null, null));
    }

    @Test
    void sensitivityAndTrustMatchTheClassification() {
        HookMatcher.ToolFacts egress = HookMatcher.ToolFacts.classified("UNTRUSTED_EXTERNAL", "EGRESS", true);
        HookMatcher.ToolFacts read = HookMatcher.ToolFacts.classified("TENANT_SCOPED", "READ_TENANT", false);

        HookDefinition bySensitivity = toolHook(new HookMatch(null, null, "EGRESS", null));
        assertTrue(HookMatcher.matches(bySensitivity, "t", egress));
        assertFalse(HookMatcher.matches(bySensitivity, "t", read));

        HookDefinition byTrust = toolHook(new HookMatch(null, "UNTRUSTED_EXTERNAL", null, null));
        assertTrue(HookMatcher.matches(byTrust, "t", egress));
        assertFalse(HookMatcher.matches(byTrust, "t", read));
    }

    @Test
    void anUnclassifiedToolMatchesOnlyTheUnclassifiedSentinel() {
        HookMatcher.ToolFacts none = HookMatcher.ToolFacts.UNCLASSIFIED;

        assertFalse(HookMatcher.matches(toolHook(new HookMatch(null, null, "EGRESS", null)), "mcp__x__y", none),
            "a rule for EGRESS must not silently cover a tool nobody classified");
        assertFalse(HookMatcher.matches(toolHook(new HookMatch(null, null, null, true)), "mcp__x__y", none));
        assertFalse(HookMatcher.matches(toolHook(new HookMatch(null, null, null, false)), "mcp__x__y", none));
        assertTrue(HookMatcher.matches(toolHook(new HookMatch(null, null, HookMatch.UNCLASSIFIED, null)), "mcp__x__y", none));
        assertTrue(HookMatcher.matches(toolHook(new HookMatch(null, HookMatch.UNCLASSIFIED, null, null)), "mcp__x__y", none));
        assertFalse(HookMatcher.matches(toolHook(new HookMatch(null, null, HookMatch.UNCLASSIFIED, null)), "t",
            HookMatcher.ToolFacts.classified("TENANT_SCOPED", "READ_TENANT", false)));
    }

    @Test
    void irreversibleMatchesOnlyClassifiedTools() {
        HookMatcher.ToolFacts irreversible = HookMatcher.ToolFacts.classified("TENANT_SCOPED", "WRITE_TENANT", true);
        HookMatcher.ToolFacts reversible = HookMatcher.ToolFacts.classified("TENANT_SCOPED", "WRITE_TENANT", false);

        HookDefinition wantsIrreversible = toolHook(new HookMatch(null, null, null, true));
        assertTrue(HookMatcher.matches(wantsIrreversible, "t", irreversible));
        assertFalse(HookMatcher.matches(wantsIrreversible, "t", reversible));
        assertTrue(HookMatcher.matches(toolHook(new HookMatch(null, null, null, false)), "t", reversible));
    }

    @Test
    void everyPresentFieldMustHold() {
        HookDefinition h = toolHook(new HookMatch("send_*", null, "EGRESS", true));
        HookMatcher.ToolFacts egress = HookMatcher.ToolFacts.classified("UNTRUSTED_EXTERNAL", "EGRESS", true);

        assertTrue(HookMatcher.matches(h, "send_email", egress));
        assertFalse(HookMatcher.matches(h, "read_email", egress), "name fails");
        assertFalse(HookMatcher.matches(h, "send_email", HookMatcher.ToolFacts.classified("UNTRUSTED_EXTERNAL", "EGRESS", false)),
            "irreversible fails");
    }

    @Test
    void aFailedClassificationLookupMatchesConservatively() {
        // "could not tell" must never read as "does not apply" for a rule whose only job is to tighten.
        HookDefinition h = toolHook(new HookMatch(null, null, "EGRESS", null));

        assertTrue(HookMatcher.matches(h, "t", HookMatcher.ToolFacts.UNKNOWN));
        assertFalse(HookMatcher.matches(toolHook(new HookMatch("other_*", null, "EGRESS", null)), "t", HookMatcher.ToolFacts.UNKNOWN),
            "the tool-name constraint still applies");
    }

    @Test
    void onErrorWithoutAToolConstraintAlsoMatchesRunLevelErrors() {
        HookDefinition any = hook(HookPoint.ON_ERROR, HookMatch.ANY);
        HookDefinition byTool = hook(HookPoint.ON_ERROR, new HookMatch("flaky_*", null, null, null));

        assertTrue(HookMatcher.matches(any, null, null), "run-level error");
        assertTrue(HookMatcher.matches(any, "some_tool", null), "tool-level error");
        assertFalse(HookMatcher.matches(byTool, null, null), "a tool-constrained hook never fires on a run-level error");
        assertTrue(HookMatcher.matches(byTool, "flaky_api", null));
        assertFalse(HookMatcher.matches(byTool, "steady_api", null));
    }
}
