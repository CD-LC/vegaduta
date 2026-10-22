package com.agenticai.core.agents.coding;

import com.agenticai.core.agents.coding.CodingTaskDtos.ApproveRequest;
import com.agenticai.core.agents.coding.CodingTaskDtos.CancelResponse;
import com.agenticai.core.agents.coding.CodingTaskDtos.Capabilities;
import com.agenticai.core.agents.coding.CodingTaskDtos.DiffResponse;
import com.agenticai.core.agents.coding.CodingTaskDtos.TaskView;
import com.agenticai.core.agents.coding.CodingTaskService.StartInput;
import java.io.IOException;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Coding tasks: start one against a repository, poll its progress, read the diff, approve or reject
 * it at the human gate, cancel, roll back.
 *
 * <p>Thin on purpose: access (RUNNER on the agent), the plan gate, validation, the flag and every
 * status code are decided in {@link CodingTaskService}, which throws only types
 * {@code GlobalExceptionHandler} already maps. No {@code SecurityConfig} entry: the path falls
 * through to {@code anyRequest().authenticated()}, like the Agent Lab and scheduled-task routes.
 * With {@code app.coding-harness.enabled=false} (the default) everything but {@code /capabilities}
 * answers 404 - including the upload, which is refused before a byte of it is read.
 */
@RestController
@RequestMapping("/api/coding-tasks")
public class CodingTaskController {

    private final CodingTaskService service;
    private final CodingTaskSettings settings;

    public CodingTaskController(CodingTaskService service, CodingTaskSettings settings) {
        this.service = service;
        this.settings = settings;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return service.capabilities();
    }

    /**
     * Starts a task. 202: it is queued, the body is its first poll result. Multipart because the source
     * may be an uploaded archive; exactly one of {@code repoUrl} / {@code archive} must be given.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TaskView> start(
        @RequestParam("agentId") String agentId,
        @RequestParam("instruction") String instruction,
        @RequestParam("testCommand") String testCommand,
        @RequestParam(value = "repoUrl", required = false) String repoUrl,
        @RequestParam(value = "archive", required = false) MultipartFile archive,
        @RequestParam(value = "maxSteps", required = false) Integer maxSteps,
        @RequestParam(value = "maxWallSeconds", required = false) Integer maxWallSeconds,
        @RequestParam(value = "maxTokens", required = false) Integer maxTokens,
        @RequestParam(value = "maxFixIterations", required = false) Integer maxFixIterations,
        Principal principal
    ) {
        service.requireEnabled();
        UUID agent;
        try {
            agent = UUID.fromString(agentId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("agentId must be an agent id.");
        }
        byte[] bytes = null;
        if (archive != null && !archive.isEmpty()) {
            if (archive.getSize() > settings.maxArchiveBytes()) {
                throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "The archive is larger than the " + (settings.maxArchiveBytes() >> 20) + " MB limit.");
            }
            try {
                bytes = archive.getBytes();
            } catch (IOException e) {
                throw new IllegalArgumentException("The archive could not be read.");
            }
        }
        TaskView view = service.start(new StartInput(
            agent, instruction, testCommand, repoUrl, bytes, maxSteps, maxWallSeconds, maxTokens, maxFixIterations),
            subjectOf(principal));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    /**
     * {@code limit} 1..100 (default 20 = the newest 20, as before), {@code offset} skips the newest
     * tasks. {@code X-Total-Count} is the size of the user's recent list (the index keeps the newest 200).
     */
    @GetMapping
    public ResponseEntity<List<TaskView>> list(
        @RequestParam(value = "limit", required = false) Integer limit,
        @RequestParam(value = "offset", required = false) Long offset,
        Principal principal
    ) {
        int size = limit == null ? CodingTaskListing.DEFAULT_LIMIT : Math.min(Math.max(limit, 1), CodingTaskListing.MAX_LIMIT);
        int skip = (int) Math.min(offset == null ? 0 : Math.max(offset, 0), 1_000_000L);
        CodingTaskListing.Page page = service.list(subjectOf(principal), skip, size);
        long seen = (long) skip + page.tasks().size();
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(Math.max(page.total(), seen)))
            .header("X-Has-More", String.valueOf(page.total() > skip + size))
            .body(page.tasks());
    }

    @GetMapping("/{taskId}")
    public TaskView get(@PathVariable String taskId, Principal principal) {
        return service.get(taskId, subjectOf(principal));
    }

    @GetMapping("/{taskId}/diff")
    public DiffResponse diff(@PathVariable String taskId, Principal principal) {
        return service.diff(taskId, subjectOf(principal));
    }

    /** The decision is bound to the diff the user saw: {@code diffHash} must match. 409 otherwise. */
    @PostMapping("/{taskId}/approve")
    public TaskView approve(@PathVariable String taskId, @RequestBody ApproveRequest body, Principal principal) {
        return service.approve(taskId, subjectOf(principal), body == null ? null : body.diffHash());
    }

    @PostMapping("/{taskId}/reject")
    public TaskView reject(@PathVariable String taskId, Principal principal) {
        return service.reject(taskId, subjectOf(principal));
    }

    /** 202: records a signal; the task stops before its next step. */
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<CancelResponse> cancel(@PathVariable String taskId, Principal principal) {
        return ResponseEntity.accepted().body(service.cancel(taskId, subjectOf(principal)));
    }

    @PostMapping("/{taskId}/rollback")
    public TaskView rollback(@PathVariable String taskId, Principal principal) {
        return service.rollback(taskId, subjectOf(principal));
    }

    private static String subjectOf(Principal principal) {
        return principal == null ? null : principal.getName();
    }
}
