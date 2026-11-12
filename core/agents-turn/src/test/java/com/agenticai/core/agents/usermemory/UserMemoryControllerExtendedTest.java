package com.agenticai.core.agents.usermemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@SuppressWarnings("unchecked")
class UserMemoryControllerExtendedTest extends UserMemoryControllerTestBase {
    @Test
    void anEditorOfTheWritingAgentApprovesTheirOwnQuarantinedSharedFact() {
        UUID id = UUID.randomUUID();
        UUID writer = UUID.randomUUID();
        UserMemoryController c = controller();
        when(tenant.listFactsForSubject("alice"))
                .thenReturn(List.of(quarantinedShared(id, writer)));
        when(tenant.approveFact(id, "alice")).thenReturn(true);

        assertEquals(HttpStatus.NO_CONTENT, c.approveSharedFact(id, alice).getStatusCode());

        verify(agentService).requireAccess(writer, PermissionLevel.EDITOR);
        verify(tenant).approveFact(id, "alice");
    }

    @Test
    void approvingASharedFactWithoutEditorOnTheWritingAgentIsDeniedAndApprovesNothing() {
        UUID id = UUID.randomUUID();
        UUID writer = UUID.randomUUID();
        UserMemoryController c = controller();
        when(tenant.listFactsForSubject("alice"))
                .thenReturn(List.of(quarantinedShared(id, writer)));
        doThrow(new ResourceAccessDeniedException())
                .when(agentService)
                .requireAccess(writer, PermissionLevel.EDITOR);

        assertThrows(ResourceAccessDeniedException.class, () -> c.approveSharedFact(id, alice));
        verify(tenant, never()).approveFact(any(), any());
    }

    @Test
    void aSharedFactWithNoRecordedWriterCannotBeApprovedHere() {
        UUID id = UUID.randomUUID();
        UserMemoryController c = controller();
        when(tenant.listFactsForSubject("alice")).thenReturn(List.of(quarantinedShared(id, null)));

        ResponseStatusException e =
                assertThrows(ResponseStatusException.class, () -> c.approveSharedFact(id, alice));
        assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
        verify(tenant, never()).approveFact(any(), any());
    }

    @Test
    void approvingSomeoneElsesTrustedOrMissingSharedFactIs404() {
        UUID id = UUID.randomUUID();
        UserMemoryController c = controller();
        Instant now = Instant.now();
        when(tenant.listFactsForSubject("alice"))
                .thenReturn(
                        List.of(
                                new TenantMemoryEntry(
                                        id,
                                        "alice",
                                        "already trusted",
                                        0,
                                        UUID.randomUUID(),
                                        now,
                                        now,
                                        0)));

        assertEquals(HttpStatus.NOT_FOUND, c.approveSharedFact(id, alice).getStatusCode());
        assertEquals(
                HttpStatus.NOT_FOUND,
                c.approveSharedFact(UUID.randomUUID(), alice).getStatusCode());
        verify(tenant, never()).approveFact(any(), any());
    }

    @Test
    void approvingASharedFactNeedsASignedInCaller() {
        UserMemoryController c = controller();
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () -> c.approveSharedFact(UUID.randomUUID(), null));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatusCode());
    }
}
