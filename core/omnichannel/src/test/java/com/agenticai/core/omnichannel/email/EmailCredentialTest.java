package com.agenticai.core.omnichannel.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link EmailCredential}: lifecycle of the tenant's IMAP mailbox row. */
class EmailCredentialTest {

    private EmailCredential credential() {
        return new EmailCredential("imap.example.com", 993, "agent@example.com", "enc-pw", true);
    }

    @Test
    void constructorStartsActiveAndStampsTimestamps() {
        EmailCredential c = credential();
        assertNull(c.getId());
        assertEquals("imap.example.com", c.getImapHost());
        assertEquals(993, c.getImapPort());
        assertEquals("agent@example.com", c.getImapUsername());
        assertEquals("enc-pw", c.getEncryptedImapPassword());
        assertTrue(c.isUseSsl());
        assertEquals(EmailCredentialStatus.ACTIVE, c.getStatus());
        assertNull(c.getLastPolledAt());
        assertNotNull(c.getCreatedAt());
        assertNotNull(c.getUpdatedAt());
        assertNotNull(c.getLastValidatedAt());
    }

    @Test
    void reactivateReplacesAllFieldsAndRestoresActive() {
        EmailCredential c = credential();
        c.disconnect();

        c.reactivate("imap2.example.com", 143, "other@example.com", "enc-2", false);

        assertEquals("imap2.example.com", c.getImapHost());
        assertEquals(143, c.getImapPort());
        assertEquals("other@example.com", c.getImapUsername());
        assertEquals("enc-2", c.getEncryptedImapPassword());
        assertEquals(EmailCredentialStatus.ACTIVE, c.getStatus());
        assertNotNull(c.getLastValidatedAt());
    }

    @Test
    void markPolledStampsTheLastPollInstant() {
        EmailCredential c = credential();
        assertNull(c.getLastPolledAt());
        c.markPolled();
        assertNotNull(c.getLastPolledAt());
    }

    @Test
    void disconnectKeepsTheRowButStopsUse() {
        EmailCredential c = credential();
        c.disconnect();
        assertEquals(EmailCredentialStatus.DISCONNECTED, c.getStatus());
    }
}
