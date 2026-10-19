package com.agenticai.core.agents.crew;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.crew.AgentSpecialtyService.Specialty;
import com.agenticai.core.sharing.PermissionLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link AgentSpecialtyService} with collaborators mocked: validation, access gating and replace-set semantics. */
class AgentSpecialtyServiceTest {

    private AgentService agentService;
    private SpecialistRegistry registry;
    private AgentSpecialtyService service;
    private final UUID agentId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        registry = mock(SpecialistRegistry.class);
        service = new AgentSpecialtyService(agentService, registry);
        when(registry.register(any(UUID.class), anyString(), any()))
            .thenAnswer(inv -> new AgentSpecialization(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
    }

    @Test
    void replaceRegistersNewTagsNormalizedAndDeregistersRemovedOnes() {
        when(registry.findByAgent(agentId)).thenReturn(List.of(
            new AgentSpecialization(agentId, "legacy", null),
            new AgentSpecialization(agentId, "billing", "old")));

        List<AgentSpecialization> result = service.replaceForAgent(agentId, List.of(
            new Specialty("  Billing ", " refunds "), new Specialty("tax-advice", "  ")));

        verify(agentService).requireAccess(agentId, PermissionLevel.EDITOR);
        verify(registry).deregister(agentId, "legacy");
        verify(registry, never()).deregister(agentId, "billing");
        verify(registry).register(agentId, "billing", "refunds");
        verify(registry).register(agentId, "tax-advice", null);
        assertEquals(List.of("billing", "tax-advice"), result.stream().map(AgentSpecialization::getCapabilityTag).toList());
    }

    @Test
    void replaceWithEmptyListClearsEverything() {
        when(registry.findByAgent(agentId)).thenReturn(List.of(new AgentSpecialization(agentId, "billing", null)));
        assertEquals(0, service.replaceForAgent(agentId, List.of()).size());
        verify(registry).deregister(agentId, "billing");
    }

    @Test
    void duplicateTagsCollapseToOneRegistration() {
        when(registry.findByAgent(agentId)).thenReturn(List.of());
        service.replaceForAgent(agentId, List.of(new Specialty("Billing", "a"), new Specialty("billing", "b")));
        verify(registry).register(agentId, "billing", "b");
        verify(registry, org.mockito.Mockito.times(1)).register(eq(agentId), anyString(), any());
    }

    @Test
    void accessDeniedStopsBeforeAnyWrite() {
        doThrow(new SecurityException("denied")).when(agentService).requireAccess(agentId, PermissionLevel.EDITOR);
        assertThrows(SecurityException.class, () -> service.replaceForAgent(agentId, List.of(new Specialty("billing", null))));
        verify(registry, never()).register(any(), anyString(), any());
        verify(registry, never()).deregister(any(), anyString());
    }

    @Test
    void listRequiresViewerAccess() {
        when(registry.findByAgent(agentId)).thenReturn(List.of(new AgentSpecialization(agentId, "b", null)));
        assertEquals(1, service.list(agentId).size());
        verify(agentService).requireAccess(agentId, PermissionLevel.VIEWER);
    }

    @Test
    void validationRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> AgentSpecialtyService.validate(null));
        assertThrows(IllegalArgumentException.class, () -> AgentSpecialtyService.validate(List.of(new Specialty(" ", null))));
        assertThrows(IllegalArgumentException.class, () -> AgentSpecialtyService.validate(List.of(new Specialty("has space", null))));
        assertThrows(IllegalArgumentException.class, () -> AgentSpecialtyService.validate(List.of(new Specialty("-lead", null))));
        assertThrows(IllegalArgumentException.class, () -> AgentSpecialtyService.validate(List.of(new Specialty("a".repeat(51), null))));
        assertThrows(IllegalArgumentException.class, () -> AgentSpecialtyService.validate(List.of(new Specialty("ok", "d".repeat(501)))));
    }

    @Test
    void validationRejectsTooManySpecialties() {
        List<Specialty> many = new ArrayList<>();
        for (int i = 0; i <= AgentSpecialtyService.MAX_SPECIALTIES_PER_AGENT; i++) {
            many.add(new Specialty("tag" + i, null));
        }
        assertThrows(IllegalArgumentException.class, () -> AgentSpecialtyService.validate(many));
    }
}
