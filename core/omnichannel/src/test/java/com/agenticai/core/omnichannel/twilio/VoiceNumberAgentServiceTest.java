package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Number normalization and lookup semantics for per-number voice routing.
 *
 * <p>The normalization half carries the real risk: an admin types a number
 * the way a human writes one ({@code "+1 (555) 123-4567"}) and Twilio sends
 * the same number as strict E.164 ({@code "+15551234567"}). If those two do
 * not converge on one stored value the mapping silently never matches, and
 * the symptom is not an error - it is calls quietly continuing to reach the
 * tenant-wide default agent, which looks exactly like the feature not being
 * deployed. Hence a test per separator shape rather than one happy path.
 *
 * <p>Same manual {@code mock(X.class)} + constructor-injection shape as
 * {@link TwilioSmsWebhookControllerDedupTest}.
 */
class VoiceNumberAgentServiceTest {

    private static final String CANONICAL = "+15551234567";

    private VoiceNumberAgentRepository repository;
    private AgentRepository agentRepository;
    private VoiceNumberAgentService service;

    @BeforeEach
    void setUp() {
        repository = mock(VoiceNumberAgentRepository.class);
        agentRepository = mock(AgentRepository.class);
        service = new VoiceNumberAgentService(repository, agentRepository);
    }

    @Test
    void humanWrittenSeparatorsNormalizeToTheSameStoredNumber() {
        when(repository.findByPhoneNumber(CANONICAL)).thenReturn(Optional.of(mapping(UUID.randomUUID())));

        for (String typed : new String[] {
            "+15551234567",
            "+1 555 123 4567",
            "+1 (555) 123-4567",
            "+1-555-123-4567",
            "+1.555.123.4567",
            "  +15551234567  ",
        }) {
            assertTrue(
                service.findAgentIdForNumber(typed).isPresent(),
                "expected '" + typed + "' to normalize to " + CANONICAL
            );
        }
    }

    @Test
    void lookupOfAnUnmappedNumberIsEmptyRatherThanAnError() {
        when(repository.findByPhoneNumber(CANONICAL)).thenReturn(Optional.empty());

        assertTrue(service.findAgentIdForNumber(CANONICAL).isEmpty());
    }

    /**
     * The hot-path read takes caller-influenceable input (see {@link
     * VoiceRelayWebSocketHandler#resolveDialedNumber}), so garbage must be a
     * quiet empty - never an exception that would break a live call, and
     * never a database round trip.
     */
    @Test
    void lookupOfAMalformedNumberIsEmptyAndNeverHitsTheDatabase() {
        for (String malformed : new String[] {
            null, "", "   ", "not-a-number", "15551234567", "+0155512345", "+1", "+1555123456789012345",
            "+1555123456a", "'; DROP TABLE voice_number_agent; --",
        }) {
            assertTrue(
                service.findAgentIdForNumber(malformed).isEmpty(),
                "expected '" + malformed + "' to be rejected"
            );
        }
        verify(repository, never()).findByPhoneNumber(any());
    }

    @Test
    void upsertStoresTheNormalizedFormNotWhatTheAdminTyped() {
        UUID agentId = UUID.randomUUID();
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn("Sales");
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(repository.findByPhoneNumber(CANONICAL)).thenReturn(Optional.empty());
        when(repository.save(any(VoiceNumberAgent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.upsert("+1 (555) 123-4567", agentId, "Mumbai sales line");

        assertEquals(CANONICAL, response.phoneNumber());
        assertEquals(agentId, response.agentId());
        assertEquals("Sales", response.agentName());
        assertEquals("Mumbai sales line", response.label());
    }

    /** Admin-facing writes fail loudly - the opposite of the hot-path read above, and deliberately so. */
    @Test
    void upsertRejectsANumberThatIsNotE164() {
        UUID agentId = UUID.randomUUID();

        assertThrows(
            IllegalArgumentException.class,
            () -> service.upsert("555-1234", agentId, "Bad")
        );
        verify(agentRepository, never()).findById(any());
    }

    @Test
    void deleteOfAnUnmappedNumberIsNotFoundRatherThanASilentNoOp() {
        when(repository.findByPhoneNumber(CANONICAL)).thenReturn(Optional.empty());

        assertThrows(
            VoiceNumberAgentNotFoundException.class,
            () -> service.delete(CANONICAL)
        );
        verify(repository, never()).deleteByPhoneNumber(any());
    }

    private static VoiceNumberAgent mapping(UUID agentId) {
        return new VoiceNumberAgent(CANONICAL, agentId, "test");
    }
}
