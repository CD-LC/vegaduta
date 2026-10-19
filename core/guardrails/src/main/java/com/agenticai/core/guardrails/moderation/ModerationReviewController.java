package com.agenticai.core.guardrails.moderation;

import com.agenticai.core.guardrails.moderation.dto.ModerationReviewDecisionRequest;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.guardrails.moderation.dto.ModerationReviewItemResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase P6 - the trust-&-safety reviewer's read/act surface for the human
 * moderation review queue. A NEW controller by design (P6 requirement) -
 * deliberately NOT folded into {@code PlatformController}.
 *
 * <p><b>Authorization</b> is enforced here in-controller, mirroring {@code
 * ApprovalController#enforceApproverAuthorization}: every endpoint requires
 * the {@code ROLE_tenant-admin} authority (the tenant's trust-&-safety
 * reviewer). This avoids editing the shared {@code SecurityConfig} (owned by
 * another workstream); an operator who wants a dedicated {@code
 * ROLE_ts-reviewer} role can add a matcher there later without changing this
 * controller's contract. Reads are gated too - a moderation queue holds
 * exactly the sensitive content (PII, harassment, self-harm) that shouldn't
 * be visible to any authenticated member.
 *
 * <p><b>Not-found → 404, already-decided → 409</b> are handled by the
 * controller-local {@code @ExceptionHandler}s below, which take precedence
 * over the app-wide {@code @RestControllerAdvice} (whose catch-all would
 * otherwise turn them into 500s) - so this controller stays self-contained
 * and doesn't depend on editing the shared handler.
 */
@RestController
@RequestMapping("/api/moderation/review")
public class ModerationReviewController {

    // Granular RBAC (2026-09-23): the moderation review queue tracks
    // MANAGE_GUARDRAILS - trust-&-safety review IS guardrail administration.
    // platform-admin keeps the role check (no tenant grants by design).
    private static final String REVIEWER_AUTHORITY = "PERM_MANAGE_GUARDRAILS";
    private static final String PLATFORM_ADMIN_AUTHORITY = "ROLE_platform-admin";

    private final ModerationReviewService reviewService;

    public ModerationReviewController(ModerationReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public ResponseEntity<List<ModerationReviewItemResponse>> list(
        @RequestParam(required = false) ModerationReviewStatus status,
        @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset,
        Authentication authentication
    ) {
        requireReviewer(authentication);
        return ListPaging.window(reviewService.list(status).stream().map(ModerationReviewItemResponse::from).toList(), limit, offset, 50, 200);
    }

    @GetMapping("/{id}")
    public ModerationReviewItemResponse get(@PathVariable UUID id, Authentication authentication) {
        requireReviewer(authentication);
        return ModerationReviewItemResponse.from(reviewService.get(id));
    }

    @PostMapping("/{id}/approve")
    public ModerationReviewItemResponse approve(
        @PathVariable UUID id,
        @Valid @RequestBody(required = false) ModerationReviewDecisionRequest body,
        Authentication authentication
    ) {
        requireReviewer(authentication);
        return ModerationReviewItemResponse.from(reviewService.approve(id, reviewerId(authentication), notes(body)));
    }

    @PostMapping("/{id}/block")
    public ModerationReviewItemResponse block(
        @PathVariable UUID id,
        @Valid @RequestBody(required = false) ModerationReviewDecisionRequest body,
        Authentication authentication
    ) {
        requireReviewer(authentication);
        return ModerationReviewItemResponse.from(reviewService.block(id, reviewerId(authentication), notes(body)));
    }

    @PostMapping("/{id}/escalate")
    public ModerationReviewItemResponse escalate(
        @PathVariable UUID id,
        @Valid @RequestBody(required = false) ModerationReviewDecisionRequest body,
        Authentication authentication
    ) {
        requireReviewer(authentication);
        return ModerationReviewItemResponse.from(reviewService.escalate(id, reviewerId(authentication), notes(body)));
    }

    private void requireReviewer(Authentication authentication) {
        boolean authorized = authentication != null && authentication.getAuthorities().stream()
            .anyMatch(a -> REVIEWER_AUTHORITY.equals(a.getAuthority())
                || PLATFORM_ADMIN_AUTHORITY.equals(a.getAuthority()));
        if (!authorized) {
            throw new ModerationReviewAccessDeniedException();
        }
    }

    private String reviewerId(Authentication authentication) {
        return authentication != null ? authentication.getName() : null;
    }

    private String notes(ModerationReviewDecisionRequest body) {
        return body != null ? body.notes() : null;
    }

    // --- Controller-local exception mapping (precedes the app-wide advice) ---

    @ExceptionHandler(ModerationReviewNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ModerationReviewNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ModerationReviewAlreadyDecidedException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ModerationReviewAlreadyDecidedException e) {
        return error(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ModerationReviewAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleForbidden(ModerationReviewAccessDeniedException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
            "status", status.value(),
            "error", message
        ));
    }
}
