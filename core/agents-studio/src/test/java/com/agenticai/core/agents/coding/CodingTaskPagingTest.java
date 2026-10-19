package com.agenticai.core.agents.coding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.coding.CodingTaskDtos.TaskView;
import com.agenticai.core.agents.coding.CodingTaskTestSupport.InMemoryStore;
import java.security.Principal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** Paging of GET /api/coding-tasks: the store window, the total, and the response headers. */
class CodingTaskPagingTest {

    private final CodingTaskService service = mock(CodingTaskService.class);
    private final CodingTaskController controller =
            new CodingTaskController(service, null);
    private final Principal alice = () -> "alice";

    @Test
    void storeWindowIsNewestFirstAndCountsTheWholeIndex() {
        InMemoryStore store = new InMemoryStore();
        for (int i = 1; i <= 7; i++) {
            store.index("t", "alice", "task" + i);
        }
        assertThat(store.recent("t", "alice", 3)).containsExactly("task7", "task6", "task5");
        assertThat(store.recent("t", "alice", 0, 3)).containsExactly("task7", "task6", "task5");
        assertThat(store.recent("t", "alice", 3, 3)).containsExactly("task4", "task3", "task2");
        assertThat(store.recent("t", "alice", 6, 3)).containsExactly("task1");
        assertThat(store.recent("t", "alice", 50, 3)).isEmpty();
        assertThat(store.recentCount("t", "alice")).isEqualTo(7);
    }

    @Test
    void listingReportsTheTotalEvenWhenNoDocumentIsReadable() {
        InMemoryStore store = new InMemoryStore();
        for (int i = 1; i <= 5; i++) {
            store.index("t", "alice", "task" + i);
        }
        CodingTaskListing.Page page = CodingTaskListing.page(store, "t", "alice", 1, 2, d -> null);
        assertThat(page.total()).isEqualTo(5);
        assertThat(page.tasks()).isEmpty();
    }

    @Test
    void noParamsKeepsTheNewestTwentyWindow() {
        when(service.list("alice", 0, 20)).thenReturn(new CodingTaskListing.Page(List.of(), 3));
        ResponseEntity<List<TaskView>> r = controller.list(null, null, alice);
        assertThat(r.getHeaders().getFirst("X-Total-Count")).isEqualTo("3");
        assertThat(r.getHeaders().getFirst("X-Has-More")).isEqualTo("false");
    }

    @Test
    void explicitWindowIsClampedAndHasMoreIsTruthful() {
        when(service.list("alice", 40, 100)).thenReturn(new CodingTaskListing.Page(List.of(), 200));
        ResponseEntity<List<TaskView>> r = controller.list(5000, 40L, alice);
        assertThat(r.getHeaders().getFirst("X-Total-Count")).isEqualTo("200");
        assertThat(r.getHeaders().getFirst("X-Has-More")).isEqualTo("true");
        when(service.list("alice", 0, 1)).thenReturn(new CodingTaskListing.Page(List.of(), 1));
        ResponseEntity<List<TaskView>> one = controller.list(-4, -9L, alice);
        assertThat(one.getHeaders().getFirst("X-Has-More")).isEqualTo("false");
    }
}
