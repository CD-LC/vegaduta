package com.agenticai.core.agents.coding;

import com.agenticai.core.agents.coding.CodingTaskDtos.TaskDocument;
import com.agenticai.core.agents.coding.CodingTaskDtos.TaskView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The paged "my coding tasks" read, kept out of {@link CodingTaskService} (a file-size-ratchet
 * file). A request that sends no window gets the newest {@link #DEFAULT_LIMIT}, exactly as before
 * paging existed; the user's index now keeps more than that so {@code offset} can reach older tasks.
 */
final class CodingTaskListing {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private static final Logger log = LoggerFactory.getLogger(CodingTaskListing.class);

    private CodingTaskListing() {}

    /** One window of tasks and the size of the whole recent list ({@code X-Total-Count}). */
    record Page(List<TaskView> tasks, long total) {}

    static Page page(
            CodingTaskStore store,
            String tenantId,
            String requester,
            int offset,
            int limit,
            Function<TaskDocument, TaskView> settle) {
        List<String> ids;
        long total;
        try {
            ids = store.recent(tenantId, requester, offset, limit);
            total = store.recentCount(tenantId, requester);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, CodingTaskService.MSG_READ_FAILED, e);
        }
        List<TaskView> out = new ArrayList<>();
        for (String id : ids) {
            try {
                Optional<TaskDocument> found = store.find(tenantId, id);
                if (found.isPresent() && requester.equals(found.get().requestedBy())) {
                    out.add(settle.apply(found.get()));
                }
            } catch (RuntimeException e) {
                log.debug("Skipping unreadable coding task {}", id, e);
            }
        }
        return new Page(out, total);
    }
}
