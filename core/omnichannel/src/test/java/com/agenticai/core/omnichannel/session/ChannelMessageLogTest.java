package com.agenticai.core.omnichannel.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link ChannelMessageLog}: the append-only per-message transcript row. */
class ChannelMessageLogTest {

    private static final UUID SESSION_ID = UUID.randomUUID();

    @Test
    void inboundConstructorDefaultsDirectionToInbound() {
        ChannelMessageLog log =
                new ChannelMessageLog(ChannelType.TELEGRAM, "chat-42", SESSION_ID, "hello");

        assertNull(log.getId());
        assertEquals(ChannelType.TELEGRAM, log.getChannel());
        assertEquals("chat-42", log.getExternalId());
        assertEquals(SESSION_ID, log.getChannelSessionId());
        assertEquals("hello", log.getBody());
        assertEquals(ChannelMessageDirection.INBOUND, log.getDirection());
        assertNull(log.getAuthor());
        assertNotNull(log.getCreatedAt());
    }

    @Test
    void fullConstructorRecordsAnOutboundOperatorReply() {
        ChannelMessageLog log =
                new ChannelMessageLog(
                        ChannelType.WHATSAPP,
                        "+15551234567",
                        SESSION_ID,
                        "operator reply",
                        ChannelMessageDirection.OUTBOUND,
                        "kc-subject-1");

        assertEquals(ChannelMessageDirection.OUTBOUND, log.getDirection());
        assertEquals("kc-subject-1", log.getAuthor());
        assertEquals("+15551234567", log.getExternalId());
    }

    @Test
    void bodyIsTrimmedAndCappedAtTheColumnLength() {
        ChannelMessageLog log =
                new ChannelMessageLog(
                        ChannelType.SMS, "+1555", null, "  " + "x".repeat(2500) + "  ");
        assertEquals(2000, log.getBody().length());
    }

    @Test
    void nullBodyBecomesTheEmptyString() {
        ChannelMessageLog log = new ChannelMessageLog(ChannelType.SMS, "+1555", null, null);
        assertEquals("", log.getBody());
    }
}
