package com.agenticai.core.agents.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.a2a.A2aAgentCardService;
import com.agenticai.core.agents.core.AgentService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** offset + total on the public directory list; the old entries/count body is unchanged. */
class AgentDirectoryControllerPagingTest {

    private final AgentDirectoryService service = mock(AgentDirectoryService.class);
    private final AgentDirectoryController controller =
            new AgentDirectoryController(service, mock(AgentService.class), mock(A2aAgentCardService.class));

    @Test
    void offsetIsPassedAndTotalComesFromTheCount() {
        when(service.listPublished("x", 20, 40L)).thenReturn(List.of());
        when(service.countPublished("x")).thenReturn(95L);

        ResponseEntity<Map<String, Object>> r = controller.list("x", 20, 40L);

        verify(service).listPublished("x", 20, 40L);
        assertEquals(95L, r.getBody().get("total"));
        assertEquals(0, r.getBody().get("count"));
        assertEquals(true, r.getBody().get("hasMore"));
        assertEquals("95", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void shortFirstPageNeedsNoCountQuery() {
        when(service.listPublished(null, 50, 0L)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> r = controller.list(null, 50, null);

        verify(service, never()).countPublished(null);
        assertEquals(0L, r.getBody().get("total"));
        assertEquals(false, r.getBody().get("hasMore"));
        assertEquals("0", r.getHeaders().getFirst("X-Total-Count"));
    }
}
