package com.agenticai.core.omnichannel.meta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link MetaSocialCredential}: the one Page credential behind both Messenger and Instagram. */
class MetaSocialCredentialTest {

    private MetaSocialCredential credential() {
        return new MetaSocialCredential(
                "page-1", "My Page", "enc-token", "enc-secret", "ig-1", "@mybrand");
    }

    @Test
    void constructorStartsActiveAndStampsTimestamps() {
        MetaSocialCredential c = credential();
        assertNull(c.getId());
        assertEquals("page-1", c.getPageId());
        assertEquals("My Page", c.getPageName());
        assertEquals("enc-token", c.getEncryptedPageAccessToken());
        assertEquals("enc-secret", c.getEncryptedAppSecret());
        assertEquals("ig-1", c.getInstagramBusinessAccountId());
        assertEquals("@mybrand", c.getInstagramUsername());
        assertEquals(MetaSocialCredentialStatus.ACTIVE, c.getStatus());
        assertNotNull(c.getCreatedAt());
        assertNotNull(c.getUpdatedAt());
        assertNotNull(c.getLastValidatedAt());
    }

    @Test
    void hasInstagramOnlyWhenAnAccountIdIsPresent() {
        assertTrue(credential().hasInstagram());
        assertFalse(new MetaSocialCredential("p", "n", "t", "s", null, null).hasInstagram());
        assertFalse(new MetaSocialCredential("p", "n", "t", "s", "  ", null).hasInstagram());
    }

    @Test
    void reactivateReplacesAllFieldsAndRestoresActive() {
        MetaSocialCredential c = credential();
        c.disconnect();

        c.reactivate("page-2", "Other Page", "t2", "s2", "ig-2", "@other");

        assertEquals("page-2", c.getPageId());
        assertEquals("Other Page", c.getPageName());
        assertEquals("t2", c.getEncryptedPageAccessToken());
        assertEquals("s2", c.getEncryptedAppSecret());
        assertEquals("ig-2", c.getInstagramBusinessAccountId());
        assertEquals("@other", c.getInstagramUsername());
        assertEquals(MetaSocialCredentialStatus.ACTIVE, c.getStatus());
        assertNotNull(c.getLastValidatedAt());
    }

    @Test
    void disconnectKeepsTheRowButStopsUse() {
        MetaSocialCredential c = credential();
        c.disconnect();
        assertEquals(MetaSocialCredentialStatus.DISCONNECTED, c.getStatus());
    }
}
