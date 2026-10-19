package com.agenticai.core.guardrails;

import com.agenticai.core.guardrails.dto.GuardrailRuleRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 2026-07-27 billing audit follow-up: {@code core/guardrails} had zero
 * plan-awareness - nothing here modeled a tenant-authored rule at all, so
 * every tenant (FREE included) was implicitly unrestricted the moment such a
 * concept existed. This service is both the new write path for tenant-
 * authored {@link GuardrailRuleType#CUSTOM} rules AND the gate: authoring
 * (create/update/delete) is a PREMIUM/UNLIMITED feature, exactly the same
 * posture and exception type {@code core.agents.AgentService#updateModel}
 * already established for the FREE-tier model-selection gate. Reading rules
 * (including the platform's {@link GuardrailRuleType#DEFAULT_TEMPLATE}
 * safety-net presets) stays open to every tenant regardless of plan - FREE
 * tenants keep full read/use access to the default templates, they just
 * cannot author or edit their own.
 *
 * <p>{@link GuardrailRuleType#DEFAULT_TEMPLATE} rows are never mutable by
 * anyone through this service, regardless of plan - see {@link
 * #assertMutable}.
 */
@Service
public class GuardrailRuleService {

    private final GuardrailRuleRepository repository;
    private final TenantLimits tenantLimits;
    // Rules are cached per tenant for a short TTL on the enforcement side, so
    // every write here has to evict - otherwise "I turned that rule off" takes
    // up to a minute to take effect, which for a safety control reads as a bug.
    private final TenantGuardrailRuleEvaluator evaluator;

    public GuardrailRuleService(
        GuardrailRuleRepository repository, TenantLimits tenantLimits, TenantGuardrailRuleEvaluator evaluator
    ) {
        this.repository = repository;
        this.tenantLimits = tenantLimits;
        this.evaluator = evaluator;
    }

    /** Every rule visible in the calling tenant's schema - platform defaults plus that tenant's own custom rules. Open to every plan. */
    @Transactional(readOnly = true)
    public List<GuardrailRule> listAll() {
        return repository.findAllByOrderByCreatedAtAsc();
    }

    /** Open to every plan - reading/using a rule (including a default template) is never gated. */
    @Transactional(readOnly = true)
    public GuardrailRule get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new GuardrailRuleNotFoundException(id));
    }

    /**
     * Authors a new {@link GuardrailRuleType#CUSTOM} rule for the calling
     * tenant. Rejects outright for a FREE tenant (direct feedback: billing
     * audit found zero plan-awareness here) - same {@link
     * TenantLimitExceededException} shape {@code AgentService#updateModel}
     * uses for its FREE-tier gate, which the platform's shared {@code
     * GlobalExceptionHandler} already maps to HTTP 403.
     */
    @Transactional
    public GuardrailRule createCustom(GuardrailRuleRequest request) {
        String tenantId = currentTenantId();
        assertCanAuthorCustomRules(tenantId);
        assertPatternCompiles(request.pattern());
        boolean enabled = request.enabled() == null || request.enabled();
        GuardrailRule rule = new GuardrailRule(
            tenantId,
            GuardrailRuleType.CUSTOM,
            request.name(),
            request.description(),
            request.pattern(),
            request.action(),
            enabled
        );
        GuardrailRule saved = repository.save(rule);
        evaluator.invalidateCurrentTenant();
        return saved;
    }

    /** Updates an existing {@code CUSTOM} rule. Rejects a default template ({@link GuardrailRuleImmutableException}) or a FREE tenant ({@link TenantLimitExceededException}). */
    @Transactional
    public GuardrailRule update(UUID id, GuardrailRuleRequest request) {
        GuardrailRule rule = get(id);
        assertMutable(rule);
        assertCanAuthorCustomRules(currentTenantId());
        assertPatternCompiles(request.pattern());
        rule.applyEdit(request.name(), request.description(), request.pattern(), request.action(), request.enabled());
        GuardrailRule saved = repository.save(rule);
        evaluator.invalidateCurrentTenant();
        return saved;
    }

    /** Deletes an existing {@code CUSTOM} rule. Same two gates as {@link #update}. */
    @Transactional
    public void delete(UUID id) {
        GuardrailRule rule = get(id);
        assertMutable(rule);
        assertCanAuthorCustomRules(currentTenantId());
        repository.delete(rule);
        evaluator.invalidateCurrentTenant();
    }

    /**
     * Rejects a pattern that java.util.regex cannot compile, at authoring time.
     *
     * <p>Without this the row saves happily and then fails to compile once per
     * cache refresh forever, on a background code path, where the author never
     * sees it - the rule just silently never fires. Throws {@link
     * IllegalArgumentException}, which the platform's shared {@code
     * GlobalExceptionHandler} already maps to a 400 carrying the message, so
     * the authoring screen can show the regex engine's own explanation of
     * what's wrong with the pattern.
     */
    private void assertPatternCompiles(String pattern) {
        // SEC-028: length cap, repetition-expansion cap, and the pattern must
        // compile under the linear-time RE2/J engine the evaluator runs it with,
        // so a tenant cannot store a catastrophically backtracking regex.
        TenantRegexPolicy.validateForAuthoring(pattern);
        try {
            Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                "That pattern is not a valid regular expression: " + e.getDescription()
                    + (e.getIndex() >= 0 ? " (at position " + e.getIndex() + ")" : ""));
        }
    }

    /** A {@code DEFAULT_TEMPLATE} row is never mutable, regardless of plan or tenant. */
    private void assertMutable(GuardrailRule rule) {
        if (rule.getRuleType() == GuardrailRuleType.DEFAULT_TEMPLATE) {
            throw new GuardrailRuleImmutableException(rule.getId());
        }
    }

    /**
     * The FREE-tier authoring gate. Mirrors {@code AgentService#updateModel}'s
     * exact pattern: read the tenant's {@link TenantPlan} through the
     * read-only {@link TenantLimits} accessor (never {@code TenantPlan},
     * {@code TenantLimits}, or any DB migration modified directly - this
     * service is the only new code), and reject a FREE tenant with a
     * client-actionable, "upgrade to unlock" message.
     */
    private void assertCanAuthorCustomRules(String tenantId) {
        TenantPlan plan = tenantId != null ? tenantLimits.plan(tenantId) : TenantPlan.UNLIMITED;
        if (plan == TenantPlan.FREE) {
            throw new TenantLimitExceededException(
                "Authoring custom guardrail rules is a Premium feature - the FREE plan uses the platform's "
                    + "default safety-net guardrail presets only. Upgrade your plan to create, edit, or delete "
                    + "your own guardrail rules."
            );
        }
    }

    private String currentTenantId() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse(null);
    }
}
