package com.agenticai.core.guardrails;

import com.agenticai.core.security.TextRedaction;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * V1 {@link GuardrailService} - see that interface's javadoc for why this is deliberately scoped to
 * cheap, high-confidence regex checks rather than a claim of solving prompt injection generally.
 *
 * <p>Tech-Stack-Audit-2026.md P2.1 / Master-Roadmap-2026.md item #7 (Guardrails hardening) flipped
 * the platform-wide default to {@link SemanticGuardrailService} - see that class's javadoc. This
 * class is no longer selected implicitly ({@code matchIfMissing} was removed): it now requires an
 * explicit {@code app.guardrails.implementation=pattern}, kept available as a deliberate opt-out
 * for a deployment that wants the cheap regex-only layer with zero extra LLM calls per tool result
 * (e.g. a cost-sensitive free-tier pool). Mutually exclusive with {@link SemanticGuardrailService}
 * by construction (each is gated to the opposite value of the same property), so {@code
 * AgentToolProvider}'s single unqualified {@code GuardrailService} injection point always resolves
 * exactly one bean - never zero, never two.
 *
 * <p>Two independent pattern lists, checked in this order:
 *
 * <ol>
 *   <li>{@link #HARD_BLOCK_PATTERNS} - if any match, the entire output is withheld ({@link
 *       GuardrailViolationException}). Kept intentionally narrow (blatant, unambiguous injection
 *       markers) to minimize false positives against legitimate tool output that happens to contain
 *       the word "ignore" or similar in an innocuous context.
 *   <li>{@link #REDACTION_PATTERNS} - secret-shaped substrings (cloud provider API key formats, PEM
 *       private key headers, JWTs) are replaced with {@code [REDACTED]} rather than blocking the
 *       whole output - a tool result that happens to echo back a credential (e.g. a misconfigured
 *       MCP server, or a file-read tool reading a .env file) shouldn't silently poison the model's
 *       context with a live secret, but the rest of that output is still useful to the agent.
 *   <li><b>Context-budget cap</b> ({@link #MAX_TOOL_OUTPUT_CHARS}, applied first, before either
 *       pattern pass) - a single tool call otherwise hands back an unbounded string straight into
 *       the model's context. Text tools stay well under this cap in practice; the failure mode this
 *       actually guards against is a tool that returns raw binary as text - a
 *       Puppeteer/browser-automation MCP server's screenshot tool returning a base64 PNG being the
 *       concrete incident this was added for: one screenshot's base64 payload alone pushed a single
 *       chat turn to 240k+ tokens against Claude's 200k context window, and the provider rejected
 *       the whole request with no indication which tool call caused it. Every tool call in this
 *       codebase funnels through this method (see {@code GuardedToolCallback#call}), so capping
 *       here - once - protects every tool, MCP or built-in, present or future, rather than
 *       requiring each new tool integration to remember to cap itself.
 * </ol>
 */
@Service
@ConditionalOnProperty(name = "app.guardrails.implementation", havingValue = "pattern")
public class PatternBasedGuardrailService implements GuardrailService {

    private static final Logger log = LoggerFactory.getLogger(PatternBasedGuardrailService.class);

    // ~5k tokens' worth of plain text (roughly 4 chars/token) - generous for
    // any legitimate text tool result (a knowledge-base hit, an email body,
    // an API response) while still keeping a single tool call from being
    // able to single-handedly exhaust a model's context window. See the
    // class javadoc's "Context-budget cap" entry for the incident this
    // closes.
    private static final int MAX_TOOL_OUTPUT_CHARS = 20_000;

    private static final List<Pattern> HARD_BLOCK_PATTERNS =
            List.of(
                    Pattern.compile(
                            "ignore\\s+(all\\s+)?(the\\s+)?(above|previous)\\s+instructions",
                            Pattern.CASE_INSENSITIVE),
                    Pattern.compile(
                            "disregard\\s+(your|the)\\s+system\\s+prompt",
                            Pattern.CASE_INSENSITIVE),
                    Pattern.compile(
                            "reveal\\s+(your|the)\\s+system\\s+prompt", Pattern.CASE_INSENSITIVE),
                    Pattern.compile(
                            "you\\s+are\\s+now\\s+in\\s+(developer|dan|jailbreak)\\s+mode",
                            Pattern.CASE_INSENSITIVE),
                    Pattern.compile(
                            "###\\s*(system|admin|override)\\s*###", Pattern.CASE_INSENSITIVE));

    // Named so a match's source is obvious in logs/error messages without
    // re-deriving it from the pattern itself.
    private static final List<NamedPattern> REDACTION_PATTERNS =
            List.of(
                    new NamedPattern("AWS access key", Pattern.compile("AKIA[0-9A-Z]{16}")),
                    new NamedPattern("Google API key", Pattern.compile("AIza[0-9A-Za-z\\-_]{35}")),
                    new NamedPattern("GitHub token", Pattern.compile("gh[pousr]_[A-Za-z0-9]{36,}")),
                    new NamedPattern(
                            "Slack token", Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}")),
                    new NamedPattern("OpenAI-style key", Pattern.compile("sk-[A-Za-z0-9]{20,}")),
                    new NamedPattern(
                            "JWT",
                            Pattern.compile(
                                    "eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")));

    @Override
    public String sanitizeToolOutput(String toolName, String rawOutput) {
        if (rawOutput == null || rawOutput.isEmpty()) {
            return rawOutput;
        }

        // #815 Governance follow-up - this cap was documented in this class's own javadoc
        // ("applied first, before either pattern pass") since the class was written but was
        // never actually implemented here - a real, live gap (not a stale doc) found while
        // adding test coverage for this class, confirmed by grepping MAX_TOOL_OUTPUT_CHARS's
        // only other reference being its own declaration. SemanticGuardrailService (the
        // platform's DEFAULT GuardrailService, matchIfMissing = true) delegates its base pass
        // to this exact method, so this gap was live for every tool call platform-wide, not just
        // the opt-in pattern-only deployment mode - including against the exact base64-screenshot
        // incident this class's javadoc describes as the reason this cap exists.
        String output = rawOutput;
        if (output.length() > MAX_TOOL_OUTPUT_CHARS) {
            log.warn(
                    "Tool '{}' returned {} chars - truncating to the {}-char context-budget cap"
                            + " before any pattern check (see this class's javadoc's \"Context-budget"
                            + " cap\" entry)",
                    toolName,
                    output.length(),
                    MAX_TOOL_OUTPUT_CHARS);
            output = output.substring(0, MAX_TOOL_OUTPUT_CHARS);
        }

        for (Pattern hardBlock : HARD_BLOCK_PATTERNS) {
            if (hardBlock.matcher(output).find()) {
                log.warn(
                        "Guardrail hard-blocked output from tool '{}' - matched pattern: {}",
                        toolName,
                        hardBlock);
                throw new GuardrailViolationException(
                        toolName, "output contained a prompt-injection marker");
            }
        }

        // PEM private-key blocks are found in code, not by a regular expression: the lazy
        // "BEGIN ... END" pattern rescanned to the end of the output from every BEGIN line.
        String sanitized = TextRedaction.redactPrivateKeyBlocks(output, "[REDACTED]");
        if (!sanitized.equals(output)) {
            log.warn(
                    "Guardrail redacted a likely PEM private key in output from tool '{}'",
                    toolName);
        }
        for (NamedPattern redaction : REDACTION_PATTERNS) {
            if (redaction.pattern.matcher(sanitized).find()) {
                log.warn(
                        "Guardrail redacted a likely {} in output from tool '{}'",
                        redaction.label,
                        toolName);
                sanitized = redaction.pattern.matcher(sanitized).replaceAll("[REDACTED]");
            }
        }
        return sanitized;
    }

    private record NamedPattern(String label, Pattern pattern) {}
}
