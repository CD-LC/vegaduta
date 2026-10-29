package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** {@link MetaWhatsAppCredential}: lifecycle of the tenant's Cloud API credential row. */
class MetaWhatsAppCredentialTest {

    private MetaWhatsAppCredential credential() {
        return new MetaWhatsAppCredential("pn-1", "waba-1", "enc-token", "enc-secret", "+15551234567");
    }

    @Test
    void constructorStartsActiveAndStampsTimestamps() {
        MetaWhatsAppCredential c = credential();
        assertNull(c.getId());
        assertEquals("pn-1", c.getPhoneNumberId());
        assertEquals("waba-1", c.getWabaId());
        assertEquals("enc-token", c.getEncryptedAccessToken());
        assertEquals("enc-secret", c.getEncryptedAppSecret());
        assertEquals("+15551234567", c.getDisplayPhoneNumber());
        assertEquals(MetaWhatsAppCredentialStatus.ACTIVE, c.getStatus());
        assertNotNull(c.getCreatedAt());
        assertNotNull(c.getUpdatedAt());
        assertNotNull(c.getLastValidatedAt());
    }

    @Test
    void reactivateReplacesAllFieldsAndRestoresActive() {
        MetaWhatsAppCredential c = credential();
        c.disconnect();

        c.reactivate("pn-2", "waba-2", "enc-t2", "enc-s2", "+15559998888");

        assertEquals("pn-2", c.getPhoneNumberId());
        assertEquals("waba-2", c.getWabaId());
        assertEquals("enc-t2", c.getEncryptedAccessToken());
        assertEquals("enc-s2", c.getEncryptedAppSecret());
        assertEquals("+15559998888", c.getDisplayPhoneNumber());
        assertEquals(MetaWhatsAppCredentialStatus.ACTIVE, c.getStatus());
        assertNotNull(c.getLastValidatedAt());
    }

    @Test
    void disconnectKeepsTheRowButStopsUse() {
        MetaWhatsAppCredential c = credential();
        c.disconnect();
        assertEquals(MetaWhatsAppCredentialStatus.DISCONNECTED, c.getStatus());
    }
}
