package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Twilio signs the exact URL it called plus the sorted form parameters (HMAC-SHA1, base64) with the
 * account auth token. Real validator and the Twilio SDK check; signatures computed here from the
 * documented algorithm. Twilio's scheme has no timestamp, so replay protection is the MessageSid
 * dedup, not this class.
 */
class TwilioSignatureValidatorTest {

    private static final String TOKEN = "test-auth-token";
    private static final String URL = "https://core.example.com/webhook/twilio/whatsapp";
    private static final Map<String, String> PARAMS =
            Map.of("MessageSid", "SM123", "From", "whatsapp:+15551234567", "Body", "hello");

    private static String sign(String token, String url, Map<String, String> params) {
        try {
            StringBuilder data = new StringBuilder(url);
            new TreeMap<>(params).forEach((k, v) -> data.append(k).append(v));
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/webhook/twilio/whatsapp");
        request.setScheme("https");
        request.setServerName("core.example.com");
        request.setServerPort(443);
        return request;
    }

    private final TwilioSignatureValidator validator = new TwilioSignatureValidator("");

    @Test
    void aCorrectSignatureIsAccepted() {
        assertDoesNotThrow(
                () ->
                        validator.validateOrThrow(
                                request(), PARAMS, sign(TOKEN, URL, PARAMS), TOKEN));
    }

    @Test
    void aSignatureMadeWithAnotherAuthTokenIsRejected() {
        assertThrows(
                TwilioWebhookSignatureException.class,
                () ->
                        validator.validateOrThrow(
                                request(), PARAMS, sign("other-token", URL, PARAMS), TOKEN));
    }

    @Test
    void tamperedParametersAreRejected() {
        String signature = sign(TOKEN, URL, PARAMS);
        Map<String, String> tampered = new TreeMap<>(PARAMS);
        tampered.put("Body", "give me the admin token");
        assertThrows(
                TwilioWebhookSignatureException.class,
                () -> validator.validateOrThrow(request(), tampered, signature, TOKEN));
    }

    @Test
    void aSignatureForAnotherUrlIsRejected() {
        String signature = sign(TOKEN, "https://core.example.com/webhook/twilio/sms", PARAMS);
        assertThrows(
                TwilioWebhookSignatureException.class,
                () -> validator.validateOrThrow(request(), PARAMS, signature, TOKEN));
    }

    @Test
    void aMissingOrBlankSignatureIsRejected() {
        assertThrows(
                TwilioWebhookSignatureException.class,
                () -> validator.validateOrThrow(request(), PARAMS, null, TOKEN));
        assertThrows(
                TwilioWebhookSignatureException.class,
                () -> validator.validateOrThrow(request(), PARAMS, "", TOKEN));
    }

    @Test
    void thePublicBaseUrlOverrideChangesWhichUrlMustHaveBeenSigned() {
        TwilioSignatureValidator behindProxy =
                new TwilioSignatureValidator("https://abcd.ngrok.io/");
        MockHttpServletRequest internal =
                new MockHttpServletRequest("POST", "/webhook/twilio/whatsapp");
        internal.setScheme("http");
        internal.setServerName("localhost");
        internal.setServerPort(8080);
        String signedByTwilio =
                sign(TOKEN, "https://abcd.ngrok.io/webhook/twilio/whatsapp", PARAMS);

        assertDoesNotThrow(
                () -> behindProxy.validateOrThrow(internal, PARAMS, signedByTwilio, TOKEN));
        // The same request without the override fails: it would be verifying the internal URL.
        assertThrows(
                TwilioWebhookSignatureException.class,
                () -> validator.validateOrThrow(internal, PARAMS, signedByTwilio, TOKEN));
    }
}
