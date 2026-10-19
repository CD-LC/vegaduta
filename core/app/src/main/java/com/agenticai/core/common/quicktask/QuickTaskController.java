package com.agenticai.core.common.quicktask;

import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.turn.ChatTurnCancelService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Simple mode's "type what you want, pick a channel, hit Run" endpoint - see {@link
 * QuickTaskService}'s javadoc for what actually happens underneath. Blocking (not SSE, unlike
 * {@code AgentController.chat}) is intentional for {@link #run}: the caller needs the complete
 * answer to also show on-screen and to dispatch as one WhatsApp/SMS message or one spoken call, not
 * a token stream to render incrementally.
 *
 * <p>{@link #runStream} (#474 follow-up: "I want to see the live trace log for that task") does the
 * identical work - see {@link QuickTaskService#execute} - but as a live SSE event stream instead,
 * for callers (the console's own Quick Task UI, going forward) that want to show real tool-call
 * activity while the task is still running rather than only once it finishes. {@link #run} is kept,
 * not replaced, for any caller that only wants the final answer and doesn't care about a live
 * trace.
 */
@RestController
@RequestMapping("/api/quick-task")
public class QuickTaskController {

    private final QuickTaskService quickTaskService;
    private final QuickTaskRunService quickTaskRunService;
    private final ChatTurnCancelService chatTurnCancelService;

    public QuickTaskController(
            QuickTaskService quickTaskService,
            QuickTaskRunService quickTaskRunService,
            ChatTurnCancelService chatTurnCancelService) {
        this.quickTaskService = quickTaskService;
        this.quickTaskRunService = quickTaskRunService;
        this.chatTurnCancelService = chatTurnCancelService;
    }

    @PostMapping
    public QuickTaskResponse run(
            Principal principal, @Valid @RequestBody QuickTaskRequest request) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new QuickTaskNotReadyException("No authenticated session for this request.");
        }
        return quickTaskService.run(principal.getName(), request);
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> runStream(
            Principal principal, @Valid @RequestBody QuickTaskRequest request) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new QuickTaskNotReadyException("No authenticated session for this request.");
        }
        return quickTaskService.runStream(principal.getName(), request);
    }

    /**
     * "Detect the underlying workflow if specified, select defined tools/MCP tools, and always ask
     * from where it should trigger the action" - this is the "detect" half, called before the
     * person picks a channel and hits Run (see SimpleTaskRunner.js).
     */
    @PostMapping("/preview")
    public QuickTaskPreviewResponse preview(
            Principal principal, @Valid @RequestBody QuickTaskPreviewRequest request) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new QuickTaskNotReadyException("No authenticated session for this request.");
        }
        return quickTaskService.preview(principal.getName(), request);
    }

    /**
     * Direct feedback: "it should generate an ID of a task and we should be able to see the
     * complete log with request sent and response back - not just half of it - any point of time."
     * The task id is the same {@code sessionId} already returned in {@link QuickTaskResponse} and
     * every {@code started}/{@code done} SSE event - this is what makes that id actually useful
     * after the fact instead of write-only.
     */
    @GetMapping("/{taskId}")
    public QuickTaskRunDetailResponse getTask(Principal principal, @PathVariable String taskId) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new QuickTaskNotReadyException("No authenticated session for this request.");
        }
        return quickTaskService.getTaskDetail(principal.getName(), taskId);
    }

    /**
     * Run control (V261 sibling): cancel a task that is still running. A Quick Task IS a chat turn
     * under the hood ({@code QuickTaskService#run} goes through {@code AgentChatService#chat}), and
     * its task id IS the chat session id - so cancelling is just setting the same Redis flag the
     * chat-turn watcher polls. Owner-scoped via {@code findForOwner} - one user cannot stop
     * another's task by guessing its id. 202, same request-not-wait contract as {@code
     * AgentChatSessionController#cancelChatTurn}: the turn ends on its own next poll (~500ms); if
     * it already finished, the flag simply expires.
     */
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelTask(
            Principal principal, @PathVariable String taskId) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new QuickTaskNotReadyException("No authenticated session for this request.");
        }
        quickTaskRunService
                .findForOwner(taskId, principal.getName())
                .ifPresent(
                        run ->
                                chatTurnCancelService.requestCancellation(
                                        run.getAgentId(),
                                        // The turn watches its caller-scoped session id
                                        // (AgentChatService).
                                        SessionMemoryService.scopedSessionId(
                                                TenantContext.current()
                                                        .map(TenantContext::getTenantId)
                                                        .orElse(null),
                                                run.getAgentId(),
                                                principal.getName(),
                                                run.getTaskId())));
        return ResponseEntity.accepted().body(Map.of("taskId", taskId, "cancelRequested", true));
    }

    /** "any point of time" - the list of past task ids to pick from, newest first. */
    @GetMapping("/recent")
    public ResponseEntity<List<QuickTaskRunSummaryResponse>> recentTasks(
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long offset,
            Principal principal) {
        if (principal == null || !StringUtils.hasText(principal.getName())) {
            throw new QuickTaskNotReadyException("No authenticated session for this request.");
        }
        // Default 50 (the pre-paging cap) even without ?limit=; max 200. X-Total-Count is the real
        // run count so the UI can say "50 of 200" instead of implying 50 is everything.
        int size = ListPaging.limit(limit == null ? RECENT_DEFAULT_LIMIT : limit, RECENT_DEFAULT_LIMIT, RECENT_MAX_LIMIT);
        long skip = ListPaging.offset(offset);
        var page = quickTaskService.recentTasks(principal.getName(), skip, size);
        return ListPaging.respond(page.getContent(), size, skip, page::getTotalElements);
    }

    private static final int RECENT_DEFAULT_LIMIT = 50;
    private static final int RECENT_MAX_LIMIT = 200;
}
