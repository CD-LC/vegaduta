package com.agenticai.core.chat.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a tenant's {@link LlmGatewayContentPolicy} for {@link
 * LlmGatewayContentPolicyAdvisor} - the read half of the V300 settings table.
 * The write half is {@link #upsert}, exposed through {@link
 * LlmGatewayContentPolicyController} and the tenant settings UI.
 */
@Service
public class LlmGatewayContentPolicyService {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayContentPolicyService.class);

    private final LlmGatewayContentPolicyRepository repository;

    public LlmGatewayContentPolicyService(LlmGatewayContentPolicyRepository repository) {
        this.repository = repository;
    }

    /**
     * @param tenantId null for an internal/system call (no tenant context) -
     *     resolves to the disabled default without touching the database,
     *     same "null tenant skips governance cleanly" posture {@link
     *     LlmGatewayBudgetService}/{@link LlmGatewayRateLimiterService} both
     *     already take.
     * @return the tenant's stored policy, or {@link LlmGatewayContentPolicy#unconfiguredDefault}
     *     (injection screening ON in FLAG mode, PII redaction OFF) when no row
     *     exists or the lookup itself fails. A settings-lookup hiccup must
     *     never fail toward blocking or altering a legitimate call - FLAG does
     *     neither (it only audit-logs a match), so this keeps the same
     *     fail-open posture every other gateway check in this package takes
     *     on its own dependency failures.
     */
    @Transactional(readOnly = true)
    public LlmGatewayContentPolicy resolve(String tenantId) {
        if (tenantId == null) {
            return LlmGatewayContentPolicy.disabledDefault(null);
        }
        try {
            return repository.findByTenantId(tenantId).orElseGet(() -> LlmGatewayContentPolicy.unconfiguredDefault(tenantId));
        } catch (RuntimeException e) {
            log.warn("LLM gateway content-policy lookup failed for tenant {} - failing open to the non-blocking default", tenantId, e);
            return LlmGatewayContentPolicy.unconfiguredDefault(tenantId);
        }
    }

    /** The full desired policy state written by {@link #upsert}. */
    public record PolicyUpdate(
        boolean piiRedactionEnabled,
        boolean piiRedactEmail,
        boolean piiRedactPhone,
        boolean piiRedactGovernmentId,
        boolean piiRedactCreditCard,
        boolean injectionScreeningEnabled,
        LlmGatewayContentPolicyAction injectionScreeningAction
    ) {}

    /**
     * Creates or updates the tenant's single policy row. Unlike {@link #resolve}
     * this does NOT fail open: a failed write must surface to the caller so the
     * tenant is never told a safety setting saved when it did not. There is no
     * cache in this service ({@link #resolve} reads the repository on every
     * call), so a successful write takes effect on the very next gateway call.
     *
     * @throws IllegalArgumentException for a null/blank tenant, null update or null action
     */
    @Transactional
    public LlmGatewayContentPolicy upsert(String tenantId, PolicyUpdate update, String actor) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (update == null) {
            throw new IllegalArgumentException("policy is required");
        }
        if (update.injectionScreeningAction() == null) {
            throw new IllegalArgumentException("injectionScreeningAction must be FLAG or BLOCK");
        }
        LlmGatewayContentPolicy policy = repository.findByTenantId(tenantId)
            .orElseGet(() -> {
                LlmGatewayContentPolicy created = new LlmGatewayContentPolicy(tenantId);
                created.setCreatedBy(actor);
                return created;
            });
        policy.setPiiRedactionEnabled(update.piiRedactionEnabled());
        policy.setPiiRedactEmail(update.piiRedactEmail());
        policy.setPiiRedactPhone(update.piiRedactPhone());
        policy.setPiiRedactGovernmentId(update.piiRedactGovernmentId());
        policy.setPiiRedactCreditCard(update.piiRedactCreditCard());
        policy.setInjectionScreeningEnabled(update.injectionScreeningEnabled());
        policy.setInjectionScreeningAction(update.injectionScreeningAction());
        return repository.save(policy);
    }
}
