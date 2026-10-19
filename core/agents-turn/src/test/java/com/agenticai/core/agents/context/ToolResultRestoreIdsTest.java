package com.agenticai.core.agents.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolResultRestoreIdsTest {

    @Test
    void theDigestIgnoresWhitespaceAndKeyOrder() {
        assertEquals(
            ToolResultRestoreIds.digest("{\"a\":1,\"b\":{\"y\":2,\"x\":3}}"),
            ToolResultRestoreIds.digest("{ \"b\" : { \"x\": 3, \"y\": 2 },\n \"a\": 1 }"));
    }

    @Test
    void differentArgumentsGiveDifferentDigests() {
        assertNotEquals(ToolResultRestoreIds.digest("{\"q\":\"a\"}"), ToolResultRestoreIds.digest("{\"q\":\"b\"}"));
    }

    @Test
    void theDigestIsTwelveLowerCaseHexCharactersAndStable() {
        String digest = ToolResultRestoreIds.digest("{\"q\":\"a\"}");

        assertTrue(digest.matches("[0-9a-f]{12}"), digest);
        assertEquals(digest, ToolResultRestoreIds.digest("{\"q\":\"a\"}"));
    }

    @Test
    void emptyAndNullArgumentsShareTheEmptyObjectDigest() {
        assertEquals(ToolResultRestoreIds.digest("{}"), ToolResultRestoreIds.digest(null));
        assertEquals(ToolResultRestoreIds.digest("{}"), ToolResultRestoreIds.digest("  "));
    }

    @Test
    void nonJsonArgumentsStillDigestDeterministically() {
        assertEquals(ToolResultRestoreIds.digest("not json"), ToolResultRestoreIds.digest("  not json "));
    }

    @Test
    void stepIdsRoundTripThroughParse() {
        String stepId = ToolResultRestoreIds.stepId("web_search", "{\"q\":\"x\"}");

        ToolResultRestoreIds.Parsed parsed = ToolResultRestoreIds.parse(stepId).orElseThrow();

        assertFalse(parsed.byLogId());
        assertEquals("web_search", parsed.toolName());
        assertEquals(ToolResultRestoreIds.digest("{\"q\":\"x\"}"), parsed.argsDigest());
    }

    @Test
    void aToolNameContainingAHashSplitsOnTheLastOne() {
        ToolResultRestoreIds.Parsed parsed = ToolResultRestoreIds.parse("mcp__srv__a#b#0123456789ab").orElseThrow();

        assertEquals("mcp__srv__a#b", parsed.toolName());
        assertEquals("0123456789ab", parsed.argsDigest());
    }

    @Test
    void aUuidParsesAsALogId() {
        UUID id = UUID.randomUUID();

        ToolResultRestoreIds.Parsed parsed = ToolResultRestoreIds.parse(" " + id + " ").orElseThrow();

        assertTrue(parsed.byLogId());
        assertEquals(id, parsed.logId());
    }

    @Test
    void garbageDoesNotParse() {
        for (String bad : new String[] {null, "", "  ", "nodigest", "tool#", "#abcd1234", "tool#xyz!", "tool#12"}) {
            assertEquals(Optional.empty(), ToolResultRestoreIds.parse(bad), String.valueOf(bad));
        }
    }
}
