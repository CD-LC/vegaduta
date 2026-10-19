package com.agenticai.core.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.agenticai.core.agentspi.AgentWalletLedger;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.elevenlabs.ElevenLabsApiException;
import com.agenticai.core.elevenlabs.ElevenLabsCredentialValidationException;
import com.agenticai.core.elevenlabs.ElevenLabsNotConfiguredException;
import com.agenticai.core.google.GoogleApiException;
import com.agenticai.core.google.GoogleNotConnectedException;
import com.agenticai.core.google.GoogleOAuthNotConfiguredException;
import com.agenticai.core.google.GoogleOAuthStateException;
import com.agenticai.core.imagegen.ImageGenApiException;
import com.agenticai.core.imagegen.ImageGenNotConfiguredException;
import com.agenticai.core.omnichannel.discord.DiscordNotConfiguredException;
import com.agenticai.core.omnichannel.email.EmailNotConfiguredException;
import com.agenticai.core.omnichannel.meta.MetaEmbeddedSignupNotConfiguredException;
import com.agenticai.core.omnichannel.meta.MetaSocialNotConfiguredException;
import com.agenticai.core.omnichannel.meta.MetaWebhookSignatureException;
import com.agenticai.core.omnichannel.meta.MetaWhatsAppNotConfiguredException;
import com.agenticai.core.omnichannel.slack.SlackNotConfiguredException;
import com.agenticai.core.omnichannel.slack.SlackWebhookSignatureException;
import com.agenticai.core.omnichannel.telegram.TelegramNotConfiguredException;
import com.agenticai.core.omnichannel.twilio.TwilioNotConfiguredException;
import com.agenticai.core.omnichannel.twilio.TwilioWebhookSignatureException;
import com.agenticai.core.omnichannel.twilio.VoiceDtmfMappingNotFoundException;
import com.agenticai.core.omnichannel.x.XApiException;
import com.agenticai.core.omnichannel.x.XNotConfiguredException;
import com.agenticai.core.omnichannel.x.XNotConnectedException;
import com.agenticai.core.omnichannel.x.XOAuthStateException;
import com.agenticai.core.payments.commerce.CommerceMandateException;
import com.agenticai.core.payments.wallet.WalletException;
import com.agenticai.core.sarvam.SarvamApiException;
import com.agenticai.core.sarvam.SarvamCredentialValidationException;
import com.agenticai.core.sarvam.SarvamNotConfiguredException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/**
 * Vendor exceptions declare their own HTTP mapping (HttpStatusMapped) instead of the catch-all
 * advice importing them (ADR-0011 rule a). Pins the answers the per-vendor named handlers used to
 * give: status (a 502 goes out as 503, edgeSafe), message, problem type and code - and that none is
 * recorded as a platform issue.
 */
class GlobalExceptionHandlerStatusMappedTest {

    private record Case(Exception e, HttpStatus status, String typeSlug, String code) {}

    // HttpStatusMapped exceptions reach the Exception.class catch-all, which lives in the
    // last-ordered advice of the chain (see FallbackExceptionHandler's javadoc).
    private static FallbackExceptionHandler fallback(PlatformIssueService issues) {
        return new FallbackExceptionHandler(
                issues, new ErrorResponseFactory(true, ErrorResponseFactory.DEFAULT_TYPE_PREFIX));
    }

    private static Case plain(Exception e, HttpStatus status) {
        return new Case(e, status, ErrorResponseFactory.slugFor(e, status), null);
    }

    private static Case coded(Exception e, HttpStatus status, String code) {
        return new Case(e, status, ErrorResponseFactory.slugForCode(code), code);
    }

    @Test
    void everyFormerVendorHandlerAnswersAsBefore() {
        List<Case> cases =
                List.of(
                        plain(new SarvamNotConfiguredException(), HttpStatus.BAD_REQUEST),
                        plain(
                                new SarvamApiException(500, "sarvam down"),
                                HttpStatus.SERVICE_UNAVAILABLE),
                        plain(
                                new SarvamCredentialValidationException("bad key"),
                                HttpStatus.BAD_REQUEST),
                        plain(
                                new ImageGenNotConfiguredException("no image provider"),
                                HttpStatus.BAD_REQUEST),
                        plain(
                                new ImageGenApiException("image call failed"),
                                HttpStatus.SERVICE_UNAVAILABLE),
                        plain(new ElevenLabsNotConfiguredException(), HttpStatus.BAD_REQUEST),
                        plain(
                                new ElevenLabsCredentialValidationException("bad key"),
                                HttpStatus.BAD_REQUEST),
                        plain(
                                new ElevenLabsApiException(503, "elevenlabs down"),
                                HttpStatus.SERVICE_UNAVAILABLE),
                        plain(new VoiceDtmfMappingNotFoundException("7"), HttpStatus.NOT_FOUND),
                        plain(new TwilioNotConfiguredException(), HttpStatus.BAD_REQUEST),
                        plain(
                                new TwilioWebhookSignatureException("bad signature"),
                                HttpStatus.FORBIDDEN),
                        plain(new MetaWhatsAppNotConfiguredException(), HttpStatus.BAD_REQUEST),
                        plain(
                                new MetaWebhookSignatureException("bad signature"),
                                HttpStatus.FORBIDDEN),
                        plain(new MetaSocialNotConfiguredException(), HttpStatus.BAD_REQUEST),
                        plain(
                                new MetaEmbeddedSignupNotConfiguredException(),
                                HttpStatus.BAD_REQUEST),
                        plain(new SlackNotConfiguredException(), HttpStatus.BAD_REQUEST),
                        plain(
                                new SlackWebhookSignatureException("bad signature"),
                                HttpStatus.FORBIDDEN),
                        plain(new DiscordNotConfiguredException(), HttpStatus.BAD_REQUEST),
                        plain(new TelegramNotConfiguredException(), HttpStatus.BAD_REQUEST),
                        plain(new EmailNotConfiguredException(), HttpStatus.BAD_REQUEST),
                        plain(new GoogleNotConnectedException(), HttpStatus.BAD_REQUEST),
                        plain(new GoogleOAuthNotConfiguredException(), HttpStatus.BAD_REQUEST),
                        plain(new GoogleOAuthStateException("bad state"), HttpStatus.BAD_REQUEST),
                        plain(
                                new GoogleApiException("google down"),
                                HttpStatus.SERVICE_UNAVAILABLE),
                        plain(new XNotConfiguredException(), HttpStatus.BAD_REQUEST),
                        plain(new XNotConnectedException(), HttpStatus.BAD_REQUEST),
                        plain(new XOAuthStateException("bad state"), HttpStatus.BAD_REQUEST),
                        plain(new XApiException("x down"), HttpStatus.SERVICE_UNAVAILABLE),
                        coded(
                                new CommerceMandateException("NOT_FOUND", "no mandate"),
                                HttpStatus.NOT_FOUND,
                                "NOT_FOUND"),
                        coded(
                                new CommerceMandateException("OVER_CEILING", "too much"),
                                HttpStatus.CONFLICT,
                                "OVER_CEILING"),
                        coded(
                                new CommerceMandateException("CAPTURE_FAILED", "declined"),
                                HttpStatus.CONFLICT,
                                "CAPTURE_FAILED"),
                        coded(
                                new CommerceMandateException("BAD_AMOUNT", "bad"),
                                HttpStatus.BAD_REQUEST,
                                "BAD_AMOUNT"),
                        coded(
                                new WalletException("WALLET_NOT_FOUND", "no wallet"),
                                HttpStatus.NOT_FOUND,
                                "WALLET_NOT_FOUND"),
                        coded(
                                new WalletException("WALLET_NOT_ACTIVE", "frozen"),
                                HttpStatus.CONFLICT,
                                "WALLET_NOT_ACTIVE"),
                        coded(
                                new WalletException("INSUFFICIENT_FUNDS", "broke"),
                                HttpStatus.PAYMENT_REQUIRED,
                                "INSUFFICIENT_FUNDS"),
                        coded(
                                new WalletException("AMOUNT_INVALID", "bad"),
                                HttpStatus.BAD_REQUEST,
                                "AMOUNT_INVALID"));

        PlatformIssueService issues = mock(PlatformIssueService.class);
        FallbackExceptionHandler handler = fallback(issues);
        for (Case c : cases) {
            String label = c.e().getClass().getSimpleName();
            ResponseEntity<Map<String, Object>> response = handler.handleUnexpected(c.e());

            assertEquals(c.status(), response.getStatusCode(), label);
            Map<String, Object> body = response.getBody();
            assertEquals(c.e().getMessage(), body.get("error"), label);
            assertTrue(
                    String.valueOf(body.get("type")).endsWith(c.typeSlug()),
                    label + " type " + body.get("type"));
            if (c.code() == null) {
                assertFalse(body.containsKey("code"), label);
            } else {
                assertEquals(c.code(), body.get("code"), label);
            }
        }
        verifyNoInteractions(issues);
    }

    @Test
    void aWalletLedgerRefusalKeepsTheLedgersStatusAndCode() {
        PlatformIssueService issues = mock(PlatformIssueService.class);
        ResponseEntity<Map<String, Object>> response =
                fallback(issues)
                        .handleUnexpected(
                                new AgentWalletLedger.WalletLedgerException(
                                        "INSUFFICIENT_FUNDS", "broke", 402, null));

        assertEquals(HttpStatus.PAYMENT_REQUIRED, response.getStatusCode());
        assertEquals("broke", response.getBody().get("error"));
        assertEquals("INSUFFICIENT_FUNDS", response.getBody().get("code"));
        verifyNoInteractions(issues);
    }

    @Test
    void anUnmappedExceptionIsStillA500Issue() {
        PlatformIssueService issues = mock(PlatformIssueService.class);
        ResponseEntity<Map<String, Object>> response =
                fallback(issues).handleUnexpected(new IllegalStateException("boom"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("Something went wrong. Please try again.", response.getBody().get("error"));
    }
}
