package com.agenticai.core.brief;

import static com.agenticai.core.brief.BriefController.errorBody;
import static com.agenticai.core.brief.BriefController.json;

import com.agenticai.core.brief.dto.LedgerDtos.ExperimentRequest;
import com.agenticai.core.brief.dto.LedgerDtos.ExperimentStarted;
import com.agenticai.core.brief.dto.LedgerDtos.Ledger;
import com.agenticai.core.brief.dto.LedgerDtos.RehearsalStarted;
import com.agenticai.core.brief.dto.LedgerDtos.RehearsalView;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The proof half of {@code /api/briefs} (contract section 2): the ledger, rehearsal and "run as experiment".
 * Authorisation is declared as explicit matchers in {@code SecurityConfig} (reads - the ledger and the rehearsal
 * list - any authenticated tenant member; {@code rehearse} and {@code experiments}: PERM_MANAGE_AGENTS); this class
 * re-checks nothing itself. Errors use the same shape as {@link BriefController}.
 */
@RestController
@RequestMapping("/api/briefs")
public class BriefProofController {

    private final ProofLedgerService ledgerService;
    private final BriefRehearsalService rehearsalService;
    private final BriefExperimentService experimentService;
    private final BriefRateLimiter rateLimiter;

    public BriefProofController(
        ProofLedgerService ledgerService, BriefRehearsalService rehearsalService, BriefExperimentService experimentService,
        BriefRateLimiter rateLimiter
    ) {
        this.rateLimiter = rateLimiter;
        this.ledgerService = ledgerService;
        this.rehearsalService = rehearsalService;
        this.experimentService = experimentService;
    }

    @GetMapping("/{id}/ledger")
    public Ledger ledger(@PathVariable UUID id) {
        return ledgerService.ledger(id);
    }

    /** Starts the rehearsal and answers at once (202); the sessions run in the background - poll {@code /rehearsals}. */
    @PostMapping("/{id}/rehearse")
    public ResponseEntity<RehearsalStarted> rehearse(@PathVariable UUID id) {
        rateLimiter.assertProofRunAllowed(tenantId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(rehearsalService.rehearse(id));
    }

    @GetMapping("/{id}/rehearsals")
    public List<RehearsalView> rehearsals(@PathVariable UUID id) {
        return rehearsalService.rehearsals(id);
    }

    @PostMapping("/{id}/experiments")
    public ResponseEntity<ExperimentStarted> experiment(@PathVariable UUID id, @RequestBody(required = false) ExperimentRequest request) {
        rateLimiter.assertProofRunAllowed(tenantId());
        return ResponseEntity.status(HttpStatus.CREATED).body(experimentService.start(id, request));
    }

    private static String tenantId() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse(null);
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
