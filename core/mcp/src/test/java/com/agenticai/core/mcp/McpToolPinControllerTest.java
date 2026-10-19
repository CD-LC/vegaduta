package com.agenticai.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class McpToolPinControllerTest {

    private final UUID id = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private McpToolPinService.PinStatus status(boolean changed) {
        return new McpToolPinService.PinStatus(id, "srv", "enforce", true, changed, "p", "o", null, "admin", null,
            List.of(), List.of(), changed ? List.of("t") : List.of());
    }

    @Test
    void approveRecordsTheActorAndNotifiesTheAuditListener() {
        McpToolPinService pins = mock(McpToolPinService.class);
        McpToolPinApprovalListener listener = mock(McpToolPinApprovalListener.class);
        McpToolPin.Diff diff = new McpToolPin.Diff(List.of(), List.of(), List.of("t"));
        when(pins.approveWithDiff(id, "admin@example.com")).thenReturn(new McpToolPinService.Approval(status(false), diff));
        TenantContext.set(TenantContext.of("tenant_a", "admin@example.com", Set.of()));

        var result = new McpToolPinController(pins, Optional.of(listener)).approve(id);

        assertThat(result.changed()).isFalse();
        verify(listener).onApproved(eq(id), eq("srv"), eq("admin@example.com"), eq("p"), eq(diff));
    }

    @Test
    void approveWorksWithoutAnAuditListener() {
        McpToolPinService pins = mock(McpToolPinService.class);
        when(pins.approveWithDiff(any(), any())).thenReturn(new McpToolPinService.Approval(status(false),
            new McpToolPin.Diff(List.of(), List.of(), List.of())));
        assertThat(new McpToolPinController(pins, Optional.empty()).approve(id).registrationId()).isEqualTo(id);
    }

    @Test
    void readsDelegateToTheServiceAndNeverApprove() {
        McpToolPinService pins = mock(McpToolPinService.class);
        McpToolPinApprovalListener listener = mock(McpToolPinApprovalListener.class);
        when(pins.changedStatuses()).thenReturn(List.of(status(true)));
        when(pins.status(id)).thenReturn(status(true));
        var controller = new McpToolPinController(pins, Optional.of(listener));
        assertThat(controller.changed()).hasSize(1);
        assertThat(controller.get(id).changedTools()).containsExactly("t");
        verifyNoInteractions(listener);
    }
}
