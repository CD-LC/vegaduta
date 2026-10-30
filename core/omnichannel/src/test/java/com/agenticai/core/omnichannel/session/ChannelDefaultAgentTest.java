package com.agenticai.core.omnichannel.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link ChannelDefaultAgent} and {@link InboundSafetyMode}: the per-channel routing row. */
class ChannelDefaultAgentTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    @Test
    void constructorDefaultsToManualPairingAndFlagMode() {
        ChannelDefaultAgent row = new ChannelDefaultAgent(ChannelType.TELEGRAM, AGENT_ID);

        assertNull(row.getId());
        assertEquals(ChannelType.TELEGRAM, row.getChannel());
        assertEquals(AGENT_ID, row.getAgentId());
        assertFalse(row.isAutoApproveNewSenders());
        assertEquals("FLAG", row.getInboundSafetyMode());
        assertNull(row.getDeclaredTask());
        assertNull(row.getDeclaredTaskAt());
        assertNull(row.getDeclaredTaskBy());
        assertFalse(row.isOpenEndedOverride());
        assertNotNull(row.getUpdatedAt());
    }

    @Test
    void setAgentIdRebindsTheChannel() {
        ChannelDefaultAgent row = new ChannelDefaultAgent(ChannelType.SMS, AGENT_ID);
        UUID other = UUID.randomUUID();
        row.setAgentId(other);
        assertEquals(other, row.getAgentId());
    }

    @Test
    void setAutoApproveNewSendersFlipsTheGate() {
        ChannelDefaultAgent row = new ChannelDefaultAgent(ChannelType.SIGNAL, AGENT_ID);
        row.setAutoApproveNewSenders(true);
        assertTrue(row.isAutoApproveNewSenders());
    }

    @Test
    void declareTaskStampsTaskActorAndOverride() {
        ChannelDefaultAgent row = new ChannelDefaultAgent(ChannelType.WHATSAPP, AGENT_ID);
        row.declareTask("support", "admin@x.test", true);

        assertEquals("support", row.getDeclaredTask());
        assertEquals("admin@x.test", row.getDeclaredTaskBy());
        assertNotNull(row.getDeclaredTaskAt());
        assertTrue(row.isOpenEndedOverride());
    }

    @Test
    void setInboundSafetyModeNormalizesThroughParse() {
        ChannelDefaultAgent row = new ChannelDefaultAgent(ChannelType.MATRIX, AGENT_ID);

        row.setInboundSafetyMode("hold");
        assertEquals("HOLD", row.getInboundSafetyMode());

        row.setInboundSafetyMode("off");
        assertEquals("OFF", row.getInboundSafetyMode());
    }

    @Test
    void setInboundSafetyModeDegradesUnknownAndBlankValuesToFlag() {
        ChannelDefaultAgent row = new ChannelDefaultAgent(ChannelType.MATRIX, AGENT_ID);

        row.setInboundSafetyMode("nonsense");
        assertEquals("FLAG", row.getInboundSafetyMode());

        row.setInboundSafetyMode(null);
        assertEquals("FLAG", row.getInboundSafetyMode());

        row.setInboundSafetyMode("   ");
        assertEquals("FLAG", row.getInboundSafetyMode());
    }

    @Test
    void parseRecognizesEveryModeCaseInsensitively() {
        assertEquals("OFF", InboundSafetyMode.parse("off"));
        assertEquals("FLAG", InboundSafetyMode.parse(" Flag "));
        assertEquals("HOLD", InboundSafetyMode.parse("HOLD"));
    }
}
