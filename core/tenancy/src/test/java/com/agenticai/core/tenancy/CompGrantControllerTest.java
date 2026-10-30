package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.dto.CompGrantResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * DAST-2026-07-27 D4 - regression coverage for the auth-before-body-parsing ordering fix. Before
 * this fix, {@code CompGrantController#compGrant} bound its body via a typed
 * {@code @Valid @RequestBody CompGrantRequest} parameter, which Spring resolves (parses AND
 * validates) before the method body's {@code authenticate()} call ever runs - so an unauthenticated
 * caller with a missing/malformed/incomplete body got a 400 revealing the required-field schema
 * (or, for a body that failed to deserialize entirely, a generic "not valid JSON" 400) instead of a
 * 401/403. These tests call the controller method directly (no MockMvc/web layer anywhere in this
 * codebase's existing tests - see RazorpayWebhookServiceTest for the established plain-unit-test
 * convention this follows) and assert that a bad body NEVER produces a body-parsing/validation
 * exception before authenticate() has already rejected the call - and that a good token combined
 * with a bad body now correctly reaches {@code CompGrantMalformedBodyException}/{@code
 * CompGrantValidationException}, which is only possible post-auth.
 */
class CompGrantControllerTest {

    private static final String VALID_TOKEN = "correct-service-token";

    private CompGrantService compGrantService;
    private CompGrantController controller;

    @BeforeEach
    void setUp() {
        compGrantService = mock(CompGrantService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        controller =
                new CompGrantController(compGrantService, VALID_TOKEN, "", objectMapper, validator);
    }

    @Test
    void missingTokenWithEmptyBodyRejectsAsAuthFailureNotBodyFailure() {
        // No X-Service-Token at all, no body - must fail as a missing-token
        // 401, never reach body parsing/validation.
        CompGrantTokenMissingException e =
                assertThrows(
                        CompGrantTokenMissingException.class,
                        () -> controller.compGrant(null, null));
        assertEquals("Missing X-Service-Token header.", e.getMessage());
        verify(compGrantService, never()).grant(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void missingTokenWithMalformedBodyStillRejectsAsAuthFailureNotBodyFailure() {
        // This is the exact "unauthenticated caller learns the schema" case
        // D4 flagged: a malformed body must NOT surface before the token
        // check runs, regardless of how broken the body is.
        CompGrantTokenMissingException e =
                assertThrows(
                        CompGrantTokenMissingException.class,
                        () -> controller.compGrant(null, "{not even valid json"));
        assertEquals("Missing X-Service-Token header.", e.getMessage());
        verify(compGrantService, never()).grant(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void wrongTokenWithWellFormedBodyRejectsAsAuthFailureNotBodyFailure() {
        // This is the D4 "oddity" repro: a wrong-but-present token with a
        // well-formed body must come back 403 (CompGrantTokenInvalidException),
        // never the generic body-parsing 400 the live DAST pass observed.
        String wellFormedBody =
                "{\"tenantId\":\"11111111-1111-1111-1111-111111111111\","
                        + "\"targetPlan\":\"PREMIUM\",\"durationMonths\":3,\"reason\":\"test\"}";

        CompGrantTokenInvalidException e =
                assertThrows(
                        CompGrantTokenInvalidException.class,
                        () -> controller.compGrant("wrong-guess-123", wellFormedBody));
        assertEquals("Invalid X-Service-Token.", e.getMessage());
        verify(compGrantService, never()).grant(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void wrongTokenWithMalformedBodyStillRejectsAsAuthFailureNotBodyFailure() {
        CompGrantTokenInvalidException e =
                assertThrows(
                        CompGrantTokenInvalidException.class,
                        () -> controller.compGrant("wrong-guess-123", "{ this is not json }"));
        assertEquals("Invalid X-Service-Token.", e.getMessage());
        verify(compGrantService, never()).grant(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void correctTokenWithMalformedBodyNowReachesMalformedBodyException() {
        // Only reachable AFTER auth succeeds - proves parsing genuinely moved
        // to run after authenticate(), not that it was simply deleted.
        CompGrantMalformedBodyException e =
                assertThrows(
                        CompGrantMalformedBodyException.class,
                        () -> controller.compGrant(VALID_TOKEN, "{ this is not json }"));
        assertEquals(
                "The request body is missing or not valid JSON for this endpoint.", e.getMessage());
    }

    @Test
    void correctTokenWithMissingFieldsNowReachesValidationException() {
        CompGrantValidationException e =
                assertThrows(
                        CompGrantValidationException.class,
                        () -> controller.compGrant(VALID_TOKEN, "{}"));
        // Same three fields the live DAST repro observed, just reached only
        // after a valid token was presented.
        assertEquals(true, e.getMessage().contains("tenantId"));
        assertEquals(true, e.getMessage().contains("targetPlan"));
        assertEquals(true, e.getMessage().contains("durationMonths"));
    }

    @Test
    void correctTokenWithValidBodyDelegatesToService() {
        String body =
                "{\"tenantId\":\"11111111-1111-1111-1111-111111111111\","
                        + "\"targetPlan\":\"PREMIUM\",\"durationMonths\":3,\"reason\":\"test\"}";
        CompGrantResponse expected =
                new CompGrantResponse(
                        java.util.UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        TenantPlan.PREMIUM,
                        Instant.now(),
                        Instant.now(),
                        "test");
        when(compGrantService.grant(org.mockito.ArgumentMatchers.any())).thenReturn(expected);

        CompGrantResponse actual = controller.compGrant(VALID_TOKEN, body);

        assertEquals(expected, actual);
    }

    @Test
    void noConfiguredServiceTokenFailsClosedRegardlessOfBody() {
        CompGrantController noSecretController =
                new CompGrantController(
                        compGrantService,
                        "",
                        "",
                        new ObjectMapper(),
                        Validation.buildDefaultValidatorFactory().getValidator());

        assertThrows(
                CompGrantTokenMissingException.class,
                () -> noSecretController.compGrant(VALID_TOKEN, "{}"));
    }
}
