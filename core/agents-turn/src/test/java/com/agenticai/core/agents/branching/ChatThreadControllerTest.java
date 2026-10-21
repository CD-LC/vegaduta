package com.agenticai.core.agents.branching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.branching.ChatBranchDtos.ThreadsResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link ChatThreadController} is a thin pass-through: the service decides everything, including
 * the 404s.
 */
class ChatThreadControllerTest {

    private final ChatThreadService service = mock(ChatThreadService.class);
    private final ChatThreadController controller = new ChatThreadController(service);
    private final UUID agentId = UUID.randomUUID();

    @Test
    void listPassesLimitAndCursorThroughAndReturnsThePage() {
        Instant before = Instant.parse("2026-10-20T10:00:00Z");
        ThreadsResponse page = new ThreadsResponse(List.of(), null);
        when(service.list(20, before)).thenReturn(page);

        assertSame(page, controller.list(20, before));
    }

    @Test
    void deleteAnswers204WhenTheServiceAccepts() {
        ResponseEntity<Void> response = controller.delete(agentId, "s1");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).delete(agentId, "s1");
    }

    @Test
    void theServices404ReachesTheClientUnchanged() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found."))
                .when(service)
                .delete(agentId, "not-mine");
        when(service.list(null, null)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        ResponseStatusException onDelete =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.delete(agentId, "not-mine"));
        ResponseStatusException onList =
                assertThrows(ResponseStatusException.class, () -> controller.list(null, null));

        assertEquals(HttpStatus.NOT_FOUND, onDelete.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, onList.getStatusCode());
    }
}
