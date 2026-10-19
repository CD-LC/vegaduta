package com.agenticai.core.agents.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.a2a.A2aAgentCardService;
import com.agenticai.core.agents.core.AgentService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** GET /api/agents/directory/mine: no params = every entry (unchanged); limit/offset window it. */
class AgentDirectoryMinePagingTest {

    private final AgentDirectoryService service = mock(AgentDirectoryService.class);
    private final AgentDirectoryController controller =
            new AgentDirectoryController(service, mock(AgentService.class), mock(A2aAgentCardService.class));

    @Test
    void emptyTenantReturnsAnEmptyListWithZeroTotal() {
        when(service.listForTenant()).thenReturn(List.of());
        ResponseEntity<List<Map<String, Object>>> r = controller.mine(null, null);
        assertEquals(0, r.getBody().size());
        assertEquals("0", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("false", r.getHeaders().getFirst("X-Has-More"));
    }
}
