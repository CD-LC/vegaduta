package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agenticai.core.agents.job.trigger.AgentEventTriggerController;
import com.agenticai.core.agents.job.trigger.AgentEventTriggerService;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskController;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskResponse;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskService;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Wave 2 list paging for the job-module lists (event triggers, scheduled tasks). Moved out of
 * {@code Wave2ListPagingTest} and {@code AgentsWave2BPagingTest} in core/agents when jobs became
 * their own module (ADR-0023); the assertions are unchanged.
 */
class JobsListPagingTest {

    private static final String ID = UUID.randomUUID().toString();

    private static void assertPaged(MockMvc mvc, String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(header().string("X-Has-More", "false"));
        mvc.perform(get(path).param("limit", "5").param("offset", "2"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
        mvc.perform(get(path).param("limit", "5").param("offset", "999999999999"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"))
                .andExpect(header().string("X-Has-More", "false"));
    }

    private static void assertPage(
            ResponseEntity<? extends List<?>> r, int size, String total, String more) {
        assertEquals(size, r.getBody().size());
        assertEquals(total, r.getHeaders().getFirst("X-Total-Count"));
        assertEquals(more, r.getHeaders().getFirst("X-Has-More"));
    }

    @Test
    void triggers() throws Exception {
        var c = new AgentEventTriggerController(mock(AgentEventTriggerService.class));
        assertPaged(MockMvcBuilders.standaloneSetup(c).build(), "/api/agents/" + ID + "/triggers");
    }

    @Test
    void scheduledTasksPage() {
        var service = mock(ScheduledTaskService.class);
        when(service.list())
                .thenReturn(IntStream.range(0, 5).mapToObj(i -> mock(TaskResponse.class)).toList());
        var controller = new ScheduledTaskController(service);
        assertPage(controller.list(null, null), 5, "5", "false");
        assertPage(controller.list(2, 1L), 2, "5", "true");
        assertPage(controller.list(2, 4L), 1, "5", "false");
    }
}
