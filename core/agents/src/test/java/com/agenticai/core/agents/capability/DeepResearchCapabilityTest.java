package com.agenticai.core.agents.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolCredentialStatusResponse;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.security.TtlCache;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** Availability matrix and row-backed state of the "Deep research" capability. */
class DeepResearchCapabilityTest {

    private AgentCapabilityStateRepository states;
    private PlatformToolCredentialService credentials;
    private ObjectProvider<PlatformToolCredentialService> credentialProvider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        states = mock(AgentCapabilityStateRepository.class);
        credentials = mock(PlatformToolCredentialService.class);
        credentialProvider = mock(ObjectProvider.class);
        when(credentialProvider.getIfAvailable()).thenReturn(credentials);
    }

    private static PlatformToolCredentialStatusResponse configured(boolean on) {
        return new PlatformToolCredentialStatusResponse(PlatformToolKey.BRAVE_SEARCH, on, null, null, null);
    }

    @Test
    void availabilityNeverDecryptsTheSearchKey() {
        when(credentials.status(PlatformToolKey.BRAVE_SEARCH)).thenReturn(configured(true));
        assertEquals(CapabilityAvailability.AVAILABLE,
            new DeepResearchCapability(states, true, credentialProvider).availability());
        verify(credentials, never()).resolveActiveApiKey(any());
    }

    @Test
    void flippingTheSwitchEvictsTheResolversCachedAnswerForThatAgentOnly() {
        TtlCache<Boolean> cache = new TtlCache<>(DeepResearchCapability.CACHE_NAME, Duration.ofMinutes(5), 100);
        UUID agentId = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        cache.get(DeepResearchCapability.cacheKey(agentId), () -> Boolean.FALSE);
        cache.get(DeepResearchCapability.cacheKey(other), () -> Boolean.FALSE);
        Agent agent = mock(Agent.class);
        when(agent.getId()).thenReturn(agentId);
        when(states.findById(any())).thenReturn(Optional.empty());

        new DeepResearchCapability(states, true, credentialProvider).apply(agent, true);

        assertEquals(Boolean.TRUE, cache.get(DeepResearchCapability.cacheKey(agentId), () -> Boolean.TRUE));
        assertEquals(Boolean.FALSE, cache.get(DeepResearchCapability.cacheKey(other), () -> Boolean.TRUE));
    }

    @Test
    void unavailableWhenTheDeploymentFlagIsOffEvenWithASearchKey() {
        when(credentials.status(PlatformToolKey.BRAVE_SEARCH)).thenReturn(configured(true));
        DeepResearchCapability capability = new DeepResearchCapability(states, false, credentialProvider);
        assertEquals(CapabilityAvailability.UNAVAILABLE, capability.availability());
        assertEquals(DeepResearchCapability.HINT_DISABLED, capability.availabilityHint());
    }

    @Test
    void needsSetupWhenEnabledButNoSearchKey() {
        when(credentials.status(PlatformToolKey.BRAVE_SEARCH)).thenReturn(configured(false));
        DeepResearchCapability capability = new DeepResearchCapability(states, true, credentialProvider);
        assertEquals(CapabilityAvailability.NEEDS_SETUP, capability.availability());
        assertEquals(DeepResearchCapability.HINT_NO_SEARCH_KEY, capability.availabilityHint());
    }

    @Test
    @SuppressWarnings("unchecked")
    void needsSetupWhenTheCredentialServiceIsAbsentOrThrows() {
        ObjectProvider<PlatformToolCredentialService> none = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);
        assertEquals(CapabilityAvailability.NEEDS_SETUP,
            new DeepResearchCapability(states, true, none).availability());
        when(credentials.status(PlatformToolKey.BRAVE_SEARCH))
            .thenThrow(new IllegalStateException("db down"));
        assertEquals(CapabilityAvailability.NEEDS_SETUP,
            new DeepResearchCapability(states, true, credentialProvider).availability());
    }

    @Test
    void availableWithNoHintWhenEnabledAndKeyPresent() {
        when(credentials.status(PlatformToolKey.BRAVE_SEARCH)).thenReturn(configured(true));
        DeepResearchCapability capability = new DeepResearchCapability(states, true, credentialProvider);
        assertEquals(CapabilityAvailability.AVAILABLE, capability.availability());
        assertNull(capability.availabilityHint());
    }

    @Test
    void wireShapeIsStableAndTheDescriptionSaysTheCheckIsWordingOnly() {
        DeepResearchCapability capability = new DeepResearchCapability(states, true, credentialProvider);
        assertEquals("deep-research", capability.key());
        assertEquals("Deep research", capability.label());
        assertEquals("tools", capability.category());
        assertTrue(capability.description().contains("quoted citations"));
        assertTrue(capability.description().contains("wording only"));
    }

    @Test
    void stateIsARowInAgentCapabilityAndAMissingRowReadsAsOff() {
        UUID agentId = UUID.randomUUID();
        Agent agent = mock(Agent.class);
        when(agent.getId()).thenReturn(agentId);
        when(states.findById(any())).thenReturn(Optional.empty());
        DeepResearchCapability capability = new DeepResearchCapability(states, true, credentialProvider);
        assertFalse(capability.isEnabled(agent));

        capability.apply(agent, true);
        ArgumentCaptor<AgentCapabilityState> saved = ArgumentCaptor.forClass(AgentCapabilityState.class);
        verify(states).save(saved.capture());
        assertEquals(agentId, saved.getValue().getAgentId());
        assertEquals("deep-research", saved.getValue().getCapabilityKey());
        assertTrue(saved.getValue().isEnabled());

        when(states.findById(new AgentCapabilityStateId(agentId, "deep-research")))
            .thenReturn(Optional.of(saved.getValue()));
        assertTrue(capability.isEnabled(agent));
    }
}
