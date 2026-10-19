package com.agenticai.core.audit.seal;

import com.agenticai.core.audit.seal.AuditSealDtos.ExportBundle;
import com.agenticai.core.audit.seal.AuditSealDtos.SealPage;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tamper-evidence endpoints for the audit log: list seals, verify them against the live rows, export a bundle
 * for offline verification. All read-only, tenant-scoped (the caller's tenant schema) and under
 * {@code /api/audit-log/**}, which SecurityConfig already gates with {@code PERM_VIEW_AUDIT_LOG} - the same
 * admin gate as the log itself, so no new security rule is needed. No class-level mapping on purpose: the
 * paths are distinct sub-paths of the existing audit-log resource. Thin: all logic is in
 * {@link AuditSealService}; a malformed window is an IllegalArgumentException (400).
 */
@RestController
public class AuditSealController {

    private final AuditSealService sealService;

    public AuditSealController(AuditSealService sealService) {
        this.sealService = sealService;
    }

    @GetMapping("/api/audit-log/seals")
    public SealPage seals(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return sealService.list(page, size);
    }

    @GetMapping("/api/audit-log/verify")
    public AuditSealVerifier.Result verify(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        return sealService.verify(from, to);
    }

    @GetMapping("/api/audit-log/export")
    public ResponseEntity<ExportBundle> export(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        ExportBundle bundle = sealService.export(from, to, Instant.now());
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename("audit-seal-export.json").build().toString())
            .body(bundle);
    }
}
