package com.agenticai.core.common.quicktask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.ChatTurnCancelService;
import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** limit/offset + X-Total-Count on GET /api/quick-task/recent; default stays the old 50. */
class QuickTaskControllerRecentPagingTest {

    private final QuickTaskService service = mock(QuickTaskService.class);
    private final QuickTaskController controller =
            new QuickTaskController(service, mock(QuickTaskRunService.class), mock(ChatTurnCancelService.class));
    private final Principal user = () -> "u1";

    private void stub(long total) {
        when(service.recentTasks(eq("u1"), anyLong(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), total));
    }

    @Test
    void defaultsToTheOldFiftyRowCapWithNoOffset() {
        stub(0);
        controller.recentTasks(null, null, user);
        verify(service).recentTasks("u1", 0L, 50);
    }

    @Test
    void clampsLimitAndPassesExactOffset() {
        stub(0);
        controller.recentTasks(9999, 7L, user);
        verify(service).recentTasks("u1", 7L, 200);
    }

    @Test
    void reportsRealTotalSoTheUiCanSayFiftyOfTwoHundred() {
        stub(200);
        var r = controller.recentTasks(50, 50L, user);
        assertEquals("200", r.getHeaders().getFirst("X-Total-Count"));
        assertEquals("true", r.getHeaders().getFirst("X-Has-More"));
    }
}
