package com.agenticai.core.common.privacy;

import com.agenticai.core.common.privacy.ConsentLedger.Entry;
import com.agenticai.core.common.privacy.ConsentPolicyService.Policy;
import com.agenticai.core.common.privacy.ContactPrivacyService.EraseOptions;
import com.agenticai.core.common.privacy.ContactPrivacyService.ErasureReport;
import com.agenticai.core.common.privacy.ContactPrivacyService.ExportReport;
import com.agenticai.core.common.privacy.RetentionPolicyService.Rule;
import com.agenticai.core.common.privacy.RetentionPolicyService.Saved;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Tenant-admin API for the DPDP-oriented controls. Gated to {@code PERM_MANAGE_DATA_GOVERNANCE} in
 * {@code TenantFeatureRules}, like the other data-governance endpoints. Nothing here reads another
 * tenant: every service below works in the schema of the request's tenant.
 */
@RestController
@RequestMapping("/api/tenant/privacy")
@Profile("security")
public class PrivacyController {

    private final ConsentPolicyService consentPolicies;
    private final ConsentLedger ledger;
    private final RetentionPolicyService retention;
    private final PurposeService purposes;
    private final ContactPrivacyService contacts;
    private final AuditExportService auditExport;

    public PrivacyController(
            ConsentPolicyService consentPolicies,
            ConsentLedger ledger,
            RetentionPolicyService retention,
            PurposeService purposes,
            ContactPrivacyService contacts,
            AuditExportService auditExport) {
        this.consentPolicies = consentPolicies;
        this.ledger = ledger;
        this.retention = retention;
        this.purposes = purposes;
        this.contacts = contacts;
        this.auditExport = auditExport;
    }

    // ---- consent -------------------------------------------------------------------------

    @GetMapping("/consent-policies/{agentId}")
    public Policy getConsentPolicy(@PathVariable UUID agentId) {
        return consentPolicies
                .find(agentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No consent policy"));
    }

    @PutMapping("/consent-policies/{agentId}")
    public Policy putConsentPolicy(
            @PathVariable UUID agentId, @RequestBody ConsentPolicyService.Request request) {
        return consentPolicies.put(agentId, request, actor());
    }

    @GetMapping("/consent-records")
    public List<Entry> consentRecords(@RequestParam String channel, @RequestParam String externalId) {
        return ledger.history(channel.trim().toUpperCase(), externalId);
    }

    // ---- retention -----------------------------------------------------------------------

    public record RetentionRequest(
            String channel, @NotNull PrivacyDataClass dataClass, boolean enabled, Integer retentionDays) {}

    @GetMapping("/retention-policies")
    public List<Rule> retentionPolicies() {
        return retention.list();
    }

    @PutMapping("/retention-policies")
    public Saved putRetention(@Valid @RequestBody RetentionRequest request) {
        return retention.put(
                request.channel(), request.dataClass(), request.enabled(), request.retentionDays(), actor());
    }

    // ---- purposes ------------------------------------------------------------------------

    public record PurposesRequest(List<String> purposes) {}

    @GetMapping("/purposes/knowledge-collections/{id}")
    public List<String> collectionPurposes(@PathVariable UUID id) {
        return purposes.purposes(PurposeService.KNOWLEDGE_COLLECTION, id);
    }

    @PutMapping("/purposes/knowledge-collections/{id}")
    public List<String> putCollectionPurposes(@PathVariable UUID id, @RequestBody PurposesRequest request) {
        return purposes.replace(PurposeService.KNOWLEDGE_COLLECTION, id, request.purposes(), actor());
    }

    @GetMapping("/purposes/agents/{id}")
    public List<String> agentPurposes(@PathVariable UUID id) {
        return purposes.purposes(PurposeService.AGENT, id);
    }

    @PutMapping("/purposes/agents/{id}")
    public List<String> putAgentPurposes(@PathVariable UUID id, @RequestBody PurposesRequest request) {
        return purposes.replace(PurposeService.AGENT, id, request.purposes(), actor());
    }

    // ---- per-contact export and erasure --------------------------------------------------

    public record ContactRequest(
            @NotBlank String channel,
            @NotBlank String externalId,
            boolean includeLeads,
            boolean keepConsentProof) {}

    @PostMapping("/contacts/export")
    public ExportReport exportContact(@Valid @RequestBody ContactRequest request) {
        return contacts.export(request.channel(), request.externalId(), request.includeLeads(), actor());
    }

    @PostMapping("/contacts/erase")
    public ErasureReport eraseContact(@Valid @RequestBody ContactRequest request) {
        return contacts.erase(
                request.channel(),
                request.externalId(),
                new EraseOptions(request.includeLeads(), request.keepConsentProof()),
                actor());
    }

    // ---- breach support ------------------------------------------------------------------

    /** Audit events in [from, to) for the caller's tenant. {@code format} is csv (default) or json. */
    @GetMapping("/audit-export")
    public ResponseEntity<StreamingResponseBody> auditExport(
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "csv") String format) {
        AuditExportService.validate(from, to);
        String actor = actor();
        boolean json = "json".equalsIgnoreCase(format);
        if (!json && !"csv".equalsIgnoreCase(format)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "format must be csv or json");
        }
        // The body is written on another thread; TenantContext is a ThreadLocal, so capture it here
        // and re-apply it there. Without a tenant the query would run against the wrong schema.
        TenantScope scope = TenantScope.capture();
        if (scope.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tenant context");
        }
        StreamingResponseBody body =
                out ->
                        scope.run(
                                () -> {
                                    try {
                                        if (json) {
                                            auditExport.exportJson(from, to, out, actor);
                                        } else {
                                            auditExport.exportCsv(from, to, out, actor);
                                        }
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                });
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"audit-events." + (json ? "json" : "csv") + "\"")
                .contentType(json ? MediaType.APPLICATION_JSON : MediaType.parseMediaType("text/csv"))
                .body(body);
    }

    private static String actor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("unknown");
    }
}
