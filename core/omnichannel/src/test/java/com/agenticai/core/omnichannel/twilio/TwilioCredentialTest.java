package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link TwilioCredential}: lifecycle of the one stored Twilio row per tenant schema. */
class TwilioCredentialTest {

    private TwilioCredential credential() {
        return new TwilioCredential("AC123", "enc-token", "SK456", "enc-key", "+15551234567");
    }

    @Test
    void constructorStartsActiveAndStampsTimestamps() {
        TwilioCredential c = credential();
        assertNull(c.getId());
        assertEquals("AC123", c.getAccountSid());
        assertEquals("enc-token", c.getEncryptedAuthToken());
        assertEquals("SK456", c.getApiKeySid());
        assertEquals("enc-key", c.getEncryptedApiKeySecret());
        assertEquals("+15551234567", c.getWhatsAppFromNumber());
        assertEquals(TwilioCredentialStatus.ACTIVE, c.getStatus());
        assertNotNull(c.getCreatedAt());
        assertNotNull(c.getUpdatedAt());
        assertNotNull(c.getLastValidatedAt());
    }

    @Test
    void usesApiKeyOnlyWhenASidIsReallySet() {
        assertTrue(credential().usesApiKey());
        assertFalse(
                new TwilioCredential("AC123", "enc-token", null, null, "+15551234567").usesApiKey());
        assertFalse(
                new TwilioCredential("AC123", "enc-token", "  ", null, "+15551234567").usesApiKey());
    }

    @Test
    void reactivateReplacesAllFieldsAndRestoresActive() {
        TwilioCredential c = credential();
        c.disconnect();

        c.reactivate("AC999", "enc-2", "SK2", "enc-k2", "+15559998888");

        assertEquals("AC999", c.getAccountSid());
        assertEquals("enc-2", c.getEncryptedAuthToken());
        assertEquals("SK2", c.getApiKeySid());
        assertEquals("enc-k2", c.getEncryptedApiKeySecret());
        assertEquals("+15559998888", c.getWhatsAppFromNumber());
        assertEquals(TwilioCredentialStatus.ACTIVE, c.getStatus());
        assertNotNull(c.getLastValidatedAt());
    }

    @Test
    void reactivateCanDropTheApiKeyEntirely() {
        TwilioCredential c = credential();
        c.reactivate("AC123", "enc-2", null, null, "+15551234567");
        assertFalse(c.usesApiKey());
        assertNull(c.getApiKeySid());
        assertNull(c.getEncryptedApiKeySecret());
    }

    @Test
    void disconnectKeepsTheRowButStopsUse() {
        TwilioCredential c = credential();
        c.disconnect();
        assertEquals(TwilioCredentialStatus.DISCONNECTED, c.getStatus());
    }
}
