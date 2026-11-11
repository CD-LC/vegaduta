package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@link TwilioStatusCallbackController}: a failure status is audited, everything else is quiet,
 * and nothing - not even a bad signature or a missing credential - ever returns non-2xx to Twilio.
 */
class TwilioStatusCallbackControllerTest {

    private TwilioSignatureValidator signatureValidator;
    private TwilioCredentialService credentialService;
    private TenantResolutionService tenantResolutionService;
    private AuditLogService auditLogService;
    private TwilioStatusCallbackController controller;
    private HttpServletRequest servletRequest;

    @BeforeEach
    void setUp() {
        signatureValidator = mock(TwilioSignatureValidator.class);
        credentialService = mock(TwilioCredentialService.class);
        tenantResolutionService = mock(TenantResolutionService.class);
        auditLogService = mock(AuditLogService.class);
        controller =
                new TwilioStatusCallbackController(
                        signatureValidator,
                        credentialService,
                        tenantResolutionService,
                        auditLogService);
        servletRequest = mock(HttpServletRequest.class);
        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.TWILIO, "AC1"))
                .thenReturn(Optional.of("tenant_a"));
        when(credentialService.resolveActive())
                .thenReturn(
                        new TwilioCredentialService.DecryptedCredential(
                                "AC1", "auth-token", null, null, "+1555"));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Map<String, String> form(String status) {
        return Map.of(
                "AccountSid", "AC1",
                "MessageSid", "SM123",
                "MessageStatus", status,
                "To", "whatsapp:+15557654321");
    }

    @Test
    void aFailedDeliveryIsAuditedWithTheTwilioErrorDetail() {
        Map<String, String> params = new java.util.HashMap<>(form("failed"));
        params.put("ErrorCode", "30007");
        params.put("ErrorMessage", "Carrier violation");

        ResponseEntity<Void> response = controller.status(params, servletRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(auditLogService)
                .record(
                        eq("system:twilio-status-callback"),
                        eq("channel.whatsapp.delivery_failed"),
                        eq("twilio_message"),
                        eq("SM123"),
                        eq(AuditOutcome.FAILURE),
                        contains("Twilio error 30007"));
    }

    @Test
    void anUndeliveredStatusAlsoAuditsButWithoutErrorDetailWhenAbsent() {
        ResponseEntity<Void> response = controller.status(form("undelivered"), servletRequest);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(auditLogService)
                .record(
                        anyString(),
                        eq("channel.whatsapp.delivery_failed"),
                        eq("twilio_message"),
                        eq("SM123"),
                        eq(AuditOutcome.FAILURE),
                        contains("***4321"));
    }

    @Test
    void intermediateStatusesAreNotAudited() {
        for (String status : new String[] {"queued", "sent", "delivered", "read"}) {
            assertEquals(
                    HttpStatus.OK, controller.status(form(status), servletRequest).getStatusCode());
        }
        verifyNoInteractions(auditLogService);
    }

    @Test
    void theSignatureIsCheckedAgainstTheTenantAuthToken() {
        controller.status(form("delivered"), servletRequest);

        verify(signatureValidator)
                .validateOrThrow(servletRequest, form("delivered"), null, "auth-token");
    }

    @Test
    void aBadSignatureStillAnswersOkButAuditsNothing() {
        doThrow(new TwilioWebhookSignatureException("bad sig"))
                .when(signatureValidator)
                .validateOrThrow(any(), anyMap(), any(), anyString());

        assertEquals(
                HttpStatus.OK, controller.status(form("failed"), servletRequest).getStatusCode());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void aMissingCredentialStillAnswersOk() {
        when(credentialService.resolveActive()).thenThrow(new TwilioNotConfiguredException());

        assertEquals(
                HttpStatus.OK, controller.status(form("failed"), servletRequest).getStatusCode());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void anUnlinkedAccountSidFallsBackToTheDefaultSchema() {
        when(tenantResolutionService.resolveTenantSchema(ExternalAccountProvider.TWILIO, "ACX"))
                .thenReturn(Optional.empty());
        Map<String, String> params = new java.util.HashMap<>(form("delivered"));
        params.put("AccountSid", "ACX");

        assertEquals(HttpStatus.OK, controller.status(params, servletRequest).getStatusCode());
        assertEquals("public", TenantSchemaResolver.DEFAULT_SCHEMA);
    }
}
