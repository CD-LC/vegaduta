package com.agenticai.core.brief;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.brief.BriefValidationResult.Issue;
import com.agenticai.core.brief.dto.BriefDtos.BriefRequest;
import com.agenticai.core.brief.dto.BriefDtos.CreateResponse;
import com.agenticai.core.brief.dto.BriefDtos.DeleteRequest;
import com.agenticai.core.brief.dto.BriefDtos.Detail;
import com.agenticai.core.brief.dto.BriefDtos.ListItem;
import com.agenticai.core.brief.dto.BriefDtos.PlanResponse;
import com.agenticai.core.brief.dto.BriefDtos.ReadinessResponse;
import com.agenticai.core.brief.dto.BriefDtos.UpdateResponse;
import com.agenticai.core.brief.dto.PlanRequest;
import com.agenticai.core.brief.dto.PlanResult;
import com.agenticai.core.brief.dto.ReadinessRow;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code /api/briefs} (Earned Autonomy contract, section 2): plan a Brief from a prompt, save it,
 * read it with live readiness, edit, recompute readiness, delete. Authorisation is declared as
 * explicit matchers in {@code SecurityConfig} (reads: any authenticated tenant member; writes:
 * PERM_MANAGE_AGENTS) - this class re-checks nothing itself.
 *
 * <p>{@code POST /plan} has two representations selected by {@code Accept}: JSON (one response) and
 * {@code text/event-stream} (events {@code thinking}, {@code section}, {@code done}, or a terminal
 * {@code error}). Errors raised here are answered by the local handlers below rather than
 * {@code GlobalExceptionHandler}, whose catch-all would otherwise turn them into 500s.
 */
@RestController
@RequestMapping("/api/briefs")
public class BriefController {

    private static final Logger log = LoggerFactory.getLogger(BriefController.class);
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final BriefPlannerService plannerService;
    private final BriefReadinessService readinessService;
    private final BriefService briefService;
    private final BriefRateLimiter rateLimiter;
    private final TenantLimits tenantLimits;

    public BriefController(
        BriefPlannerService plannerService,
        BriefReadinessService readinessService,
        BriefService briefService,
        BriefRateLimiter rateLimiter,
        TenantLimits tenantLimits
    ) {
        this.plannerService = plannerService;
        this.readinessService = readinessService;
        this.briefService = briefService;
        this.rateLimiter = rateLimiter;
        this.tenantLimits = tenantLimits;
    }

    // ------------------------------------------------------------------ plan

    @PostMapping(value = "/plan", produces = MediaType.APPLICATION_JSON_VALUE)
    public PlanResponse plan(@Valid @RequestBody PlanRequest request) {
        countAgainstLimit();
        return toResponse(plannerService.plan(request));
    }

    @PostMapping(value = "/plan", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter planStream(@Valid @RequestBody PlanRequest request) {
        countAgainstLimit(); // before the stream opens, so a 429 is a normal JSON error
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Optional<TenantContext> tenant = TenantContext.current();
        // Tenant context is thread-local; the worker must carry the caller's or every tenant-schema read misroutes.
        TenantAwareExecutors.startVirtualThread("brief-plan", () -> {
            tenant.ifPresent(TenantContext::set);
            try {
                PlanResult result = plannerService.plan(request, new BriefPlannerService.PlanListener() {
                    @Override
                    public void thinking(String step) {
                        send(emitter, "thinking", Map.of("step", step));
                    }

                    @Override
                    public void section(String name, Object payload) {
                        send(emitter, "section", Map.of("name", name, "data", payload));
                    }
                });
                send(emitter, "done", toResponse(result));
                emitter.complete();
            } catch (BriefValidationException e) {
                send(emitter, "error", errorBody(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e.getErrors()));
                emitter.complete();
            } catch (RuntimeException e) {
                log.error("Brief planning failed", e);
                send(emitter, "error", errorBody(HttpStatus.INTERNAL_SERVER_ERROR, "Planning failed. Please try again.", List.of()));
                emitter.complete();
            } finally {
                TenantContext.clear();
            }
        });
        return emitter;
    }

    private void countAgainstLimit() {
        Optional<TenantContext> ctx = TenantContext.current();
        String subject = ctx.map(TenantContext::getSubject).orElse(null);
        String tenantId = ctx.map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId == null ? TenantPlan.FREE : tenantLimits.plan(tenantId);
        rateLimiter.assertPlanCallAllowed(subject, plan);
    }

    private PlanResponse toResponse(PlanResult result) {
        List<ReadinessRow> readiness = readinessService.compute(result.brief());
        return new PlanResponse(result.brief(), readiness, result.warnings());
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("Brief plan stream closed by the client before event {}", event);
        }
    }

    // ------------------------------------------------------------------ CRUD

    @PostMapping
    public ResponseEntity<CreateResponse> create(@RequestBody BriefRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(briefService.create(request.brief(), request.note()));
    }

    /** {@code ?entityId=} scopes the list to the briefs bound to that entity - the coach's envoy lookup. */
    @GetMapping
    public ResponseEntity<List<ListItem>> list(@RequestParam(name = "entityId", required = false) String entityId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            entityId == null || entityId.isBlank() ? briefService.list() : briefService.listBoundTo(entityId),
            limit, offset, 50, 500);
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable UUID id) {
        return briefService.get(id);
    }

    @PutMapping("/{id}")
    public UpdateResponse update(@PathVariable UUID id, @RequestBody BriefRequest request) {
        return briefService.update(id, request.brief(), request.note());
    }

    @PostMapping("/{id}/readiness")
    public ReadinessResponse readiness(@PathVariable UUID id) {
        List<ReadinessRow> rows = briefService.readiness(id);
        return new ReadinessResponse(rows, readinessService.summarize(rows));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
        @PathVariable UUID id,
        @RequestParam(name = "purgeEntities", defaultValue = "false") boolean purgeParam,
        @RequestBody(required = false) DeleteRequest body
    ) {
        boolean purge = purgeParam || (body != null && Boolean.TRUE.equals(body.purgeEntities()));
        briefService.delete(id, purge);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ errors

    @ExceptionHandler(BriefValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(BriefValidationException e) {
        return json(HttpStatus.UNPROCESSABLE_ENTITY, errorBody(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), e.getErrors()));
    }

    @ExceptionHandler(BriefRateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(BriefRateLimitException e) {
        return json(HttpStatus.TOO_MANY_REQUESTS, errorBody(HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), List.of()));
    }

    @ExceptionHandler(BriefNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(BriefNotFoundException e) {
        return json(HttpStatus.NOT_FOUND, errorBody(HttpStatus.NOT_FOUND, e.getMessage(), List.of()));
    }

    @ExceptionHandler(BriefConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(BriefConflictException e) {
        return json(HttpStatus.CONFLICT, errorBody(HttpStatus.CONFLICT, e.getMessage(), List.of()));
    }

    /** JSON content type set explicitly so an SSE caller's Accept header cannot turn an error into a 406. */
    static ResponseEntity<Map<String, Object>> json(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    static Map<String, Object> errorBody(HttpStatus status, String message, List<Issue> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", message);
        if (!errors.isEmpty()) {
            body.put("section", errors.get(0).section());
            body.put("errors", errors);
        }
        return body;
    }
}
