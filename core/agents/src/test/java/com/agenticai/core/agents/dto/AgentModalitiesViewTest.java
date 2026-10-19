package com.agenticai.core.agents.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.modality.ModelModalities;
import com.agenticai.core.agents.tools.e2b.SandboxDataAvailability;
import com.agenticai.core.agents.tools.e2b.SandboxFileRegistry;
import com.agenticai.core.sdlc.E2bCredentialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * D5: the agent response says WHY file analysis is unavailable - only when the platform flag is on.
 */
class AgentModalitiesViewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static SandboxFileRegistry registry(boolean flagOn, boolean tenantReady) {
        E2bCredentialService creds = mock(E2bCredentialService.class);
        when(creds.isActive()).thenReturn(tenantReady);
        when(creds.hasCodeExecutionTemplate()).thenReturn(tenantReady);
        ObjectProvider<E2bCredentialService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(creds);
        SandboxFileRegistry registry = new SandboxFileRegistry(flagOn, 6, 1000, 10_000, 60);
        registry.setCredentialService(provider);
        return registry;
    }

    @Test
    void flagOffOrNoRegistryCarriesNoReasonAndTheJsonFieldIsOmitted() throws Exception {
        assertSame(SandboxDataAvailability.OFF, SandboxDataAvailability.of(null, true));
        SandboxDataAvailability off = SandboxDataAvailability.of(registry(false, true), true);
        assertFalse(off.available());
        assertNull(off.unavailableReason());

        AgentModalitiesView view =
                AgentModalitiesView.of(
                        ModelModalities.NONE, off.available(), off.unavailableReason());
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(view));
        assertFalse(json.path("sandboxData").asBoolean());
        assertFalse(
                json.has("sandboxDataUnavailableReason"), "default-off response must be unchanged");
        // The pre-existing fields are all still there.
        for (String field :
                new String[] {
                    "image",
                    "audio",
                    "video",
                    "document",
                    "maxAttachmentBytes",
                    "maxAttachments",
                    "sandboxData"
                }) {
            assertTrue(json.has(field), field);
        }
    }

    @Test
    void flagOnAndCodeExecutionOffTellsTheUserToTurnItOn() throws Exception {
        SandboxDataAvailability state = SandboxDataAvailability.of(registry(true, true), false);
        assertFalse(state.available());
        assertTrue(state.unavailableReason().contains("Code execution"));

        AgentModalitiesView view =
                AgentModalitiesView.of(
                        ModelModalities.NONE, state.available(), state.unavailableReason());
        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(view));
        assertEquals(state.unavailableReason(), json.path("sandboxDataUnavailableReason").asText());
        assertFalse(json.path("sandboxData").asBoolean());
    }

    @Test
    void flagOnWithoutE2bTellsTheUserToConnectIt() {
        SandboxDataAvailability state = SandboxDataAvailability.of(registry(true, false), true);
        assertFalse(state.available());
        assertTrue(state.unavailableReason().contains("E2B"));
    }

    @Test
    void availableMeansNoReasonEvenIfOneIsPassed() throws Exception {
        SandboxDataAvailability state = SandboxDataAvailability.of(registry(true, true), true);
        assertTrue(state.available());
        assertNull(state.unavailableReason());

        AgentModalitiesView view =
                AgentModalitiesView.of(ModelModalities.NONE, true, "stale reason");
        assertNull(view.sandboxDataUnavailableReason());
        assertFalse(
                MAPPER.readTree(MAPPER.writeValueAsString(view))
                        .has("sandboxDataUnavailableReason"));
    }

    @Test
    void twoArgFactoryKeepsItsOldMeaning() {
        AgentModalitiesView view = AgentModalitiesView.of(ModelModalities.NONE, true);
        assertTrue(view.sandboxData());
        assertNull(view.sandboxDataUnavailableReason());
        assertNull(AgentModalitiesView.of(null).sandboxDataUnavailableReason());
    }
}
