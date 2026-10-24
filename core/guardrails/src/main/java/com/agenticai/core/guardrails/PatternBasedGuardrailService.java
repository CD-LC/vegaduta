package com.agenticai.core.guardrails;

import com.agenticai.core.prompts.PromptResolver;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * V1 {@link GuardrailService} - see that interface's javadoc for why this is
 * deliberately scoped to cheap, high-confidence regex checks rather than a
 * claim of solving prompt injection generally.
 *
 * <p>Tech-Stack-Audit-2026.md P2.1 / Master-Roadmap-2026.md item #7
 * (Guardrails hardening) flipped the platform-wide default to {@link
 * SemanticGuardrailService} - see that class's javadoc. This class is no
 * longer selected implicitly ({@code matchIfMissing} was removed): it now
 * requires an explicit {@code app.guardrails.implementation=pattern}, kept
 * available as a deliberate opt-out for a deployment that wants the cheap
 * regex-only layer with zero extra LLM calls per tool result (e.g. a
 * cost-sensitive free-tier pool). Mutually exclusive with {@link
 * SemanticGuardrailService} by construction (each is gated to the opposite
 * value of the same property), so {@code AgentToolProvider}'s single
 * unqualified {@code GuardrailService} injection point always resolves
 * exactly one bean - never zero, never two.
 *
 * <p>Two independent pattern lists, checked in this order:
 * <ol>
 *   <li>{@link #HARD_BLOCK_PATTERNS} - if any match, the entire output is
 *       withheld ({@link GuardrailViolationException}). Kept intentionally
 *       narrow (blatant, unambiguous injection markers) to minimize false
 *       positives against legitimate tool output that happens to contain
 *       the word "ignore" or similar in an innocuous context.</li>
 *   <li>{@link #REDACTION_PATTERNS} - secret-shaped substrings (cloud
 *       provider API key formats, PEM private key headers, JWTs) are
 *       replaced with {@code [REDACTED]} rather than blocking the whole
 *       output - a tool result that happens to echo back a credential
 *       (e.g. a misconfigured MCP server, or a file-read tool reading a
 *       .env file) shouldn't silently poison the model's context with a
 *       live secret, but the rest of that output is still useful to the
 *       agent.</li>
 *   <li><b>Context-budget cap</b> ({@link ToolOutputProperties}, applied
 *       first, before either pattern pass) - a single tool call otherwise
 *       hands back an unbounded string straight into the model's context.
 *       Text tools stay well under this cap in practice; the failure mode
 *       this actually guards against is a tool that returns raw binary as
 *       text - a Puppeteer/browser-automation MCP server's screenshot tool
 *       returning a base64 PNG being the concrete incident this was added
 *       for: one screenshot's base64 payload alone pushed a single chat
 *       turn to 240k+ tokens against Claude's 200k context window, and the
 *       provider rejected the whole request with no indication which tool
 *       call caused it. Every tool call in this codebase funnels through
 *       this method (see {@code GuardedToolCallback#call}), so capping here
 *       - once - protects every tool, MCP or built-in, present or future,
 *       rather than requiring each new tool integration to remember to cap
 *       itself.</li>
 * </ol>
 */
@Service
@ConditionalOnProperty(name = "app.guardrails.implementation", havingValue = "pattern")
public class PatternBasedGuardrailService implements GuardrailService {

    private static final Logger log = LoggerFactory.getLogger(PatternBasedGuardrailService.class);

    // The context-budget cap is app.tools.output.* (ToolOutputProperties): one cap for every tool,
    // shared with GuardedToolCallback, which has usually applied it already by the time a result gets
    // here. A result cut there is no longer than the cap, so cutting it again changes nothing.
    private final ToolOutputProperties outputLimits;

    /** Code defaults for the output cap (a context without {@code ToolOutputProperties}). */
    public PatternBasedGuardrailService() {
        this(null);
    }

    @Autowired
    public PatternBasedGuardrailService(ToolOutputProperties outputLimits) {
        this.outputLimits = outputLimits != null ? outputLimits : new ToolOutputProperties();
    }

    // The patterns are classpath resources (guardrails/patterns/<locale>/injection-markers.txt, every locale on the
    // classpath, English first; guardrails/patterns/redaction.txt), not Java literals: the shipped files hold exactly
    // the patterns this class compiled before they moved. See PatternSets.
    private static final List<Pattern> HARD_BLOCK_PATTERNS = PatternSets.localized("injection-markers").stream()
        .map(PatternSets.Entry::pattern)
        .toList();

    // Named so a match's source is obvious in logs/error messages without
    // re-deriving it from the pattern itself.
    private static final List<NamedPattern> REDACTION_PATTERNS = PatternSets.shared("redaction").stream()
        .map(e -> new NamedPattern(e.label(), e.pattern()))
        .toList();

    /** Where the withhold reason comes from ({@code toolresult.guard.pattern_injection_marker}); the shipped text unless wired. */
    private volatile PromptResolver prompts = PromptResolver.defaultsOnly();

    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        if (resolver != null) {
            this.prompts = resolver;
        }
    }

    @Override
    public String sanitizeToolOutput(String toolName, String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return rawOutput;
        }

        // The cap is applied before either pattern pass: bound the context first, no exceptions. It was
        // documented here for a long time before it was implemented (#815 Governance follow-up) and,
        // once implemented, was unreachable through GuardedToolCallback because a length check in front
        // of it refused the same output. Now both use the same cap and the same marker.
        String output = rawOutput;
        if (output.length() > outputLimits.limitFor(toolName)) {
            log.warn(
                "Tool '{}' returned {} chars - truncating to the {}-char context-budget cap before any pattern check "
                    + "(see this class's javadoc's \"Context-budget cap\" entry)",
                toolName, output.length(), outputLimits.limitFor(toolName)
            );
            output = outputLimits.limit(toolName, output);
        }

        for (Pattern hardBlock : HARD_BLOCK_PATTERNS) {
            if (hardBlock.matcher(output).find()) {
                log.warn("Guardrail hard-blocked output from tool '{}' - matched pattern: {}", toolName, hardBlock);
                throw new GuardrailViolationException(
                    toolName, GuardrailPrompts.scopedText(prompts, GuardrailPrompts.PATTERN_INJECTION_MARKER));
            }
        }

        String sanitized = output;
        for (NamedPattern redaction : REDACTION_PATTERNS) {
            if (redaction.pattern.matcher(sanitized).find()) {
                log.warn("Guardrail redacted a likely {} in output from tool '{}'", redaction.label, toolName);
                sanitized = redaction.pattern.matcher(sanitized).replaceAll("[REDACTED]");
            }
        }
        return sanitized;
    }

    private record NamedPattern(String label, Pattern pattern) {
    }
}
