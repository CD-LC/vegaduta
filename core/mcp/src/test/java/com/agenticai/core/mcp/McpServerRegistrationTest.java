package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * {@link McpServerRegistration}'s entity-owned state machines - the
 * platform-staff review gate (MCP-STDIO-SANDBOXING) and the bounded
 * auto-retry-with-backoff schedule (both 2026-08-05). Both are pure,
 * deterministic transitions on a plain (non-JPA-backed) instance, so they're
 * directly unit-testable without a repository/service - same "test the
 * calculation function in isolation" posture the task that added these asked
 * for, and the same "construct the real entity, not a mock" convention
 * {@code McpRegistryServiceTest} already documents for itself.
 */
class McpServerRegistrationTest {

    private static McpServerRegistration dynamicEntity() {
        McpServerRegistration entity = new McpServerRegistration("custom-server", McpServerType.STDIO, "d", true);
        entity.configureDynamic("npx", "[]", "encrypted-env");
        return entity;
    }

    // --- review gate ---------------------------------------------------------

    @Test
    void newRegistrationDefaultsToNotRequired() {
        McpServerRegistration entity = new McpServerRegistration("filesystem", McpServerType.STDIO, "d", true);

        assertEquals(McpReviewStatus.NOT_REQUIRED, entity.getReviewStatus());
        assertFalse(entity.isReviewBlocked());
    }

    @Test
    void markReviewPendingBlocksAndMarkReviewNotRequiredUnblocks() {
        McpServerRegistration entity = dynamicEntity();

        entity.markReviewPending();
        assertEquals(McpReviewStatus.PENDING, entity.getReviewStatus());
        assertTrue(entity.isReviewBlocked());

        entity.markReviewNotRequired();
        assertEquals(McpReviewStatus.NOT_REQUIRED, entity.getReviewStatus());
        assertFalse(entity.isReviewBlocked());
    }

    @Test
    void approveReviewUnblocksAndClearsAnyPriorRejectionReason() {
        McpServerRegistration entity = dynamicEntity();
        entity.rejectReview("looked suspicious");

        entity.approveReview();

        assertEquals(McpReviewStatus.APPROVED, entity.getReviewStatus());
        assertFalse(entity.isReviewBlocked());
        assertNull(entity.getReviewRejectionReason());
    }

    @Test
    void rejectReviewBlocksAndStoresTheReason() {
        McpServerRegistration entity = dynamicEntity();

        entity.rejectReview("unverified publisher");

        assertEquals(McpReviewStatus.REJECTED, entity.getReviewStatus());
        assertTrue(entity.isReviewBlocked());
        assertEquals("unverified publisher", entity.getReviewRejectionReason());
    }

    @Test
    void markConnectionRefusedByReviewSetsErrorStatusWithoutTouchingRetryState() {
        // The whole point of this being a SEPARATE mutator from markError -
        // see its own javadoc: a review-blocked registration must never
        // schedule an automatic backoff retry, since nothing about the
        // outcome can change without a human acting on it.
        McpServerRegistration entity = dynamicEntity();

        entity.markConnectionRefusedByReview("Awaiting platform review before this custom server can connect.");

        assertEquals(McpConnectionStatus.ERROR, entity.getConnectionStatus());
        assertEquals("Awaiting platform review before this custom server can connect.", entity.getConnectionHint());
        assertEquals(0, entity.getRetryAttemptCount());
        assertNull(entity.getNextRetryAt());
        assertFalse(entity.isRetryExhausted());
        assertTrue(entity.getLastAttemptedAt() != null);
    }

    // --- backoffFor: the pure schedule function -------------------------------

    @Test
    void backoffScheduleIsFiveMinutesThenFifteenThenOneHourThenSteadySixHours() {
        assertEquals(Duration.ofMinutes(5), McpServerRegistration.backoffFor(0));
        assertEquals(Duration.ofMinutes(15), McpServerRegistration.backoffFor(1));
        assertEquals(Duration.ofHours(1), McpServerRegistration.backoffFor(2));
        assertEquals(Duration.ofHours(6), McpServerRegistration.backoffFor(3));
        assertEquals(Duration.ofHours(6), McpServerRegistration.backoffFor(4));
        assertEquals(Duration.ofHours(6), McpServerRegistration.backoffFor(100));
    }

    @Test
    void backoffScheduleTreatsAnyNonPositiveAttemptNumberAsTheFirstStep() {
        assertEquals(Duration.ofMinutes(5), McpServerRegistration.backoffFor(-1));
    }

    // --- markError: retry bookkeeping -----------------------------------------

    @Test
    void firstFailureSchedulesTheFirstBackoffStepAndIncrementsTheAttemptCount() {
        McpServerRegistration entity = dynamicEntity();
        entity.markConnected(); // establish a known "just worked" baseline first

        entity.markError("boom");

        assertEquals(1, entity.getRetryAttemptCount());
        assertFalse(entity.isRetryExhausted());
        assertTrue(entity.getNextRetryAt() != null);
        assertTrue(entity.getNextRetryAt().isAfter(entity.getLastAttemptedAt().minusSeconds(1)));
    }

    @Test
    void consecutiveFailuresAdvanceThroughEachBackoffStepInOrder() {
        McpServerRegistration entity = dynamicEntity();

        entity.markError("1"); // was attempt 0 -> now count=1, scheduled per backoffFor(0)=5m
        java.time.Instant afterFirst = entity.getNextRetryAt();
        entity.markError("2"); // was attempt 1 -> now count=2, scheduled per backoffFor(1)=15m
        java.time.Instant afterSecond = entity.getNextRetryAt();

        assertEquals(2, entity.getRetryAttemptCount());
        // 15m-out is a later timestamp than 5m-out (both measured from
        // roughly "now", so a simple ordering check is deterministic
        // regardless of exact test execution speed).
        assertTrue(afterSecond.isAfter(afterFirst.minusSeconds(1)));
    }

    @Test
    void retryIsMarkedExhaustedAfterMaxAutoRetryAttemptsAndStopsSchedulingFurtherRetries() {
        McpServerRegistration entity = dynamicEntity();

        for (int i = 0; i < 8; i++) {
            entity.markError("failure #" + i);
        }

        assertTrue(entity.isRetryExhausted(), "should give up after 8 consecutive failures");
        assertNull(entity.getNextRetryAt(), "no further retry should be scheduled once exhausted");
        assertEquals(8, entity.getRetryAttemptCount());
    }

    @Test
    void aSuccessfulConnectResetsAllRetryStateEvenAfterMultipleFailures() {
        McpServerRegistration entity = dynamicEntity();
        entity.markError("1");
        entity.markError("2");
        entity.markError("3");

        entity.markConnected();

        assertEquals(0, entity.getRetryAttemptCount());
        assertNull(entity.getNextRetryAt());
        assertFalse(entity.isRetryExhausted());
    }

    @Test
    void aPassedTestAlsoResetsAllRetryStateSameAsAConnectedSuccess() {
        McpServerRegistration entity = dynamicEntity();
        entity.markError("1");
        entity.markError("2");

        entity.markTestPassed();

        assertEquals(0, entity.getRetryAttemptCount());
        assertNull(entity.getNextRetryAt());
        assertFalse(entity.isRetryExhausted());
    }

    // --- lastAttemptedAt: distinct from connectedAt/updatedAt -----------------

    @Test
    void lastAttemptedAtIsStampedOnFailureEvenThoughConnectedAtStaysNull() {
        McpServerRegistration entity = dynamicEntity();

        entity.markError("boom");

        assertNull(entity.getConnectedAt());
        assertTrue(entity.getLastAttemptedAt() != null);
    }

    @Test
    void lastAttemptedAtIsNotTouchedByAPlainEnabledToggle() {
        McpServerRegistration entity = dynamicEntity();
        entity.markError("boom");
        var stampedAfterFailure = entity.getLastAttemptedAt();

        entity.setEnabled(false);

        assertEquals(stampedAfterFailure, entity.getLastAttemptedAt());
    }

    // --- credentialsCorrupted: cleared by a fresh config save ------------------

    @Test
    void credentialsCorruptedIsClearedWhenTheDynamicConfigIsResaved() {
        McpServerRegistration entity = dynamicEntity();
        entity.setCredentialsCorrupted(true);

        entity.configureDynamic("npx", "[]", "new-encrypted-env");

        assertFalse(entity.isCredentialsCorrupted());
    }

    @Test
    void credentialsCorruptedIsClearedWhenSwitchingToARemoteConfig() {
        McpServerRegistration entity = dynamicEntity();
        entity.setCredentialsCorrupted(true);

        entity.configureRemote("https://mcp.example.com/mcp", "new-encrypted-env");

        assertFalse(entity.isCredentialsCorrupted());
    }
}
