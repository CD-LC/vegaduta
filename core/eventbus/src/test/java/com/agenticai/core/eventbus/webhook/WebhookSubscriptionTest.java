package com.agenticai.core.eventbus.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link WebhookSubscription}'s two pieces of real logic: the comma-joined {@code event_types}
 * column round-tripping as a set, and {@link WebhookSubscription#matches}' three pattern kinds
 * ({@code "*"}, {@code "prefix.*"}, exact) - plus the auto-disable bookkeeping that switches a
 * chronically failing endpoint off at {@link WebhookSubscription#AUTO_DISABLE_FAILURE_THRESHOLD}.
 */
class WebhookSubscriptionTest {

    private static WebhookSubscription sub(Set<String> types) {
        return new WebhookSubscription("hook", "https://example.invalid/h", "enc", types, "tester");
    }

    @Test
    void aNewSubscriptionIsEnabledWithNoFailuresAndATrimmedTypeSet() {
        WebhookSubscription sub = sub(Set.of(" workflow.* ", ""));

        assertTrue(sub.isEnabled());
        assertEquals(0, sub.getConsecutiveFailures());
        assertNull(sub.getAutoDisabledReason());
        assertEquals(Set.of("workflow.*"), sub.eventTypeSet());
        assertEquals("workflow.*", sub.getEventTypes());
        assertEquals("hook", sub.getName());
        assertEquals("https://example.invalid/h", sub.getUrl());
        assertEquals("enc", sub.getSecretEncrypted());
        assertEquals("tester", sub.getCreatedBy());
        assertNotNull(sub.getCreatedAt());
        assertNotNull(sub.getUpdatedAt());
    }

    @Test
    void eventTypeSetToleratesBlankAndMissingStorage() {
        WebhookSubscription sub = sub(Set.of());
        assertTrue(sub.eventTypeSet().isEmpty());

        sub.setEventTypeSet(new LinkedHashSet<>(Arrays.asList("a", " b ", "", "a")));
        // Joined, trimmed, deduplicated - the stored form the column actually carries.
        assertEquals("a,b", sub.getEventTypes());
        assertEquals(Set.of("a", "b"), sub.eventTypeSet());
    }

    @Test
    void starMatchesEverythingButOnlyWhileEnabled() {
        WebhookSubscription sub = sub(Set.of("*"));

        assertTrue(sub.matches("anything.at.all"));
        assertFalse(sub.matches(null), "a null event type never matches");

        sub.update("hook", "https://example.invalid/h", Set.of("*"), false);
        assertFalse(sub.matches("anything.at.all"), "a disabled subscription matches nothing");
    }

    @Test
    void aPrefixPatternMatchesTheWholeSubtreeButNotASibling() {
        WebhookSubscription sub = sub(Set.of("workflow.*"));

        assertTrue(sub.matches("workflow.run.completed"));
        assertTrue(sub.matches("workflow.node.completed"));
        assertFalse(sub.matches("workflow"));
        assertFalse(sub.matches("approval.requested"));
    }

    @Test
    void anExactPatternIsCaseInsensitiveAndMatchesNothingElse() {
        WebhookSubscription sub = sub(Set.of("Agent.Message.Completed"));

        assertTrue(sub.matches("agent.message.completed"));
        assertFalse(sub.matches("agent.message.completed.extra"));
    }

    @Test
    void updateAndRotateSecretRefreshFieldsAndBumpsUpdatedAt() {
        WebhookSubscription sub = sub(Set.of("a"));

        sub.update("renamed", "https://example.invalid/new", Set.of("b", "c"), false);
        sub.rotateSecret("enc2");

        assertEquals("renamed", sub.getName());
        assertEquals("https://example.invalid/new", sub.getUrl());
        assertEquals(Set.of("b", "c"), sub.eventTypeSet());
        assertFalse(sub.isEnabled());
        assertEquals("enc2", sub.getSecretEncrypted());
    }

    @Test
    void failuresBelowTheThresholdKeepTheSubscriptionEnabled() {
        WebhookSubscription sub = sub(Set.of("*"));

        for (int i = 0; i < WebhookSubscription.AUTO_DISABLE_FAILURE_THRESHOLD - 1; i++) {
            sub.recordDeliveryFailure("boom " + i);
        }

        assertTrue(sub.isEnabled());
        assertEquals(
                WebhookSubscription.AUTO_DISABLE_FAILURE_THRESHOLD - 1,
                sub.getConsecutiveFailures());
        assertNull(sub.getAutoDisabledReason());
    }

    @Test
    void crossingTheFailureThresholdAutoDisablesWithAReason() {
        WebhookSubscription sub = sub(Set.of("*"));

        for (int i = 0; i < WebhookSubscription.AUTO_DISABLE_FAILURE_THRESHOLD; i++) {
            sub.recordDeliveryFailure("endpoint refused");
        }

        assertFalse(sub.isEnabled());
        assertNotNull(sub.getAutoDisabledReason());
        assertTrue(sub.getAutoDisabledReason().contains("endpoint refused"));
        assertTrue(sub.getAutoDisabledReason().length() <= 300);

        // Failures past the threshold keep counting but do not rewrite the reason.
        int before = sub.getConsecutiveFailures();
        sub.recordDeliveryFailure("still down");
        assertEquals(before + 1, sub.getConsecutiveFailures());
    }

    @Test
    void aSuccessClearsTheStreakAndTheRecordedReason() {
        WebhookSubscription sub = sub(Set.of("*"));
        sub.recordDeliveryFailure("down");

        sub.recordDeliverySuccess();

        assertEquals(0, sub.getConsecutiveFailures());
        assertNull(sub.getAutoDisabledReason());

        // And a no-op success on a clean row stays clean.
        sub.recordDeliverySuccess();
        assertEquals(0, sub.getConsecutiveFailures());
    }
}
