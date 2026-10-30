package com.agenticai.core.omnichannel.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** {@link TelegramCredential}: lifecycle of the one stored bot row per tenant schema. */
class TelegramCredentialTest {

    private TelegramCredential credential() {
        return new TelegramCredential("12345", "mybot", "enc-token", "hashed-secret");
    }

    @Test
    void constructorStartsActiveAndStampsTimestamps() {
        TelegramCredential c = credential();
        assertNull(c.getId());
        assertEquals("12345", c.getBotUserId());
        assertEquals("mybot", c.getBotUsername());
        assertEquals("enc-token", c.getEncryptedBotToken());
        assertEquals("hashed-secret", c.getWebhookSecret());
        assertEquals(TelegramCredentialStatus.ACTIVE, c.getStatus());
        assertNotNull(c.getCreatedAt());
        assertNotNull(c.getUpdatedAt());
        assertNotNull(c.getLastValidatedAt());
    }

    @Test
    void reactivateReplacesAllFieldsAndRestoresActive() {
        TelegramCredential c = credential();
        c.disconnect();

        c.reactivate("99999", "otherbot", "enc-2", "hash-2");

        assertEquals("99999", c.getBotUserId());
        assertEquals("otherbot", c.getBotUsername());
        assertEquals("enc-2", c.getEncryptedBotToken());
        assertEquals("hash-2", c.getWebhookSecret());
        assertEquals(TelegramCredentialStatus.ACTIVE, c.getStatus());
        assertNotNull(c.getLastValidatedAt());
    }

    @Test
    void disconnectKeepsTheRowButStopsUse() {
        TelegramCredential c = credential();
        c.disconnect();
        assertEquals(TelegramCredentialStatus.DISCONNECTED, c.getStatus());
    }
}
