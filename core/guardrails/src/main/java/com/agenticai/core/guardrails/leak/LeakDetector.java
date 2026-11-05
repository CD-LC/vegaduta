package com.agenticai.core.guardrails.leak;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Checks model output for the system prompt it was given (OWASP LLM07), on the final text and on a
 * stream of chunks.
 *
 * <p>Two rules, both run on the {@linkplain LeakText compact form} of the output:
 *
 * <ul>
 *   <li>{@value #RULE_CANARY}: the turn's {@link PromptCanary} token appears;
 *   <li>{@value #RULE_OVERLAP}: a verbatim run of at least {@code minChars} letters/digits (default
 *       60, about 10 words) from the system prompt appears. The prompt is indexed once as rolling
 *       hashes of every {@code minChars}-window; prompt text is not kept by the detector beyond
 *       that, only 64-bit hashes.
 * </ul>
 *
 * <p><b>What this catches:</b> verbatim or near-verbatim reproduction, including different spacing,
 * line breaks, markdown, case, zero-width characters, combining marks, full-width forms and the
 * Cyrillic/Greek look-alikes in {@link LeakText}; and a leak split across stream chunks.
 *
 * <p><b>What this does not catch, by design:</b> a paraphrase, summary, translation or description
 * of the prompt ("I was told to always answer in French and never discuss pricing"); encodings
 * (base64, rot13, leetspeak, one letter per line with other text in between); verbatim fragments
 * shorter than {@code minChars} (a reader can reassemble a prompt from 59-character pieces);
 * homoglyphs outside the fold table; and anything the model reveals through tool calls or other
 * channels than the reply text. It is a tripwire for the common extraction attacks, not a
 * confidentiality boundary: never put a secret in a prompt on the strength of this check.
 *
 * <p><b>False positives:</b> a reply that legitimately repeats {@code minChars} or more characters
 * of the prompt (a URL, a policy sentence the user asked to be quoted, memory text recalled to the
 * user) is flagged. Shorter shared phrases ("you are a helpful assistant") are not. That is why the
 * default action is {@code warn}.
 *
 * <p>Instances are immutable and thread-safe; a {@link StreamScan} is single-threaded state.
 */
public final class LeakDetector {

    public static final String RULE_CANARY = "LLM07-CANARY";
    public static final String RULE_OVERLAP = "LLM07-OVERLAP";

    private static final long BASE = 0x9E3779B97F4A7C15L;

    private final int promptLength;
    private final String canaryCompact;
    private final int window;
    private final long[] promptHashes;
    private final long basePowWindow;
    private final PromptLeakAction action;
    private final int holdback;
    private final String redactionMarker;
    private final String blockMessage;

    /**
     * @param systemPrompt the prompt as sent, without the canary block (the canary instruction text
     *     is not prompt content worth flagging)
     * @param canary this turn's canary, or null for overlap detection only
     * @param props thresholds and messages
     * @param action what to do with a leak; {@link PromptLeakAction#WARN} changes no output
     */
    public LeakDetector(
            String systemPrompt,
            PromptCanary canary,
            PromptLeakProperties props,
            PromptLeakAction action) {
        this.promptLength = systemPrompt == null ? 0 : systemPrompt.length();
        this.canaryCompact = canary == null ? null : LeakText.compactOf(canary.token());
        this.window = props.minChars();
        this.action = action;
        this.holdback = props.streamHoldbackChars();
        this.redactionMarker = props.getRedactionMarker();
        this.blockMessage = props.getBlockMessage();
        long pow = 1;
        for (int i = 0; i < window; i++) {
            pow *= BASE;
        }
        this.basePowWindow = pow;
        this.promptHashes =
                hashWindows(LeakText.of(systemPrompt == null ? "" : systemPrompt).compact);
    }

    /**
     * One finding: the rule, the character range in the raw text, and how many letters/digits
     * matched.
     */
    record Match(String rule, int rawStart, int rawEnd, int matchedChars, boolean touchesEnd) {}

    /** What a detection looked like. Carries lengths and rule ids only, never text. */
    public record Verdict(
            Set<String> rules,
            int matchedChars,
            int promptLength,
            int outputLength,
            PromptLeakAction action) {}

    /** The effective action of this detector. */
    public PromptLeakAction action() {
        return action;
    }

    /** True if {@code text} contains the canary or a long verbatim run of the prompt. */
    public boolean leaks(CharSequence text) {
        return !find(text).isEmpty();
    }

    /**
     * Scans a complete reply and applies the action: the text to send in its place (unchanged for
     * WARN).
     */
    public String apply(String text, Consumer<Verdict> onDetect) {
        StreamScan scan = newScan(onDetect);
        String head = scan.feed(text);
        return head + scan.finish();
    }

    public StreamScan newScan(Consumer<Verdict> onDetect) {
        return new StreamScan(onDetect);
    }

    // ------------------------------------------------------------------ matching

    List<Match> find(CharSequence raw) {
        if (raw == null || raw.length() == 0) {
            return List.of();
        }
        LeakText text = LeakText.of(raw);
        List<Match> found = new ArrayList<>();
        if (canaryCompact != null && !canaryCompact.isEmpty()) {
            int from = 0;
            int at;
            while ((at = text.compact.indexOf(canaryCompact, from)) >= 0) {
                int last = at + canaryCompact.length() - 1;
                found.add(
                        new Match(
                                RULE_CANARY,
                                text.rawStart(at),
                                text.rawEnd(last),
                                canaryCompact.length(),
                                last == text.length() - 1));
                from = last + 1;
            }
        }
        if (promptHashes.length > 0 && text.length() >= window) {
            overlap(text, found);
        }
        return found;
    }

    private void overlap(LeakText text, List<Match> found) {
        String c = text.compact;
        long h = 0;
        int runStart = -1;
        int runEnd = -1;
        for (int i = 0; i < c.length(); i++) {
            h = h * BASE + c.charAt(i);
            if (i >= window) {
                h -= basePowWindow * c.charAt(i - window);
            }
            if (i >= window - 1 && Arrays.binarySearch(promptHashes, h) >= 0) {
                int s = i - window + 1;
                if (runStart >= 0 && s <= runEnd) {
                    runEnd = i;
                } else {
                    if (runStart >= 0) {
                        found.add(overlapMatch(text, runStart, runEnd));
                    }
                    runStart = s;
                    runEnd = i;
                }
            }
        }
        if (runStart >= 0) {
            found.add(overlapMatch(text, runStart, runEnd));
        }
    }

    private static Match overlapMatch(LeakText text, int from, int to) {
        return new Match(
                RULE_OVERLAP,
                text.rawStart(from),
                text.rawEnd(to),
                to - from + 1,
                to == text.length() - 1);
    }

    private long[] hashWindows(String c) {
        if (c.length() < window) {
            return new long[0];
        }
        long[] out = new long[c.length() - window + 1];
        long h = 0;
        for (int i = 0; i < c.length(); i++) {
            h = h * BASE + c.charAt(i);
            if (i >= window) {
                h -= basePowWindow * c.charAt(i - window);
            }
            if (i >= window - 1) {
                out[i - window + 1] = h;
            }
        }
        Arrays.sort(out);
        return out;
    }

    // ------------------------------------------------------------------ streaming

    /**
     * Feed chunks in, get the text that is safe to send out. Keeps the last {@code holdback}
     * characters back (and any still-growing overlap match in full), so a canary or a long prompt
     * fragment split across chunks is seen before its first character leaves. Cost: a stream's
     * first text reaches the client about {@code holdback} characters late, and a stream that ends
     * flushes the rest at once.
     *
     * <p>Detection runs over the held text plus the last {@code holdback} characters already sent,
     * so a match that began just before the cut is still reported (it cannot be recalled).
     */
    public final class StreamScan {
        private final Consumer<Verdict> onDetect;
        private final StringBuilder pending = new StringBuilder();
        private String sent = "";
        private boolean reported;
        private boolean blocked;
        private int seen;

        private StreamScan(Consumer<Verdict> onDetect) {
            this.onDetect = onDetect;
        }

        /** True once BLOCK fired: the caller should stop reading the model. */
        public boolean blocked() {
            return blocked;
        }

        /** The text to send now for this chunk; may be empty while text is held back. */
        public String feed(String chunk) {
            if (blocked || chunk == null || chunk.isEmpty()) {
                return "";
            }
            seen += chunk.length();
            pending.append(chunk);
            List<Match> matches = find(sent + pending);
            int holdFrom = Integer.MAX_VALUE;
            if (!matches.isEmpty()) {
                report(matches);
                if (action == PromptLeakAction.BLOCK) {
                    blocked = true;
                    pending.setLength(0);
                    return blockMessage;
                }
                if (action == PromptLeakAction.REDACT) {
                    List<Match> closed = new ArrayList<>();
                    boolean open = false;
                    for (Match m : matches) {
                        if (m.touchesEnd()) {
                            open = true;
                        } else {
                            closed.add(m);
                        }
                    }
                    redact(closed);
                    if (open) {
                        // Redaction moved the text: find where the still-growing match starts now.
                        for (Match m : find(sent + pending)) {
                            if (m.touchesEnd()) {
                                holdFrom = Math.min(holdFrom, m.rawStart() - sent.length());
                            }
                        }
                    }
                }
            }
            return release(holdFrom);
        }

        /** The held-back rest of the reply; call once when the model stream ends (or fails). */
        public String finish() {
            if (blocked) {
                return "";
            }
            if (action == PromptLeakAction.REDACT && pending.length() > 0) {
                redact(find(sent + pending));
            }
            String rest = pending.toString();
            pending.setLength(0);
            return rest;
        }

        private String release(int holdFrom) {
            int cut = pending.length() - holdback;
            if (holdFrom != Integer.MAX_VALUE) {
                cut = Math.min(cut, Math.max(holdFrom, 0));
            }
            if (cut <= 0) {
                return "";
            }
            if (Character.isHighSurrogate(pending.charAt(cut - 1))) {
                cut--;
                if (cut <= 0) {
                    return "";
                }
            }
            String out = pending.substring(0, cut);
            pending.delete(0, cut);
            String all = sent + out;
            sent = all.length() > holdback ? all.substring(all.length() - holdback) : all;
            return out;
        }

        private void redact(List<Match> matches) {
            int offset = sent.length();
            // Merge overlapping findings (a canary inside an overlap run) into disjoint ranges.
            List<int[]> ranges = new ArrayList<>();
            matches.stream()
                    .sorted(java.util.Comparator.comparingInt(Match::rawStart))
                    .forEach(
                            m -> {
                                int[] last =
                                        ranges.isEmpty() ? null : ranges.get(ranges.size() - 1);
                                if (last != null && m.rawStart() <= last[1]) {
                                    last[1] = Math.max(last[1], m.rawEnd());
                                } else {
                                    ranges.add(new int[] {m.rawStart(), m.rawEnd()});
                                }
                            });
            for (int i = ranges.size() - 1; i >= 0; i--) {
                int s = Math.max(ranges.get(i)[0] - offset, 0);
                int e = ranges.get(i)[1] - offset;
                if (e <= 0 || s >= pending.length()) {
                    continue;
                }
                pending.replace(s, Math.min(e, pending.length()), redactionMarker);
            }
        }

        private void report(List<Match> matches) {
            if (reported) {
                return;
            }
            reported = true;
            if (onDetect == null) {
                return;
            }
            Set<String> rules = new LinkedHashSet<>();
            int matched = 0;
            for (Match m : matches) {
                rules.add(m.rule());
                matched += m.matchedChars();
            }
            onDetect.accept(new Verdict(rules, matched, promptLength, seen, action));
        }
    }
}
