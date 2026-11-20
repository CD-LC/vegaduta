package com.agenticai.core.payments;

import com.agenticai.core.payments.PaymentDtos.CreateMandateRequest;
import com.agenticai.core.payments.PaymentDtos.MandatePreviewResponse;
import com.agenticai.core.payments.PaymentDtos.MandateResponse;
import com.agenticai.core.payments.PaymentDtos.MandateSpendResponse;
import com.agenticai.core.payments.PaymentDtos.RevokeMandateRequest;
import com.agenticai.core.payments.PaymentDtos.TransactionResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.tenancy.OffsetLimitRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tenant-facing REST for Agentic UPI Payments (§E1): the mandate grant wizard's preview/sign calls,
 * mandate list/revoke, transaction list/detail (receipts included) and the spend-vs-cap meter.
 * Every row read here is tenant-schema-scoped by {@code TenantSchemaResolver} automatically - there
 * is no cross-tenant path in this controller. All mutating endpoints audit-log inside {@link
 * PaymentMandateService}.
 *
 * <p>{@code grantedByUserId} is NEVER caller-supplied: the grantor is the authenticated JWT subject
 * from {@link TenantContext} - a client cannot mint a mandate "granted by" someone else.
 */
@RestController
@RequestMapping("/api/payments")
@ConditionalOnEditionFeature(EditionFeature.BILLING_PAYMENTS)
public class PaymentController {

    private final PaymentMandateService mandateService;
    private final PaymentApiService apiService;

    public PaymentController(PaymentMandateService mandateService, PaymentApiService apiService) {
        this.mandateService = mandateService;
        this.apiService = apiService;
    }

    /**
     * The grant wizard's "review and sign" step: returns the exact canonical JSON that would be
     * signed and its SHA-256 - displayed so the grantor verifies what they are authorizing before
     * POSTing the grant.
     */
    @PostMapping("/mandates/preview")
    public MandatePreviewResponse preview(@Valid @RequestBody CreateMandateRequest body) {
        try {
            PaymentMandateService.MandatePreview preview = mandateService.preview(toDraft(body));
            return new MandatePreviewResponse(
                    preview.canonicalJson(), preview.canonicalHashSha256());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/mandates")
    public ResponseEntity<MandateResponse> grant(@Valid @RequestBody CreateMandateRequest body) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(apiService.grant(toDraft(body)));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // e.g. the agent FK rejected - surfaced as 422, not a raw 500.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        }
    }

    @GetMapping("/mandates")
    public List<MandateResponse> list(@RequestParam(required = false) UUID agentId) {
        if (agentId == null) {
            // The wizard always names an agent; a tenant-wide list stays a
            // deliberate later addition rather than an accidental leak.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agentId is required");
        }
        return apiService.listForAgent(agentId);
    }

    @GetMapping("/mandates/{id}")
    public MandateResponse get(@PathVariable UUID id) {
        // Read through the same tenant-scoped JPA layer; a mandate in a
        // different tenant schema is indistinguishable from absent.
        return apiService.findMandate(id).orElseThrow(PaymentController::noSuchMandate);
    }

    @PostMapping("/mandates/{id}/revoke")
    public MandateResponse revoke(
            @PathVariable UUID id, @RequestBody(required = false) RevokeMandateRequest body) {
        return apiService
                .revoke(id, body == null ? null : body.reason())
                .orElseThrow(PaymentController::noSuchMandate);
    }

    /**
     * Spend-vs-cap meters for all of an agent's mandates in one call (newest mandate first, same
     * order as the mandate list), so the console does not issue one request per mandate card.
     */
    @GetMapping("/mandates/spend")
    public List<MandateSpendResponse> spendForAgent(@RequestParam UUID agentId) {
        return mandateService.spendForAgent(agentId).stream()
                .map(
                        e ->
                                new MandateSpendResponse(
                                        e.mandateId(),
                                        e.view().maxPerTxnPaise(),
                                        e.view().maxDailyPaise(),
                                        e.view().maxMonthlyPaise(),
                                        e.view().dailySpendPaise(),
                                        e.view().monthlySpendPaise()))
                .toList();
    }

    /** Spend-vs-cap meter data for an agent's mandate card. */
    @GetMapping("/mandates/{id}/spend")
    public MandateSpendResponse spend(@PathVariable UUID id) {
        PaymentMandateService.MandateSpendView view = mandateService.spend(id);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such mandate");
        }
        return new MandateSpendResponse(
                id,
                view.maxPerTxnPaise(),
                view.maxDailyPaise(),
                view.maxMonthlyPaise(),
                view.dailySpendPaise(),
                view.monthlySpendPaise());
    }

    /**
     * Newest first. {@code limit} 1..200 (default 50, the old fixed cap); {@code offset} skips the
     * newest rows. Carries {@code X-Total-Count} (rows for the agent, or for the mandate when
     * {@code mandateId} is given) and {@code X-Has-More}; the body is still a plain array.
     */
    @GetMapping("/transactions")
    public ResponseEntity<List<TransactionResponse>> transactions(
            @RequestParam UUID agentId,
            @RequestParam(required = false) UUID mandateId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long offset) {
        int capped = Math.max(1, Math.min(limit, 200));
        long skip = ListPaging.offset(offset);
        OffsetLimitRequest page = new OffsetLimitRequest(skip, capped);
        List<TransactionResponse> body = apiService.transactions(agentId, mandateId, page);
        return ListPaging.respond(
                body, capped, skip, () -> apiService.countTransactions(agentId, mandateId));
    }

    @GetMapping("/transactions/{id}")
    public TransactionResponse transaction(@PathVariable UUID id) {
        return apiService
                .findTransaction(id)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "no such transaction"));
    }

    private static ResponseStatusException noSuchMandate() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "no such mandate");
    }

    private PaymentMandateService.MandateDraft toDraft(CreateMandateRequest body) {
        String grantor = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        if (grantor == null || grantor.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "no authenticated subject to grant as");
        }
        return new PaymentMandateService.MandateDraft(
                body.agentId(),
                grantor,
                body.scope(),
                body.maxPerTxnPaise(),
                body.maxDailyPaise(),
                body.maxMonthlyPaise(),
                body.allowedVpas(),
                body.allowedMerchantCategories(),
                body.approvalPolicy(),
                body.approvalThresholdPaise(),
                body.validFrom(),
                body.validUntil());
    }
}
