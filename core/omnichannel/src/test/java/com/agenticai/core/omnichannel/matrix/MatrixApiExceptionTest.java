package com.agenticai.core.omnichannel.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link MatrixApiException}: the structured read of a failed homeserver call. */
class MatrixApiExceptionTest {

    @Test
    void twoArgConstructorDefaultsErrcodeAndRetryAfter() {
        MatrixApiException e = new MatrixApiException(500, null, "boom");
        assertEquals(500, e.status());
        assertEquals("M_UNKNOWN", e.errcode());
        assertEquals(0L, e.retryAfterMs());
        assertEquals("boom", e.getMessage());
    }

    @Test
    void fullConstructorCarriesEverything() {
        RuntimeException cause = new RuntimeException("io");
        MatrixApiException e =
                new MatrixApiException(429, "M_LIMIT_EXCEEDED", "slow down", 5000, cause);
        assertEquals(429, e.status());
        assertEquals("M_LIMIT_EXCEEDED", e.errcode());
        assertEquals(5000, e.retryAfterMs());
        assertEquals(cause, e.getCause());
    }

    @Test
    void unknownTokenMatchesBothDeadTokenErrcodes() {
        assertTrue(new MatrixApiException(401, "M_UNKNOWN_TOKEN", "x").isUnknownToken());
        assertTrue(new MatrixApiException(401, "M_MISSING_TOKEN", "x").isUnknownToken());
        assertFalse(new MatrixApiException(401, "M_FORBIDDEN", "x").isUnknownToken());
    }

    @Test
    void rateLimitedMatchesHttp429OrTheLimitErrcode() {
        assertTrue(new MatrixApiException(429, null, "x").isRateLimited());
        assertTrue(new MatrixApiException(500, "M_LIMIT_EXCEEDED", "x").isRateLimited());
        assertFalse(new MatrixApiException(500, "M_FORBIDDEN", "x").isRateLimited());
    }

    @Test
    void permanentClientErrorIsAnyNonRateLimited4xx() {
        assertTrue(new MatrixApiException(403, "M_FORBIDDEN", "x").isPermanentClientError());
        assertFalse(new MatrixApiException(429, "M_LIMIT_EXCEEDED", "x").isPermanentClientError());
        assertFalse(new MatrixApiException(503, null, "x").isPermanentClientError());
    }
}
