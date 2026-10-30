package com.agenticai.core.omnichannel.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link ConversationHeldByHumanException} and {@link InboundRateLimitedException}: the two
 * inbound-suppression signals every channel entry point can hit.
 */
class SessionExceptionsTest {

    @Test
    void heldByHumanCarriesTheHandoffStateAndMasksTheOperator() {
        ChannelSession session = mock(ChannelSession.class);
        UUID sessionId = UUID.randomUUID();
        when(session.getChannel()).thenReturn(ChannelType.WHATSAPP);
        when(session.getId()).thenReturn(sessionId);
        when(session.getTakenOverBy()).thenReturn("operator-subject-1234");
        when(session.getHandoffState()).thenReturn(HandoffState.HUMAN);

        ConversationHeldByHumanException e = new ConversationHeldByHumanException(session);

        assertEquals(HandoffState.HUMAN, e.getState());
        assertTrue(e.getMessage().contains(sessionId.toString()));
        assertTrue(e.getMessage().contains("***1234"));
        assertFalse(e.getMessage().contains("operator-subject-1234"));
    }

    @Test
    void heldByHumanMasksAShortOrMissingSubject() {
        ChannelSession session = mock(ChannelSession.class);
        when(session.getChannel()).thenReturn(ChannelType.TELEGRAM);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(session.getTakenOverBy()).thenReturn("ab");
        when(session.getHandoffState()).thenReturn(HandoffState.HUMAN);

        assertTrue(new ConversationHeldByHumanException(session).getMessage().contains("***"));
    }

    @Test
    void rateLimitedCarriesRetryAfterAndTheChannelWideFlag() {
        InboundRateLimitedException sender = new InboundRateLimitedException(30, false);
        assertEquals(30, sender.getRetryAfterSeconds());
        assertFalse(sender.isChannelWide());
        assertEquals(InboundRateLimitedException.FRIENDLY_MESSAGE, sender.getMessage());

        InboundRateLimitedException channel = new InboundRateLimitedException(5, true);
        assertTrue(channel.isChannelWide());
    }

    @Test
    void rateLimitedFloorsRetryAfterAtOneSecond() {
        assertEquals(1, new InboundRateLimitedException(0, false).getRetryAfterSeconds());
        assertEquals(1, new InboundRateLimitedException(-5, false).getRetryAfterSeconds());
    }
}
