package com.agenticai.core.brief;

import com.agenticai.core.tenancy.ListPaging;
import static com.agenticai.core.brief.BriefController.errorBody;
import static com.agenticai.core.brief.BriefController.json;

import com.agenticai.core.brief.dto.ApplyDtos.ApplyRequest;
import com.agenticai.core.brief.dto.ApplyDtos.ApplyResult;
import com.agenticai.core.brief.dto.ApplyDtos.JobStarted;
import com.agenticai.core.brief.dto.ApplyDtos.RollbackRequest;
import com.agenticai.core.brief.dto.ApplyDtos.TuneRequest;
import com.agenticai.core.brief.dto.ApplyDtos.TuneResult;
import com.agenticai.core.brief.dto.ApplyDtos.VersionDiff;
import com.agenticai.core.brief.dto.BriefDtos.VersionSummary;
import com.agenticai.core.brief.dto.TrustDtos.TrustChange;
import com.agenticai.core.brief.dto.TrustDtos.TrustProposal;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The lifecycle half of {@code /api/briefs} (contract section 2): apply and its job log, versions, diff,
 * rollback, tune, and the trust ladder. Authorisation is declared as explicit matchers in {@code SecurityConfig}
 * (apply and rollback: PERM_MANAGE_AGENTS and PERM_MANAGE_WORKFLOWS_ADMIN; trust: ROLE_tenant-admin; tune:
 * PERM_MANAGE_AGENTS; reads: any authenticated tenant member) - this class re-checks nothing itself. Errors
 * are answered by the local handlers below in the same shape as {@link BriefController}.
 */
@RestController
@RequestMapping("/api/briefs")
public class BriefLifecycleController {

    private final BriefApplyService applyService;
    private final BriefVersionService versionService;
    private final BriefTuneService tuneService;
    private final TrustLadderService trustService;
    private final BriefRateLimiter rateLimiter;
    private final TenantLimits tenantLimits;

    public BriefLifecycleController(
        BriefApplyService applyService,
        BriefVersionService versionService,
        BriefTuneService tuneService,
        TrustLadderService trustService,
        BriefRateLimiter rateLimiter,
        TenantLimits tenantLimits
    ) {
        this.applyService = applyService;
        this.versionService = versionService;
        this.tuneService = tuneService;
        this.trustService = trustService;
        this.rateLimiter = rateLimiter;
        this.tenantLimits = tenantLimits;
    }

    // ------------------------------------------------------------------ apply

    /** Background: 202 with {@code {jobId, status}}; otherwise 200 with the finished result (status SUCCEEDED or FAILED). */
    @PostMapping("/{id}/apply")
    public ResponseEntity<?> apply(@PathVariable UUID id, @RequestBody(required = false) ApplyRequest request) {
        ApplyResult result = applyService.apply(id, request);
        if (request != null && request.isBackground()) {
            return ResponseEntity.accepted().body(new JobStarted(result.jobId(), result.status()));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/apply/{jobId}")
    public ApplyResult job(@PathVariable UUID id, @PathVariable UUID jobId) {
        return applyService.job(id, jobId);
    }

    // ------------------------------------------------------------------ versions

    @GetMapping("/{id}/versions")
    public ResponseEntity<List<VersionSummary>> versions(@PathVariable UUID id, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            versionService.list(id),
            limit, offset, 50, 500);
    }

    @GetMapping("/{id}/versions/diff")
    public VersionDiff diff(@PathVariable UUID id, @RequestParam int from, @RequestParam int to) {
        return versionService.diff(id, from, to);
    }

    @PostMapping("/{id}/versions/{version}/rollback")
    public ResponseEntity<?> rollback(
        @PathVariable UUID id, @PathVariable int version, @RequestBody(required = false) RollbackRequest request
    ) {
        boolean background = request != null && Boolean.TRUE.equals(request.background());
        ApplyResult result = versionService.rollback(id, version, background);
        if (background) {
            return ResponseEntity.accepted().body(new JobStarted(result.jobId(), result.status()));
        }
        return ResponseEntity.ok(result);
    }

    // ------------------------------------------------------------------ tune

    /** Proposes a change (never saves or applies it). Counts against the same daily plan limit as planning. */
    @PostMapping("/{id}/tune")
    public TuneResult tune(@PathVariable UUID id, @RequestBody TuneRequest request) {
        Optional<TenantContext> ctx = TenantContext.current();
        String subject = ctx.map(TenantContext::getSubject).orElse(null);
        String tenantId = ctx.map(TenantContext::getTenantId).orElse(null);
        TenantPlan plan = tenantId == null ? TenantPlan.FREE : tenantLimits.plan(tenantId);
        rateLimiter.assertPlanCallAllowed(subject, plan);
        return tuneService.tune(id, request == null ? null : request.instruction());
    }

    // ------------------------------------------------------------------ trust

    @PostMapping("/{id}/trust/proposal")
    public TrustProposal proposal(@PathVariable UUID id) {
        return trustService.proposal(id);
    }

    @PostMapping("/{id}/trust/{rung}")
    public TrustChange setRung(@PathVariable UUID id, @PathVariable String rung) {
        TrustRung target = TrustRung.parse(rung);
        if (target == null) {
            throw new BriefValidationException("rung", "The rung must be UNDER_WATCH, TRUSTED or AUTONOMOUS.");
        }
        return trustService.setRung(id, target);
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
}
