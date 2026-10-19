package com.agenticai.core.agents.usermemory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.MemoryProvenanceService;
import java.security.Principal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class UserMemoryProvenanceControllerTest {

    private final MemoryProvenanceService service = mock(MemoryProvenanceService.class);
    private final Principal alice = () -> "alice";
    private final UUID id = UUID.randomUUID();

    @Test
    void flagOffAnswers404WithoutReadingAnything() {
        UserMemoryProvenanceController c = new UserMemoryProvenanceController(service, false);
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> c.source("agent", id, alice));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(service, never()).sourceOf(any(), any(), any());
    }

    @Test
    void flagOnReturnsTheSourceAndMapsMissingAndBadScope() {
        UserMemoryProvenanceController c = new UserMemoryProvenanceController(service, true);
        MemoryProvenanceService.FactSource source = new MemoryProvenanceService.FactSource(null, null, Instant.EPOCH, false);
        when(service.sourceOf("agent", id, "alice")).thenReturn(Optional.of(source));
        assertSame(source, c.source("agent", id, alice));

        when(service.sourceOf("shared", id, "alice")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () -> c.source("shared", id, alice)).getStatusCode());

        when(service.sourceOf("bogus", id, "alice")).thenThrow(new IllegalArgumentException("Unknown memory scope."));
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () -> c.source("bogus", id, alice)).getStatusCode());

        assertEquals(HttpStatus.UNAUTHORIZED,
            assertThrows(ResponseStatusException.class, () -> c.source("agent", id, null)).getStatusCode());
    }
}
