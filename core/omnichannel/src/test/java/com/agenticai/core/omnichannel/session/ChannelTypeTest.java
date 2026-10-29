package com.agenticai.core.omnichannel.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@link ChannelType}: the persisted channel vocabulary - pinned so a rename is a visible break. */
class ChannelTypeTest {

    @Test
    void everyChannelValueResolvesByName() {
        for (String name :
                new String[] {
                    "WHATSAPP", "MESSENGER", "INSTAGRAM", "SLACK", "DISCORD", "X", "TELEGRAM",
                    "EMAIL", "TEAMS", "VOICE", "SMS", "SIGNAL", "MATRIX", "IMESSAGE", "WHATSAPP_QR"
                }) {
            assertEquals(name, ChannelType.valueOf(name).name());
        }
        assertEquals(15, ChannelType.values().length);
    }
}
