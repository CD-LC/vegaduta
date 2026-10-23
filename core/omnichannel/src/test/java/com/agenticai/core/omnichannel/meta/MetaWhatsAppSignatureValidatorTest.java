package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Meta signs the raw body with the tenant's App Secret ({@code X-Hub-Signature-256: sha256=<hex>}).
 * Real validator, signatures computed here from the documented scheme; there is no timestamp in
 * Meta's scheme, so replay protection for this channel is the provider-id dedup, not this class.
 */
class MetaWhatsAppSignatureValidatorTest {

    private static final String SECRET = "test-app-secret";
    private static final String BODY = "{\"object\":\"whatsapp_business_account\",\"entry\":[]}";

    private final MetaWhatsAppSignatureValidator validator = new MetaWhatsAppSignatureValidator();

    private static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256="
                    + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aCorrectSignatureIsAccepted() {
        assertDoesNotThrow(() -> validator.validateOrThrow(BODY, sign(SECRET, BODY), SECRET));
    }

    @Test
    void aSignatureMadeWithAnotherSecretIsRejected() {
        assertThrows(
                MetaWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, sign("other-secret", BODY), SECRET));
    }

    @Test
    void aTamperedBodyIsRejected() {
        String signature = sign(SECRET, BODY);
        assertThrows(
                MetaWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY.replace("entry", "entrx"), signature, SECRET));
    }

    @Test
    void missingEmptyOrMalformedHeadersAreRejected() {
        String good = sign(SECRET, BODY);
        assertThrows(
                MetaWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, null, SECRET));
        assertThrows(
                MetaWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, "", SECRET));
        assertThrows(
                MetaWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, "sha256=", SECRET));
        assertThrows(
                MetaWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, good.substring("sha256=".length()), SECRET));
        assertThrows(
                MetaWebhookSignatureException.class,
                () ->
                        validator.validateOrThrow(
                                BODY, "sha1=" + good.substring("sha256=".length()), SECRET));
    }

    @Test
    void anEmptyAppSecretFailsClosedInsteadOfAcceptingAnything() {
        assertThrows(
                MetaWebhookSignatureException.class,
                () -> validator.validateOrThrow(BODY, "sha256=" + "0".repeat(64), ""));
    }
}
