package com.agenticai.core.workflow;

import com.agenticai.core.workflow.comment.WorkflowCommentController;
import com.agenticai.core.workflow.comment.WorkflowCommentService;
import com.agenticai.core.workflow.comment.WorkflowCommentService.CommentView;
import com.agenticai.core.workflow.dto.WorkflowPinResponse;
import com.agenticai.core.workflow.humaninput.WorkflowInputRequestController;
import com.agenticai.core.workflow.humaninput.WorkflowInputRequestService;
import com.agenticai.core.workflow.version.WorkflowVersionController;
import com.agenticai.core.workflow.version.WorkflowVersionService;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Wave 2 (B) paging: no params keep the complete list; limit/offset window it and send X-Total-Count / X-Has-More. */
class WorkflowSubListPagingTest {

    private static <T> List<T> five(Class<T> type) {
        return IntStream.range(0, 5).mapToObj(i -> mock(type)).toList();
    }

    private static void assertPage(ResponseEntity<? extends List<?>> r, int size, String total, String more) {
        assertEquals(size, r.getBody().size());
        assertEquals(total, r.getHeaders().getFirst("X-Total-Count"));
        assertEquals(more, r.getHeaders().getFirst("X-Has-More"));
    }

    /** Param binding + headers over HTTP for an endpoint whose backing list is empty. */
    private static void assertHttpEmptyPage(org.springframework.test.web.servlet.MockMvc mvc, String url) throws Exception {
        mvc.perform(get(url).param("limit", "5").param("offset", "3"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Total-Count", "0"))
                .andExpect(header().string("X-Has-More", "false"));
    }

    private final UUID wf = UUID.randomUUID();

    @Test
    void commentsPage() {
        var service = mock(WorkflowCommentService.class);
        when(service.list(wf)).thenReturn(five(CommentView.class));
        var controller = new WorkflowCommentController(service);
        assertPage(controller.list(wf, null, null), 5, "5", "false");
        assertPage(controller.list(wf, 2, 1L), 2, "5", "true");
        assertPage(controller.list(wf, 2, 4L), 1, "5", "false");
    }

    @Test
    void pinsPage() {
        var service = mock(WorkflowPinService.class);
        when(service.list(wf)).thenReturn(five(WorkflowPinResponse.class));
        var controller = new WorkflowPinController(service);
        assertPage(controller.list(wf, null, null), 5, "5", "false");
        assertPage(controller.list(wf, 2, 1L), 2, "5", "true");
        assertPage(controller.list(wf, 2, 4L), 1, "5", "false");
    }

    @Test
    void versionsAreWindowedAndHeadered() throws Exception {
        var versions = mock(WorkflowVersionService.class);
        when(versions.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        var controller = new WorkflowVersionController(mock(WorkflowService.class), versions, mock(WorkflowRunRepository.class));
        assertHttpEmptyPage(MockMvcBuilders.standaloneSetup(controller).build(), "/api/workflows/" + wf + "/versions");
    }

    @Test
    void inputRequestsAreWindowedAndHeadered() throws Exception {
        var service = mock(WorkflowInputRequestService.class);
        when(service.list(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        var controller = new WorkflowInputRequestController(service, mock(WorkflowRunnerAccess.class));
        assertHttpEmptyPage(MockMvcBuilders.standaloneSetup(controller).build(), "/api/workflows/input-requests");
    }

}
