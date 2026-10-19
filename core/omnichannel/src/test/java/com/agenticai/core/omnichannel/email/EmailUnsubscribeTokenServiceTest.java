package com.agenticai.core.omnichannel.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmailUnsubscribeTokenServiceTest {

    private final EmailUnsubscribeTokenService svc = new EmailUnsubscribeTokenService("s3cret", "https://api.test/");

    @Test
    void roundTripsTenantAndLowercasedEmail() {
        String token = svc.createToken("tenant-a", "Ann@X.test");
        EmailUnsubscribeTokenService.Claims claims = svc.verify(token).orElseThrow();
        assertEquals("tenant-a", claims.tenantId());
        assertEquals("ann@x.test", claims.email());
    }

    @Test
    void rejectsTamperedForeignAndMalformedTokens() {
        String token = svc.createToken("tenant-a", "ann@x.test");
        String tampered = svc.createToken("tenant-b", "ann@x.test").split("\\.")[0] + "." + token.split("\\.")[1];
        assertTrue(svc.verify(tampered).isEmpty());
        assertTrue(new EmailUnsubscribeTokenService("other", "https://api.test").verify(token).isEmpty());
        assertTrue(svc.verify("garbage").isEmpty());
        assertTrue(svc.verify("a.b").isEmpty());
        assertTrue(svc.verify(null).isEmpty());
    }

    @Test
    void buildsAbsoluteUrlAndIsUnconfiguredWithoutSecretOrBase() {
        assertTrue(svc.unsubscribeUrl("t", "a@x.test").startsWith("https://api.test/api/public/email/unsubscribe?t="));
        assertFalse(new EmailUnsubscribeTokenService("", "https://api.test").isConfigured());
        assertFalse(new EmailUnsubscribeTokenService("k", "").isConfigured());
        assertNull(new EmailUnsubscribeTokenService("k", "").unsubscribeUrl("t", "a@x.test"));
    }
}
