package com.agenticai.core.guardrails.impl;

import com.agenticai.core.guardrails.ContentGuardrail;
import com.agenticai.core.guardrails.ContentGuardrailResult;
import com.agenticai.core.guardrails.ContentGuardrailType;
import com.agenticai.core.guardrails.GuardrailPrompts;
import com.agenticai.core.guardrails.GuardrailSettings;
import com.agenticai.core.guardrails.GuardrailTuningProperties;
import com.agenticai.core.guardrails.PatternSets;
import com.agenticai.core.prompts.PromptResolver;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Detects common prompt injection and jailbreak attempts in user input.
 *
 * <p>This guardrail looks for known attack patterns:
 * <ul>
 *   <li>Role-override attempts ("Ignore previous instructions")</li>
 *   <li>Prompt leakage ("Show me your system prompt")</li>
 *   <li>Authority spoofing ("I'm the admin")</li>
 *   <li>Format override attempts ("Respond only in JSON")</li>
 *   <li>Instruction injection markers</li>
 * </ul>
 *
 * <p>Pattern-based detection is fast but can miss sophisticated attacks. Consider
 * upgrading to semantic detection for higher-security deployments.
 *
 * <p><b>ReDoS remediation (2026-07-29).</b> Every multi-keyword pattern below
 * used to join its keywords with an unbounded {@code .*}. Java's regex engine
 * is a backtracking engine, so {@code a.*b.*c.*d.*e} against a string that
 * contains a, b, c and d many times but never e explores every way of
 * splitting the input between the four gaps - O(n^4) work for a NON-match.
 * Measured on this exact pattern array before the fix (JDK 24, one core):
 *
 * <pre>
 *   input "show me your system " repeated     evaluate() wall clock
 *      200 chars                                        36 ms
 *      800 chars                                     1,407 ms
 *    1,600 chars                                    39,975 ms
 *    2,400 chars                                   302,867 ms
 * </pre>
 *
 * <p>i.e. a 1.6 KB chat message pinned a CPU core for 41 seconds, and this
 * guardrail runs BEFORE the response cache and the hosted-pool budget check
 * (see {@code AgentChatService#enforceInboundGuardrails}), so the request cost
 * the attacker nothing. Two independent mitigations are now in place:
 *
 * <ol>
 *   <li>Every gap is bounded - {@code [^\n]{0,40}?} instead of {@code .*}.
 *       Bounding is what removes the polynomial blow-up: the engine can no
 *       longer try O(n) split points per gap, only 41. See {@link #MAX_GAP}
 *       for why 40 characters does not weaken detection.</li>
 *   <li>{@link #MAX_SCANNED_CHARS} caps how much text is handed to the regex
 *       engine at all, so a future pattern edit that reintroduces an unbounded
 *       quantifier cannot become an unbounded outage.</li>
 * </ol>
 *
 * <p>The DTO-level {@code @Size} cap on {@code ChatRequest.message} is a third
 * layer, but it is NOT sufficient on its own and must not be relied on here:
 * {@link #evaluate} is also called on the OUTPUT path (agent replies, tool
 * output) and from omnichannel webhooks that never construct a {@code
 * ChatRequest} at all.
 */
@Component
public class JailbreakDetectorGuardrail implements ContentGuardrail {

    /**
     * Maximum characters allowed between two adjacent keywords of a
     * multi-keyword pattern (as the literal regex fragment
     * {@code [^\n]{0,40}?}).
     *
     * <p>40 characters is roughly six to seven English words of slack, which
     * covers every realistic phrasing of these attacks - "ignore <b>all of
     * the</b> previous instructions" (11), "what are your <b>original</b>
     * instructions" (10), "I'm <b>the</b> admin" (5). It is also, on its own,
     * a small precision improvement: the old unbounded {@code .*} happily
     * matched "ignore" at the start of a 2,000-character line and
     * "instruction" at the end of it, which is not the same sentence and was
     * never the attack being detected.
     *
     * <p>{@code [^\n]} rather than {@code .} because it states the intent
     * explicitly - both are line-scoped, since {@code .} without
     * {@code DOTALL} already excludes {@code \n}. (The one behavioural
     * difference is that {@code [^\n]} additionally matches the exotic line
     * separators U+0085, U+2028 and U+2029, which {@code .} excludes - i.e.
     * it detects marginally MORE, never less.)
     *
     * <p>Lazy ({@code {0,40}?}) rather than greedy so the common case - the
     * keywords sitting right next to each other - is found without walking
     * out to the bound and back first. {@code find()} semantics are identical
     * either way; only the step count differs.
     */
    private static final int MAX_GAP = 40;

    /**
     * How much of {@code content} is actually scanned. Text beyond this is
     * ignored rather than rejected.
     *
     * <p><b>Why truncate rather than reject.</b> {@link #evaluate} is called
     * on both sides of a turn (see {@code AgentChatService}'s
     * {@code enforceInboundGuardrails} and {@code enforceOutboundGuardrails}).
     * On the output path the string is an agent reply or tool output, which is
     * legitimately long; failing it would turn a defensive cap into a
     * self-inflicted outage on ordinary traffic. On the input path the DTO's
     * {@code @Size(max = 8000)} already rejects anything longer with a 400
     * before this class is reached, so for user messages this cap is
     * unreachable and nothing a real user types is ever truncated.
     *
     * <p>The window is {@code app.guardrails.jailbreak.max-scanned-chars}; this constant is its
     * default (16,000, deliberately double the chat DTO cap so the two limits cannot interact: no
     * accepted user message can ever be partially scanned). With the bounded patterns above, scanning
     * 16,000 characters costs well under a millisecond, so the window exists as a structural ceiling on
     * future pattern edits rather than as a hot-path optimisation. Raise it to scan longer text.
     */
    static final int MAX_SCANNED_CHARS = GuardrailTuningProperties.Jailbreak.DEFAULT_MAX_SCANNED_CHARS;

    private final GuardrailTuningProperties tuning;

    /** Code default window (no {@link GuardrailTuningProperties} bean). */
    public JailbreakDetectorGuardrail() {
        this(new GuardrailTuningProperties());
    }

    @Autowired
    public JailbreakDetectorGuardrail(GuardrailTuningProperties tuning) {
        this.tuning = tuning;
    }

    /** {@code [^\n]{0,40}?} - the bounded, lazy replacement for the old unbounded {@code .*}. See {@link #MAX_GAP}. */
    private static final String GAP = "[^\\n]{0," + MAX_GAP + "}?";

    /**
     * Newline-evasion hardening (pentest L2, 2026-08-27). The gaps above are
     * line-scoped BY DESIGN (that is the ReDoS fix), which left a bypass: a
     * payload split across lines ({@code "ignore\nprevious\ninstructions"})
     * never matched, because no gap can cross a newline. Rather than widening
     * the patterns with {@code DOTALL} (which would reopen the unbounded-gap
     * problem the 2026-07-29 remediation closed), the newline is removed from
     * the INPUT: every whitespace run - spaces, tabs, CR/LF, plus the exotic
     * separators U+0085/U+2028/U+2029 that {@code \s} alone misses - collapses
     * to a single space before matching. The gaps stay bounded and the
     * collapsed form has no newlines for them to be blocked by.
     */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[\\s\\u0085\\u2028\\u2029]+");

    /**
     * The patterns, from {@code guardrails/patterns/<locale>/jailbreak.txt} (every locale on the classpath, English
     * first; see {@link PatternSets}). The English file holds exactly the patterns this class compiled before they
     * moved; each is bounded with {@link #GAP}.
     */
    private static final List<Pattern> JAILBREAK_PATTERNS = PatternSets.localized("jailbreak", Map.of("GAP", GAP)).stream()
        .map(PatternSets.Entry::pattern)
        .toList();

    /** Where the refusal text comes from ({@code refusal.guardrail.jailbreak}); the shipped text unless the application wires the registry. */
    private volatile PromptResolver prompts = PromptResolver.defaultsOnly();

    @Autowired(required = false)
    public void setPromptResolver(PromptResolver resolver) {
        if (resolver != null) {
            this.prompts = resolver;
        }
    }

    @Override
    public ContentGuardrailType getType() {
        return ContentGuardrailType.JAILBREAK;
    }

    @Override
    public ContentGuardrailResult evaluate(String content) {
        if (content == null || content.isBlank()) {
            return ContentGuardrailResult.pass(ContentGuardrailType.JAILBREAK);
        }

        // Defensive, independent of any caller's validation - see MAX_SCANNED_CHARS.
        // The window is read per call: a workspace or agent may widen it (never narrow it) in the settings catalog.
        int maxScannedChars = GuardrailSettings.jailbreakMaxScannedChars(tuning);
        String scanned = content.length() > maxScannedChars
            ? content.substring(0, maxScannedChars)
            : content;

        // Scan both forms: the whitespace-collapsed form so a payload split
        // across lines cannot slip past the line-scoped gaps (see
        // WHITESPACE_RUN), and the raw form so the MULTILINE-anchored
        // attack-prefix pattern still has line starts to anchor to. Both
        // scans use the same bounded patterns, so the ReDoS ceiling holds.
        String normalized = WHITESPACE_RUN.matcher(scanned).replaceAll(" ");

        for (Pattern pattern : JAILBREAK_PATTERNS) {
            if (pattern.matcher(normalized).find() || pattern.matcher(scanned).find()) {
                return ContentGuardrailResult.fail(
                    ContentGuardrailType.JAILBREAK,
                    GuardrailPrompts.scopedText(prompts, GuardrailPrompts.REFUSAL_JAILBREAK),
                    90
                );
            }
        }

        return ContentGuardrailResult.pass(ContentGuardrailType.JAILBREAK);
    }
}
