package com.agenticai.core.agents.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.capability.AgentCapabilityState;
import com.agenticai.core.agents.capability.AgentCapabilityStateId;
import com.agenticai.core.agents.capability.AgentCapabilityStateRepository;
import com.agenticai.core.agents.capability.DeepResearchCapability;
import com.agenticai.core.agents.tools.web.DeepResearchSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Result-source sanitising and the submit gate that applies the run cap to direct jobs. */
class ResearchResultSanitizerAndGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode result(String... urls) throws Exception {
        StringBuilder sources = new StringBuilder();
        for (int i = 0; i < urls.length; i++) {
            sources.append(i == 0 ? "" : ",")
                    .append("{\"id\":\"S")
                    .append(i + 1)
                    .append("\",\"url\":\"")
                    .append(urls[i])
                    .append("\"}");
        }
        return MAPPER.readTree(
                "{\"title\":\"t\",\"reportMarkdown\":\"r\",\"sources\":[" + sources + "]}");
    }

    private static int count(JsonNode result) {
        return result.get("sources").size();
    }

    @Test
    void onlyAbsoluteHttpAndHttpsLinksWithAHostSurvive() throws Exception {
        JsonNode out =
                ResearchResultSanitizer.sanitize(
                        result(
                                "https://ok.example/a",
                                "http://ok.example/b",
                                "javascript:alert(1)",
                                "data:text/html,<b>x</b>",
                                "JAVASCRIPT:alert(1)",
                                "//evil.example/x",
                                "/relative",
                                "ftp://files.example/x",
                                "https:///nohost"),
                        null);
        assertEquals(2, count(out));
        assertEquals("https://ok.example/a", out.get("sources").get(0).get("url").asText());
        assertEquals("http://ok.example/b", out.get("sources").get(1).get("url").asText());
    }

    @Test
    void withTheFetchedSetOnlyPagesTheSessionReallyReadSurvive() throws Exception {
        DeepResearchSession session = new DeepResearchSession(12, 12);
        session.add("https://www.read.example/page?utm_source=x", "Read", "text");
        JsonNode out =
                ResearchResultSanitizer.sanitize(
                        result("https://read.example/page", "https://invented.example/never-read"),
                        session.fetchedUrls());
        assertEquals(1, count(out));
        assertEquals("https://read.example/page", out.get("sources").get(0).get("url").asText());
    }

    @Test
    void nullAndShapelessResultsPassThrough() throws Exception {
        assertNull(ResearchResultSanitizer.sanitize(null, null));
        JsonNode noSources = MAPPER.readTree("{\"title\":\"t\"}");
        assertEquals(noSources, ResearchResultSanitizer.sanitize(noSources, Set.of()));
        JsonNode prose = MAPPER.readTree("\"just text\"");
        assertEquals(prose, ResearchResultSanitizer.sanitize(prose, null));
    }

    // ----------------------------------------------------------------- gate

    private static ResearchRunUsageService usage() {
        return mock(ResearchRunUsageService.class);
    }

    @Test
    void anAgentWithTheCapabilityIsGatedAndTheReservationCanBeGivenBack() {
        AgentCapabilityStateRepository states = mock(AgentCapabilityStateRepository.class);
        ResearchRunUsageService usage = usage();
        UUID agentId = UUID.randomUUID();
        when(states.findById(new AgentCapabilityStateId(agentId, DeepResearchCapability.CAPABILITY_KEY)))
                .thenReturn(
                        Optional.of(
                                new AgentCapabilityState(
                                        agentId, DeepResearchCapability.CAPABILITY_KEY, true)));

        Runnable release =
                new ResearchAgentSubmitGate(states, usage).reserve(agentId, "t1", "alice");
        assertNotNull(release);
        verify(usage).reserve("t1", "alice");
        verify(usage, never()).release(any(), any());
        release.run();
        verify(usage).release("t1", "alice");
    }

    @Test
    void theCapRefusalPropagatesAs429() {
        AgentCapabilityStateRepository states = mock(AgentCapabilityStateRepository.class);
        ResearchRunUsageService usage = usage();
        UUID agentId = UUID.randomUUID();
        when(states.findById(new AgentCapabilityStateId(agentId, DeepResearchCapability.CAPABILITY_KEY)))
                .thenReturn(
                        Optional.of(
                                new AgentCapabilityState(
                                        agentId, DeepResearchCapability.CAPABILITY_KEY, true)));
        org.mockito.Mockito.doThrow(
                        new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "used"))
                .when(usage)
                .reserve("t1", "alice");

        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                new ResearchAgentSubmitGate(states, usage)
                                        .reserve(agentId, "t1", "alice"));
        assertEquals(429, e.getStatusCode().value());
    }

    @Test
    void normalAgentsDisabledCapabilitiesAndUserlessCallsAreNotGated() {
        AgentCapabilityStateRepository states = mock(AgentCapabilityStateRepository.class);
        ResearchRunUsageService usage = usage();
        UUID plain = UUID.randomUUID();
        UUID off = UUID.randomUUID();
        when(states.findById(new AgentCapabilityStateId(plain, DeepResearchCapability.CAPABILITY_KEY)))
                .thenReturn(Optional.empty());
        when(states.findById(new AgentCapabilityStateId(off, DeepResearchCapability.CAPABILITY_KEY)))
                .thenReturn(
                        Optional.of(
                                new AgentCapabilityState(off, DeepResearchCapability.CAPABILITY_KEY, false)));
        ResearchAgentSubmitGate gate = new ResearchAgentSubmitGate(states, usage);

        assertNull(gate.reserve(plain, "t1", "alice"));
        assertNull(gate.reserve(off, "t1", "alice"));
        assertNull(gate.reserve(plain, "t1", null));
        assertNull(gate.reserve(plain, "t1", " "));
        verify(usage, never()).reserve(any(), any());
    }

    @Test
    void aBrokenCapabilityLookupNeverBlocksJobSubmission() {
        AgentCapabilityStateRepository states = mock(AgentCapabilityStateRepository.class);
        when(states.findById(any())).thenThrow(new IllegalStateException("db"));
        assertNull(
                new ResearchAgentSubmitGate(states, usage())
                        .reserve(UUID.randomUUID(), "t1", "alice"));
        assertFalse(ResearchResultSanitizer.isWebUrl(null));
        assertTrue(ResearchResultSanitizer.isWebUrl(" https://a.example "));
    }
}
