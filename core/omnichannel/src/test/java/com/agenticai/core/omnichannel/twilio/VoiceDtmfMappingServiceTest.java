package com.agenticai.core.omnichannel.twilio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.omnichannel.twilio.dto.VoiceDtmfMappingResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link VoiceDtmfMappingService}: the per-digit IVR routing table and its hot-path read. */
class VoiceDtmfMappingServiceTest {

    private static final UUID AGENT_ID = UUID.randomUUID();

    private VoiceDtmfMappingRepository repository;
    private AgentRepository agentRepository;
    private VoiceDtmfMappingService service;

    @BeforeEach
    void setUp() {
        repository = mock(VoiceDtmfMappingRepository.class);
        agentRepository = mock(AgentRepository.class);
        service = new VoiceDtmfMappingService(repository, agentRepository);
    }

    private Agent agentNamed(String name) {
        Agent agent = mock(Agent.class);
        when(agent.getName()).thenReturn(name);
        return agent;
    }

    @Test
    void listDenormalizesTheAgentNameOntoEachRow() {
        VoiceDtmfMapping mapping = new VoiceDtmfMapping("2", AGENT_ID, "Support");
        Agent supportBot = agentNamed("Support Bot");
        when(repository.findAllByOrderByDigitAsc()).thenReturn(List.of(mapping));
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(supportBot));

        List<VoiceDtmfMappingResponse> rows = service.list();
        assertEquals(1, rows.size());
        assertEquals("2", rows.get(0).digit());
        assertEquals(AGENT_ID, rows.get(0).agentId());
        assertEquals("Support Bot", rows.get(0).agentName());
        assertEquals("Support", rows.get(0).label());
    }

    @Test
    void listLabelsADeletedAgentUnknown() {
        VoiceDtmfMapping mapping = new VoiceDtmfMapping("9", AGENT_ID, null);
        when(repository.findAllByOrderByDigitAsc()).thenReturn(List.of(mapping));
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

        assertEquals("(deleted agent)", service.list().get(0).agentName());
    }

    @Test
    void upsertCreatesANewRowWhenTheDigitIsUnmapped() {
        Agent agent = agentNamed("Sales Bot");
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
        when(repository.findByDigit("2")).thenReturn(Optional.empty());
        when(repository.save(any(VoiceDtmfMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        VoiceDtmfMappingResponse response = service.upsert("2", AGENT_ID, "Sales");

        assertEquals("2", response.digit());
        assertEquals(AGENT_ID, response.agentId());
        assertEquals("Sales Bot", response.agentName());
        verify(repository).save(any(VoiceDtmfMapping.class));
    }

    @Test
    void upsertUpdatesTheExistingRowInPlace() {
        VoiceDtmfMapping existing = new VoiceDtmfMapping("2", UUID.randomUUID(), "Old");
        Agent salesBot = agentNamed("Sales Bot");
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(salesBot));
        when(repository.findByDigit("2")).thenReturn(Optional.of(existing));

        VoiceDtmfMappingResponse response = service.upsert("2", AGENT_ID, "Sales");

        assertEquals(AGENT_ID, existing.getAgentId());
        assertEquals("Sales", existing.getLabel());
        assertEquals("Sales", response.label());
        verify(repository, never()).save(any());
    }

    @Test
    void upsertRejectsAnUnknownAgent() {
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.empty());

        assertThrows(AgentNotFoundException.class, () -> service.upsert("2", AGENT_ID, "Sales"));
        verify(repository, never()).save(any());
    }

    @Test
    void upsertRejectsAnythingThatIsNotASingleDtmfDigit() {
        for (String bad : new String[] {null, "", "12", "a", " ", "!"}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.upsert(bad, AGENT_ID, "x"),
                    "digit: " + bad);
        }
        verifyNoInteractions(agentRepository);
    }

    @Test
    void deleteRemovesAnExistingMappingOrThrows() {
        when(repository.findByDigit("2")).thenReturn(Optional.empty());
        assertThrows(VoiceDtmfMappingNotFoundException.class, () -> service.delete("2"));

        when(repository.findByDigit("2"))
                .thenReturn(Optional.of(new VoiceDtmfMapping("2", AGENT_ID, null)));
        service.delete("2");
        verify(repository).deleteByDigit("2");
    }

    @Test
    void findAgentIdForDigitIsQuietlyEmptyForAnythingUnmappedOrInvalid() {
        assertTrue(service.findAgentIdForDigit(null).isEmpty());
        assertTrue(service.findAgentIdForDigit("12").isEmpty());
        assertTrue(service.findAgentIdForDigit("a").isEmpty());

        when(repository.findByDigit("5")).thenReturn(Optional.empty());
        assertTrue(service.findAgentIdForDigit("5").isEmpty());

        when(repository.findByDigit("5"))
                .thenReturn(Optional.of(new VoiceDtmfMapping("5", AGENT_ID, null)));
        assertEquals(Optional.of(AGENT_ID), service.findAgentIdForDigit("5"));
    }
}
