package com.agenticai.core.agents.scheduledtask;

import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.Capabilities;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.PreviewRequest;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.PreviewResponse;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.RunResponse;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskRequest;
import com.agenticai.core.agents.scheduledtask.ScheduledTaskDtos.TaskResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Scheduled tasks: list/create/edit/delete, pause/resume, run-now and run history.
 *
 * <p>Thin on purpose: access, the plan gate, validation and every status code are decided in {@link
 * ScheduledTaskService}, which throws only types {@code GlobalExceptionHandler} already maps. No {@code
 * SecurityConfig} entry - the path falls through to {@code anyRequest().authenticated()}. With {@code
 * app.scheduled-tasks.enabled=false} (the default) everything but {@code /capabilities} answers 404.
 */
@RestController
@RequestMapping("/api/scheduled-tasks")
public class ScheduledTaskController {

    private final ScheduledTaskService service;

    public ScheduledTaskController(ScheduledTaskService service) {
        this.service = service;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return service.capabilities();
    }

    @GetMapping
    public ResponseEntity<List<TaskResponse>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(service.list(), limit, offset, 50, 200);
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody TaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PostMapping("/preview")
    public PreviewResponse preview(@Valid @RequestBody PreviewRequest request) {
        return service.preview(request);
    }

    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public TaskResponse update(@PathVariable UUID id, @Valid @RequestBody TaskRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pause")
    public TaskResponse pause(@PathVariable UUID id) {
        return service.pause(id);
    }

    @PostMapping("/{id}/resume")
    public TaskResponse resume(@PathVariable UUID id) {
        return service.resume(id);
    }

    @PostMapping("/{id}/run-now")
    public RunResponse runNow(@PathVariable UUID id) {
        return service.runNow(id);
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<List<RunResponse>> runs(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(required = false) Long offset
    ) {
        ScheduledTaskService.RunsPage page = service.runsPage(id, limit, offset == null ? 0 : offset);
        return ResponseEntity.ok()
            .header("X-Total-Count", String.valueOf(page.total()))
            .header("X-Has-More", String.valueOf(page.hasMore()))
            .body(page.items());
    }
}
