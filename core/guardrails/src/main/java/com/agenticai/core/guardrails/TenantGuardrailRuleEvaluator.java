package com.agenticai.core.guardrails;

import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Makes a {@link GuardrailRule} row actually do something.
 *
 * <p>Until this class existed, {@code guardrail_rule} was a write-only table:
 * {@link GuardrailRuleService} + {@link GuardrailRuleController} let a tenant
 * author, list, edit and delete rules, {@code V145} seeded three
 * {@link GuardrailRuleType#DEFAULT_TEMPLATE} presets into every tenant schema -
 * and nothing anywhere on the chat or tool-call path ever read a single row
 * back. A customer could create a rule, get a 201, see it in the list, and it
 * would silently never fire. This evaluator is the read side, wired into the
 * two places content can actually be intercepted:
 *
 * <ol>
 *   <li>{@link GuardedToolCallback#call} - every tool result, after the
 *       compiled-in {@link GuardrailService} pass. BLOCK and REDACT both
 *       apply, exactly as {@link GuardrailRuleAction} promises.</li>
 *   <li>{@code CustomRuleContentGuardrail} - the
 *       {@link ContentGuardrailType#CUSTOM} content guardrail, i.e. the
 *       agent-level "Custom Guardrail" toggle that was likewise offered in the
 *       API with no implementation behind it.</li>
 * </ol>
 *
 * <p><b>The compiled-in patterns remain the floor.</b> This never replaces
 * {@link PatternBasedGuardrailService}'s hardcoded HARD_BLOCK/REDACTION lists -
 * it runs after them and adds to them. A tenant cannot switch the platform's
 * own safety net off by deleting rows (and cannot delete a DEFAULT_TEMPLATE row
 * at all - see {@link GuardrailRuleService}).
 *
 * <p><b>Plan posture, stated explicitly:</b> AUTHORING a CUSTOM rule is gated
 * to PREMIUM/UNLIMITED ({@link GuardrailRuleService}); ENFORCING an already
 * authored one is not gated here. A tenant that downgrades to FREE keeps the
 * protection it configured while it was paying. This is deliberately the
 * opposite call from {@code AgentGuardrailService#resolveEnforceableGuardrails}
 * (which drops plan-locked types at enforcement time) and for a specific
 * reason: that method drops guardrails that cost money to run (semantic checks
 * make LLM calls), whereas a regex costs nothing, and silently un-protecting a
 * customer's tool output the moment their card fails is the wrong failure mode.
 *
 * <p><b>Off switch:</b> {@code app.guardrails.tenant-rules.enabled=false}
 * skips this evaluator entirely without touching any other guardrail - see the
 * constructor's javadoc for when that is the right lever to reach for.
 *
 * <p><b>Fail-open on infrastructure, fail-closed on a match</b> - the house
 * posture everywhere else in this package. A DB error, a missing tenant
 * context or an un-compilable pattern leave the text untouched rather than
 * breaking the turn; an actual match blocks or redacts.
 *
 * <p><b>SEC-028 (ReDoS).</b> Tenant patterns are compiled with RE2/J (linear
 * time, see {@link TenantRegexPolicy}). Only legacy rows RE2/J cannot compile
 * (backreferences, lookaround) fall back to java.util.regex. EVERY evaluation,
 * on either engine, reads the text through {@link BudgetedCharSequence}, which
 * aborts after a step budget or a wall-clock deadline. When a rule is aborted:
 * a BLOCK rule fails CLOSED (the content is treated as a violation - an
 * attacker must not be able to slip content past a deny rule by making it
 * expensive to scan), a REDACT rule is skipped. Both cases log a WARN and
 * increment {@code guardrails.tenant_rule.regex_aborted}.
 */
@Service
public class TenantGuardrailRuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TenantGuardrailRuleEvaluator.class);

    /** Same marker {@link PatternBasedGuardrailService} uses, so redacted text reads identically whichever layer caught it. */
    private static final String REDACTION_MARKER = "[REDACTED]";

    private final GuardrailRuleRepository repository;
    private final long cacheTtlMillis;
    private final boolean enabled;

    /**
     * Per-tenant compiled-rule cache. A tool-heavy turn calls {@link #apply}
     * once per tool call and the content path once or twice per turn; without
     * this every one of those is a DB round trip for a table that changes
     * maybe monthly. Keyed by tenant id (the same string the Hibernate
     * multi-tenancy resolver uses to route the query), so one tenant's edit
     * can never serve another tenant's rules.
     */
    private final Map<String, CachedRules> cache = new ConcurrentHashMap<>();

    /** SEC-028 defaults, used by the 3-arg constructor (tests / manual wiring). */
    static final long DEFAULT_MAX_REGEX_STEPS = 5_000_000L;
    static final long DEFAULT_REGEX_TIMEOUT_MS = 250L;

    /** SEC-028: upper bound on characters read per rule evaluation ({@code app.guardrails.tenant-rules.max-regex-steps}). */
    private final long maxRegexSteps;

    /** SEC-028: wall-clock bound per rule evaluation ({@code app.guardrails.tenant-rules.regex-timeout-ms}). */
    private final long regexTimeoutNanos;

    /**
     * @param enabled the operational off switch, {@code
     *     app.guardrails.tenant-rules.enabled}. Default TRUE - enforcing these
     *     rules is the entire point of this class, and a soft launch would just
     *     be the old broken behaviour under a new name.
     *
     *     <p>It exists because this capability had never executed in production
     *     before the deploy that introduced it: tenant rules were accepted and
     *     silently ignored from V145 (2026-07-27) until then. The first time
     *     any of it runs against real customer data is the first time anyone
     *     finds out whether some tenant's long-forgotten regex is over-broad,
     *     and without this the only remedy would be rolling the whole core
     *     image back - taking every unrelated fix in that image with it. Set
     *     this to {@code false} to neutralise tenant-authored rules ALONE.
     *
     *     <p>Turning it off is NOT "guardrails off": {@link
     *     PatternBasedGuardrailService}/{@link SemanticGuardrailService} still
     *     run on every tool call, the compiled-in prompt-injection and
     *     secret-redaction patterns still apply, and the per-agent content
     *     guardrails still apply. Only rows from {@code guardrail_rule} stop
     *     being consulted - i.e. exactly the behaviour prod had before this
     *     class existed.
     */
    public TenantGuardrailRuleEvaluator(
        GuardrailRuleRepository repository, long cacheTtlSeconds, boolean enabled
    ) {
        this(repository, cacheTtlSeconds, enabled, DEFAULT_MAX_REGEX_STEPS, DEFAULT_REGEX_TIMEOUT_MS);
    }

    /**
     * @param maxRegexSteps SEC-028 step cap per rule evaluation; the effective
     *     budget is {@code min(maxRegexSteps, 50_000 + 200 * text.length())}
     * @param regexTimeoutMs SEC-028 wall-clock cap per rule evaluation
     */
    @org.springframework.beans.factory.annotation.Autowired
    public TenantGuardrailRuleEvaluator(
        GuardrailRuleRepository repository,
        @Value("${app.guardrails.tenant-rules.cache-ttl-seconds:60}") long cacheTtlSeconds,
        @Value("${app.guardrails.tenant-rules.enabled:true}") boolean enabled,
        @Value("${app.guardrails.tenant-rules.max-regex-steps:5000000}") long maxRegexSteps,
        @Value("${app.guardrails.tenant-rules.regex-timeout-ms:250}") long regexTimeoutMs
    ) {
        this.maxRegexSteps = Math.max(1_000L, maxRegexSteps);
        this.regexTimeoutNanos = Math.max(1L, regexTimeoutMs) * 1_000_000L;
        this.repository = repository;
        this.cacheTtlMillis = Math.max(0L, cacheTtlSeconds) * 1000L;
        this.enabled = enabled;
        if (!enabled) {
            log.warn("Tenant-authored guardrail rules are DISABLED "
                + "(app.guardrails.tenant-rules.enabled=false) - guardrail_rule rows will not be consulted. "
                + "The platform's compiled-in guardrails are unaffected and still run on every tool call.");
        }
    }

    /**
     * Applies this tenant's enabled rules to {@code text}.
     *
     * @param text the content to check - returned unchanged when there are no
     *     rules, no tenant context, or anything at all goes wrong
     * @param allowRedaction whether REDACT rules may rewrite the text. False on
     *     paths that have no way to hand a rewritten string back (see
     *     {@code AgentChatService#enforceInboundGuardrails}) - there, a REDACT
     *     rule is skipped outright rather than evaluated and silently ignored,
     *     so it cannot produce a "checked and passed" log line for a check that
     *     could never have had an effect.
     * @return the outcome; never null
     */
    public Outcome apply(String text, boolean allowRedaction) {
        if (!enabled || text == null || text.isEmpty()) {
            return Outcome.unchanged(text);
        }
        List<CompiledRule> rules;
        try {
            rules = rulesForCurrentTenant();
        } catch (RuntimeException e) {
            log.warn("Could not load tenant guardrail rules - leaving content untouched (fail-open)", e);
            return Outcome.unchanged(text);
        }
        if (rules.isEmpty()) {
            return Outcome.unchanged(text);
        }

        // BLOCK before REDACT: if the content is going to be withheld entirely
        // there is no point paying for the rewrite, and a blocked-by name is
        // more useful than a half-redacted string nobody will ever see.
        for (CompiledRule rule : rules) {
            if (rule.action() != GuardrailRuleAction.BLOCK) {
                continue;
            }
            if (matches(rule, text)) {
                return Outcome.blocked(text, rule.name());
            }
        }
        if (!allowRedaction) {
            return Outcome.unchanged(text);
        }

        String working = text;
        boolean anyRedaction = false;
        for (CompiledRule rule : rules) {
            if (rule.action() != GuardrailRuleAction.REDACT) {
                continue;
            }
            String next = redact(rule, working);
            if (next != null && !next.equals(working)) {
                working = next;
                anyRedaction = true;
            }
        }
        return anyRedaction ? Outcome.redacted(working) : Outcome.unchanged(text);
    }

    /** Drops the cached rules for the calling tenant so the next evaluation re-reads them. Called on every rule write. */
    public void invalidateCurrentTenant() {
        currentTenantId().ifPresent(cache::remove);
    }

    private List<CompiledRule> rulesForCurrentTenant() {
        String tenantId = currentTenantId().orElse(null);
        if (tenantId == null) {
            // No tenant on this thread means the repository cannot be routed to
            // a schema - a rule list would be meaningless (or worse, another
            // tenant's). Silence is correct here: this is a normal state on
            // some background/webhook threads, not an error.
            return List.of();
        }
        CachedRules cached = cache.get(tenantId);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) {
            return cached.rules();
        }
        List<CompiledRule> compiled = compile(repository.findAllByOrderByCreatedAtAsc());
        cache.put(tenantId, new CachedRules(compiled, now + cacheTtlMillis));
        return compiled;
    }

    private static List<CompiledRule> compile(List<GuardrailRule> rows) {
        List<CompiledRule> compiled = new ArrayList<>(rows.size());
        for (GuardrailRule row : rows) {
            if (!row.isEnabled() || row.getPattern() == null || row.getPattern().isBlank()) {
                continue;
            }
            try {
                // CASE_INSENSITIVE to match what the compiled-in lists in
                // PatternBasedGuardrailService do, and what the seeded
                // DEFAULT_TEMPLATE patterns (all lowercase) assume. Documented
                // on the authoring screen so it is not a surprise.
                // SEC-028: RE2/J first (linear time); java.util.regex only for
                // legacy rows RE2/J cannot parse.
                com.google.re2j.Pattern linear = TenantRegexPolicy.compileLinearOrNull(row.getPattern());
                Pattern legacy = linear != null ? null : Pattern.compile(row.getPattern(), Pattern.CASE_INSENSITIVE);
                if (legacy != null) {
                    log.warn("Guardrail rule {} ('{}') uses syntax the linear-time engine does not support - "
                        + "evaluating it with the budgeted backtracking engine; re-save it in RE2 syntax",
                        row.getId(), row.getName());
                }
                compiled.add(new CompiledRule(row.getId(), row.getName(), linear, legacy, row.getAction()));
            } catch (PatternSyntaxException e) {
                // Rules authored before GuardrailRuleService started rejecting
                // un-compilable patterns can still be in the table. Skip the
                // one bad rule rather than failing every check for this tenant.
                log.warn("Skipping guardrail rule {} ('{}') - its pattern does not compile: {}",
                    row.getId(), row.getName(), e.getMessage());
            } catch (IllegalArgumentException e) {
                // TenantRegexPolicy: too long / expands too much - compiling it at all is the risk.
                log.warn("Skipping guardrail rule {} ('{}') - its pattern is too large to evaluate safely: {}",
                    row.getId(), row.getName(), e.getMessage());
            }
        }
        return List.copyOf(compiled);
    }

    private boolean matches(CompiledRule rule, String text) {
        try {
            return find(rule, text);
        } catch (RegexBudgetExceededException e) {
            // A linear (RE2/J) rule cannot be slowed by its input, so a missed wall-clock deadline there means the
            // thread was descheduled (a laptop running a local model, a paused VM), not that the text is hostile.
            // One retry with a fresh deadline; the step cap is unchanged, so a genuinely huge scan still fails.
            if (e.timedOut() && rule.linear() != null) {
                try {
                    return find(rule, text);
                } catch (RegexBudgetExceededException again) {
                    // still over budget: fall through to the fail-closed path
                }
            }
            // SEC-028: a deny rule fails CLOSED - scanning cost must not be a bypass.
            logBudgetExceeded(rule, text, "treating the content as a violation (fail-closed)");
            return true;
        }
    }

    private boolean find(CompiledRule rule, String text) {
        CharSequence input = budgeted(text);
        return rule.linear() != null
            ? rule.linear().matcher(input).find()
            : rule.legacy().matcher(input).find();
    }

    /** @return the rewritten text, or null if this rule matched nothing or could not be applied safely. */
    private String redact(CompiledRule rule, String text) {
        try {
            CharSequence input = budgeted(text);
            String result;
            if (rule.linear() != null) {
                com.google.re2j.Matcher matcher = rule.linear().matcher(input);
                if (!matcher.find()) {
                    return null;
                }
                matcher.reset();
                result = matcher.replaceAll(com.google.re2j.Matcher.quoteReplacement(REDACTION_MARKER));
            } else {
                Matcher matcher = rule.legacy().matcher(input);
                if (!matcher.find()) {
                    return null;
                }
                matcher.reset();
                result = matcher.replaceAll(Matcher.quoteReplacement(REDACTION_MARKER));
            }
            log.info("Guardrail rule '{}' redacted matched text", rule.name());
            return result;
        } catch (RegexBudgetExceededException e) {
            logBudgetExceeded(rule, text, "skipping this redaction");
            return null;
        }
    }

    private static void logBudgetExceeded(CompiledRule rule, String text, String consequence) {
        String engine = rule.linear() != null ? "re2j" : "legacy";
        log.warn("Guardrail rule {} ('{}') exceeded its matching budget on {} chars ({} engine) - {}",
            rule.id(), rule.name(), text.length(), engine, consequence);
        try {
            io.micrometer.core.instrument.Metrics.counter("guardrails.tenant_rule.regex_aborted",
                "action", String.valueOf(rule.action()), "engine", engine).increment();
        } catch (RuntimeException ignored) {
            // metrics must never change the guardrail outcome
        }
    }

    /**
     * Wraps the text so a tenant-authored pattern cannot hang a chat turn.
     *
     * <p>These patterns are regexes typed by a customer into a web form and run
     * on our servers, against text an attacker can often influence (a tool
     * result, a model reply). {@code (a+)+$} against a long line is the
     * textbook catastrophic-backtracking case and java.util.regex has no
     * built-in timeout, so the only portable defence is to count how many
     * characters the matcher reads and abort when it reads absurdly more than
     * the input length. The budget scales with input, so a legitimate scan of a
     * 20k-char tool result never trips it.
     */
    private CharSequence budgeted(String text) {
        long budget = Math.min(maxRegexSteps, 50_000L + (long) text.length() * 200L);
        return new BudgetedCharSequence(text, new long[] { budget, System.nanoTime() + regexTimeoutNanos });
    }

    private static Optional<String> currentTenantId() {
        return TenantContext.current().map(TenantContext::getTenantId);
    }

    /** What {@link #apply} decided. {@code text} is what the caller should use going forward. */
    public record Outcome(String text, boolean redacted, String blockedByRuleName) {

        static Outcome unchanged(String text) {
            return new Outcome(text, false, null);
        }

        static Outcome redacted(String text) {
            return new Outcome(text, true, null);
        }

        static Outcome blocked(String text, String ruleName) {
            return new Outcome(text, false, ruleName);
        }

        public boolean blocked() {
            return blockedByRuleName != null;
        }
    }

    /** Exactly one of {@code linear} (RE2/J) and {@code legacy} (java.util.regex) is non-null. */
    private record CompiledRule(
        UUID id, String name, com.google.re2j.Pattern linear, Pattern legacy, GuardrailRuleAction action
    ) {
    }

    private record CachedRules(List<CompiledRule> rules, long expiresAt) {
    }

    /** Thrown by {@link BudgetedCharSequence} and never escapes this class. */
    private static final class RegexBudgetExceededException extends RuntimeException {
        private final boolean timedOut;

        RegexBudgetExceededException(boolean timedOut) {
            super(null, null, false, false);
            this.timedOut = timedOut;
        }

        /** True when only the wall-clock deadline was missed (the step cap still had room). */
        boolean timedOut() {
            return timedOut;
        }
    }

    /**
     * A read-counting view of a string. The budget is shared (a {@code long[2]}:
     * remaining steps, deadline in {@link System#nanoTime()} units) with every
     * subsequence the matcher derives, so group extraction cannot reset it.
     * The deadline is checked every 1024 reads to keep the per-char cost low.
     */
    private record BudgetedCharSequence(CharSequence delegate, long[] remaining) implements CharSequence {

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            long left = --remaining[0];
            if (left < 0) {
                throw new RegexBudgetExceededException(false);
            }
            if ((left & 1023L) == 0 && System.nanoTime() - remaining[1] > 0) {
                throw new RegexBudgetExceededException(true);
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BudgetedCharSequence(delegate.subSequence(start, end), remaining);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
