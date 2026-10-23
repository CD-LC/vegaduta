package com.agenticai.core.agents.limits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.ToolOutputProperties;
import com.agenticai.core.guardrails.ToolStepLimitProperties;
import com.agenticai.core.sharing.PermissionLevel;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;

/** The write endpoints of the runtime-limit overrides: authorization before validation, 400 on out-of-bounds values. */
class RuntimeLimitsControllersTest {

    private static final UUID AGENT = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final AgentService agents = mock(AgentService.class);
    private final TenantRuntimeLimitsService tenantService = mock(TenantRuntimeLimitsService.class);
    private final AgentRuntimeSettingsResolver resolver = new AgentRuntimeSettingsResolver(
            new AgentRuntimeProperties(),
            new AgentRuntimeSettingsResolver.PlatformKeys(15, Duration.ofSeconds(240), 8),
            new ToolStepLimitProperties(),
            new ToolOutputProperties(),
            new ToolLoopProperties(),
            tenantService,
            agents);

    private static RuntimeLimits tooManySteps() {
        return new RuntimeLimits(1_000_000, null, null, null, null, null, null);
    }

    @Test
    void aNonEditorGets403NotA400ForOutOfBoundsValues() {
        when(agents.requireAccess(AGENT, PermissionLevel.EDITOR)).thenThrow(new AccessDeniedException("no"));
        AgentRuntimeLimitsController controller = new AgentRuntimeLimitsController(agents, resolver);

        assertThrows(AccessDeniedException.class, () -> controller.put(AGENT, tooManySteps()));

        verify(agents, never()).setRuntimeLimits(any(), any());
    }

    @Test
    void anEditorWritingOutOfBoundsValuesGets400AndNothingIsStored() {
        AgentRuntimeLimitsController controller = new AgentRuntimeLimitsController(agents, resolver);

        ResponseStatusException e =
                assertThrows(ResponseStatusException.class, () -> controller.put(AGENT, tooManySteps()));

        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        assertTrue(e.getReason().contains("maxToolSteps"), e.getReason());
        verify(agents, never()).setRuntimeLimits(any(), any());
    }

    @Test
    void anEditorWritingValidValuesStoresThemAfterTheAccessCheck() {
        AgentRuntimeLimitsController controller = new AgentRuntimeLimitsController(agents, resolver);
        RuntimeLimits ok = new RuntimeLimits(20, null, null, null, null, null, null);
        when(agents.getRuntimeLimits(AGENT)).thenReturn(ok);

        controller.put(AGENT, ok);

        InOrder order = inOrder(agents);
        order.verify(agents).requireAccess(AGENT, PermissionLevel.EDITOR);
        order.verify(agents).setRuntimeLimits(AGENT, ok);
    }

    @Test
    void readingNeedsOnlyViewer() {
        AgentRuntimeLimitsController controller = new AgentRuntimeLimitsController(agents, resolver);
        when(agents.getRuntimeLimits(AGENT)).thenReturn(RuntimeLimits.none());

        controller.get(AGENT);

        verify(agents).requireAccess(AGENT, PermissionLevel.VIEWER);
        verify(agents, never()).requireAccess(AGENT, PermissionLevel.EDITOR);
    }

    @Test
    void theTenantEndpointsAreTenantAdminOnlyAndRefuseOutOfBoundsValues() {
        PreAuthorize guard = TenantRuntimeLimitsController.class.getAnnotation(PreAuthorize.class);
        assertEquals(TenantRuntimeLimitsController.TENANT_ADMIN, guard.value());
        assertTrue(guard.value().contains("ROLE_tenant-admin"));

        TenantRuntimeLimitsController controller = new TenantRuntimeLimitsController(tenantService, resolver);
        ResponseStatusException e =
                assertThrows(ResponseStatusException.class, () -> controller.put(tooManySteps()));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        verify(tenantService, never()).replace(any());
    }
}
