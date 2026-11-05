package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

/**
 * {@link GovernanceFailOpenPolicy#allowOnBackendFailure} is the ONE decision point for "Redis down
 * -> metered or unmetered traffic". Today every Redis-backed spend/abuse control fails open; this
 * pins both the decision and the greppable marker operators search for during an outage.
 */
class GovernanceFailOpenPolicyTest {

    @Test
    void theShippedPolicyIsFailOpen() {
        assertThat(new SpendControlsFailMode("open").isClosed()).isFalse();
        assertThat(new SpendControlsFailMode("").isClosed()).isFalse();
        assertThat(SpendControlsFailMode.open().isClosed()).isFalse();
    }

    @Test
    void closedModeThrowsA503ExceptionAndLogsTheClosedMarker() {
        Logger log = mock(Logger.class);
        SpendControlsFailMode mode = new SpendControlsFailMode("CLOSED");
        Exception redisDown = new IllegalStateException("down");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> mode.onGateFailure("x", redisDown, log))
                .isInstanceOf(SpendControlsUnavailableException.class)
                .hasCause(redisDown);
        verify(log)
                .error(
                        org.mockito.ArgumentMatchers.contains("GOVERNANCE_FAIL_CLOSED"),
                        eq("x"),
                        eq(redisDown));
        assertThat(
                        SpendControlsUnavailableException.class
                                .getAnnotation(
                                        org.springframework.web.bind.annotation.ResponseStatus
                                                .class)
                                .value())
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void anUnknownModeFailsStartup() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SpendControlsFailMode("maybe"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowOnBackendFailureLetsTrafficThroughAndLogsTheSearchableMarker() {
        Logger log = mock(Logger.class);
        Exception redisDown = new IllegalStateException("RedisConnectionFailureException: down");

        boolean allowed =
                GovernanceFailOpenPolicy.allowOnBackendFailure(
                        "llm-gateway-rate-limit", redisDown, log);

        assertThat(allowed).isTrue();
        // The marker must stay greppable: one Redis outage shows up as one line per control,
        // all with the same GOVERNANCE_FAIL_OPEN prefix and the control's own name.
        verify(log)
                .warn(
                        org.mockito.ArgumentMatchers.contains("GOVERNANCE_FAIL_OPEN"),
                        eq("llm-gateway-rate-limit"),
                        eq(redisDown));
    }

    @Test
    void theCallerSuppliesTheLoggerSoLinesLandUnderItsOwnName() {
        Logger log = mock(Logger.class);

        GovernanceFailOpenPolicy.allowOnBackendFailure(
                "per-user-budget", new RuntimeException(), log);

        verify(log).warn(anyString(), anyString(), any(Exception.class));
        verify(log, org.mockito.Mockito.never()).error(anyString(), any(), any());
    }
}
