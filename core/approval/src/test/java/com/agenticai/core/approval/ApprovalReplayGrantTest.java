package com.agenticai.core.approval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ApprovalReplayGrant}'s three load-bearing properties (exact action
 * type, single use, explicit clear) - each one is what stands between "a
 * human approved this one call" and a broader bypass of the approval gate,
 * so each gets its own test.
 */
class ApprovalReplayGrantTest {

    @AfterEach
    void cleanUpThreadLocal() {
        ApprovalReplayGrant.clear();
    }

    @Test
    void consumeMatchesOnlyTheGrantedActionType() {
        ApprovalReplayGrant.grant("mcp_tool.send_message");

        assertFalse(ApprovalReplayGrant.consume("mcp_tool.delete_file"));
        // The non-matching consume above must NOT have burned the grant the
        // approved call is still waiting on.
        assertTrue(ApprovalReplayGrant.consume("mcp_tool.send_message"));
    }

    @Test
    void grantIsSingleUse() {
        ApprovalReplayGrant.grant("mcp_tool.send_message");

        assertTrue(ApprovalReplayGrant.consume("mcp_tool.send_message"));
        // One approval authorizes exactly one execution - a second call to
        // the same tool re-enters the normal gate.
        assertFalse(ApprovalReplayGrant.consume("mcp_tool.send_message"));
    }

    @Test
    void consumeWithoutAnyGrantIsFalse() {
        assertFalse(ApprovalReplayGrant.consume("mcp_tool.send_message"));
    }

    @Test
    void clearRemovesAnUnconsumedGrant() {
        ApprovalReplayGrant.grant("mcp_tool.send_message");
        ApprovalReplayGrant.clear();

        assertFalse(ApprovalReplayGrant.consume("mcp_tool.send_message"));
    }
}
