package com.agenticai.core.speech;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw agent/LLM text into something a text-to-speech engine should
 * actually SAY out loud.
 *
 * <p>Live bug this exists to fix (reported 2026-07-29): "voice is working,
 * but it's reading all special characters in the text message". An agent
 * reply is LLM output - it routinely contains markdown ({@code **bold**},
 * {@code ## headings}, {@code - bullets}, {@code `code`},
 * {@code [text](url)}, tables), HTML, emoji and bare URLs. Handed verbatim
 * to ElevenLabs/Sarvam, those are pronounced literally ("asterisk asterisk
 * bold asterisk asterisk", "hash hash heading", a URL spelled out character
 * by character). The chat transcript still wants the raw markdown - only
 * the string that goes to the synthesizer is normalized, never the text the
 * user reads on screen.
 *
 * <p><b>Transformation rules</b> (each one has a dedicated test in {@code
 * SpeechTextNormalizerTest}):
 * <ul>
 *   <li><b>Fenced code blocks</b> ({@code ```} / {@code ~~~}) - dropped and
 *       replaced by the spoken phrase "code block." Reading a shell script
 *       aloud is never useful; saying that one WAS returned is.</li>
 *   <li><b>Inline code</b> ({@code `agentId`}) - backticks removed, the
 *       content KEPT. Inline code is almost always a single identifier or
 *       value that reads perfectly well; dropping it would gut the
 *       sentence.</li>
 *   <li><b>Emphasis</b> {@code **b**}, {@code __b__}, {@code *i*},
 *       {@code _i_}, {@code ~~s~~} - markers removed, text kept.</li>
 *   <li><b>Headings</b> ({@code ## Title}) - markers removed, and the line
 *       is terminated with a full stop so the voice pauses instead of
 *       running the heading into the next sentence.</li>
 *   <li><b>Blockquotes</b> ({@code &gt; quoted}) - marker removed.</li>
 *   <li><b>List bullets</b> ({@code -}/{@code *}/{@code +}/{@code &#8226;}) -
 *       removed; ordered markers ({@code 1.}) are KEPT, because "one, do this"
 *       is how a person reads a numbered list aloud. Every list item is
 *       terminated with a full stop so items don't slur together.</li>
 *   <li><b>Horizontal rules</b> ({@code ---}, {@code ***}, {@code ___}) -
 *       dropped entirely.</li>
 *   <li><b>Tables</b> - the {@code |---|:--:|} separator row is dropped, and
 *       every other row becomes its cells joined by ", " (so a row reads as
 *       a natural comma-separated phrase rather than "pipe pipe pipe").</li>
 *   <li><b>Links</b> {@code [text](url)} - the text is kept, the URL
 *       dropped. Images {@code ![alt](url)} keep the alt text.</li>
 *   <li><b>HTML</b> - {@code <script>}/{@code <style>} blocks and comments
 *       removed with their content, all remaining tags stripped, then
 *       entities decoded ({@code &amp;amp;}, {@code &amp;lt;},
 *       {@code &amp;nbsp;}, numeric {@code &amp;#8217;}...). Tags are
 *       stripped a second time after decoding so an encoded tag can't
 *       re-appear as a literal "less than b greater than".</li>
 *   <li><b>Bare URLs / emails</b> - replaced with the phrases "a link" and
 *       "an email address". Keeping even just the domain means the engine
 *       spells out "v e g a d u t a dot x y z"; there is no pronunciation of
 *       a URL that is pleasant to listen to, and the user can read the real
 *       one in the transcript next to the audio.</li>
 *   <li><b>Emoji and decorative symbols</b> - DROPPED (see {@link
 *       SpeechCharacters#isSpeechNoiseSymbol}). Engines either verbalize them ("smiling face
 *       with sunglasses") or emit an audible artifact, and LLM replies are
 *       emoji-heavy. This is the one rule where the choice is a judgment
 *       call rather than an obvious win, so it is confined to one method and
 *       to explicitly enumerated code-point ranges.</li>
 *   <li><b>Control / ANSI / invisible characters</b> - stripped by {@link
 *       #stripControlAnsiAndInvisibles}, a port of {@code
 *       McpToolOutputSanitizer}'s handling in {@code core/agents} (that class
 *       is package-private and lives in a module neither TTS client can see,
 *       so the character classes are re-stated here rather than the class
 *       being reused; the two are deliberately identical and the invisible
 *       ranges are commented with the same reasoning).</li>
 *   <li><b>Residual sweep</b> - any {@code *}, {@code `}, {@code ~},
 *       {@code |} or {@code \} still standing after the rules above is
 *       deleted. This is the safety net that actually closes the reported
 *       bug: an unpaired marker (an emphasis run the model never closed, a
 *       stray fence) is otherwise pronounced. The known cost is that a
 *       multiplication written as {@code 2 * 3} becomes "2 3" - accepted
 *       deliberately, because unmatched markdown in LLM output is far more
 *       common than {@code *}-as-times and "2 asterisk 3" is the worse of
 *       the two failures. {@code #} is NOT swept (so "C#" survives), and
 *       {@code -}/{@code &gt;} are left alone so ranges and comparisons
 *       still read correctly.</li>
 *   <li><b>Whitespace</b> - runs collapsed to a single space, structural
 *       lines terminated with a full stop, blank lines turned into a
 *       sentence break, so the engine gets natural pauses instead of one
 *       breathless run-on.</li>
 * </ul>
 *
 * <p><b>Unicode / Indic safety.</b> This platform is India-first and Sarvam
 * synthesizes Devanagari, Tamil, Telugu, Kannada, Malayalam, Bengali,
 * Gujarati, Gurmukhi and Odia. Every rule here is written to be inert on
 * non-Latin scripts:
 * <ul>
 *   <li>No {@code \w}, {@code \b} or {@code [a-zA-Z]}-as-"a letter" anywhere
 *       that decides whether text survives - word characters are matched as
 *       {@code [\p{L}\p{N}]}, which is Unicode-aware by definition.</li>
 *   <li>Symbol stripping is code-point-based over an explicit allow-list of
 *       ranges, all of which sit at U+2190 and above or in the U+1F000
 *       emoji planes. Every Indic block lives in U+0900-U+0D7F, i.e. far
 *       below the lowest stripped range, so no Indic code point can be
 *       removed. The Devanagari danda (U+0964) and double danda (U+0965) are
 *       additionally recognised as sentence-ending punctuation so a
 *       Hindi/Marathi line is not given a redundant Latin full stop.</li>
 *   <li>The bare-URL and email rules are anchored on {@code http://},
 *       {@code https://}, {@code www.} and {@code @}, so they cannot match
 *       Indic prose.</li>
 * </ul>
 *
 * <p><b>Never empty.</b> An empty TTS request fails the call outright, so
 * {@link #normalizeForSpeech} falls back to a whitespace-collapsed copy of
 * the input (and then to the input itself) rather than ever returning an
 * empty string for non-empty input. {@code null} in, {@code null} out.
 * A caller streaming a reply in pieces wants the opposite (a piece that was
 * pure markup should be sent as nothing at all, not spoken as itself) and
 * uses {@link #normalizeFragmentForSpeech} - see {@link SpeechStreamNormalizer},
 * which is what buffers LLM tokens into those pieces.
 *
 * <p>Pure static functions, no Spring, no other core module - see this
 * module's pom.xml for why it is its own leaf module.
 */
public final class SpeechTextNormalizer {

    /** Spoken in place of a fenced code block - see the class javadoc's rules list. */
    public static final String CODE_BLOCK_PHRASE = "code block.";

    /** Spoken in place of a bare URL. */
    public static final String LINK_PHRASE = "a link";

    /** Spoken in place of a bare email address. */
    public static final String EMAIL_PHRASE = "an email address";

    private static final char ESC = (char) 0x1B;
    private static final char BEL = (char) 0x07;
    private static final char DEL = (char) 0x7F;

    /**
     * Hard ceiling on how much text is normalized. Anything past this is
     * dropped BEFORE a single regex runs.
     *
     * <p><b>Why this exists (ReDoS remediation, 2026-07-29).</b> Java's regex
     * engine backtracks, and several rules below are "open delimiter, run of
     * anything, close delimiter". When the close delimiter never arrives,
     * {@code find()} retries at every one of the n possible start offsets and
     * each retry rescans - quadratic. Measured end to end on this class before
     * the fix (JDK 24, one core, {@link #normalizeForSpeech} wall clock):
     *
     * <pre>
     *   input                          8 KB       32 KB      128 KB
     *   "&lt;script&gt;" repeated       12,114 ms   163,925 ms       -
     *   "&lt;!--" repeated               5,027 ms    84,696 ms       -
     *   "x@" + "a." repeated           2,745 ms    43,605 ms       -
     *   "[a](" repeated                  271 ms     5,241 ms   79,566 ms
     * </pre>
     *
     * <p>Every quantifier below is now bounded, which is the actual fix; this
     * cap is the second, independent layer, so a future rule added without a
     * bound cannot turn one long agent reply into a pinned core.
     *
     * <p><b>Why 8,000 specifically, and why truncate rather than throw.</b>
     * Every consumer of this method is a speech synthesizer with its own,
     * much smaller, per-request character limit: ElevenLabs 5,000, Sarvam
     * bulbul ~1,500, Twilio {@code <Say>} 4,096. 8,000 sits above all of them,
     * so the cap can only ever discard text the engine was going to reject
     * anyway. Throwing is not an option: the callers ({@code
     * ElevenLabsClient#textToSpeech}, {@code
     * SarvamTextToSpeechClient#synthesize}, {@code
     * TwilioVoiceWebhookController}, {@code VoiceCallService}, {@code
     * VoiceCallController}) all hand over an agent reply mid-turn, and a
     * long-but-legitimate reply must still be spoken, not turned into a 500.
     * The one path with no engine-side limit of its own is {@code
     * VoiceCallController}'s {@code speechText} for the browser Web Speech API
     * fallback; 8,000 characters is roughly ten minutes of speech, so
     * truncation there is theoretical rather than practical.
     */
    public static final int MAX_INPUT_CHARS = 8_000;

    // --- block-level pre-pass ------------------------------------------------
    /**
     * Bounded (see {@link #MAX_INPUT_CHARS}): an unterminated {@code <!--} used
     * to make the lazy run rescan to end-of-input from every {@code <!--} in
     * the text. An HTML comment in an LLM reply that is longer than 500
     * characters is not something worth excising precisely - its {@code <!--}
     * is stripped by {@link SpeechInlineMarkup}'s tag and residual-markup rules anyway.
     */
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.{0,500}?-->", Pattern.DOTALL);
    /**
     * Bounded (see {@link #MAX_INPUT_CHARS}). {@code [^>]{0,200}} is the
     * attribute list - the old {@code [^>]*} scanned to end-of-input and back
     * for every {@code <script} that had no {@code >} after it. The character
     * class deliberately still admits {@code \n}, because an opening tag can
     * legitimately be written across lines and this rule (unlike the inline
     * ones) runs over the whole text; only the bound changed. {@code
     * .{0,2000}?} is the block body; a longer one loses its closing-tag match
     * and is then stripped tag-by-tag by {@link SpeechInlineMarkup} instead, which
     * leaves the body text audible - accepted, because a 2,000-character
     * script block inside an agent reply is not a case worth making the whole
     * endpoint quadratic for.
     */
    private static final Pattern SCRIPT_OR_STYLE =
        Pattern.compile("<\\s{0,8}(script|style)\\b[^>]{0,200}>.{0,2000}?<\\s{0,8}/\\s{0,8}\\1\\s{0,8}>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /**
     * ``` or ~~~ fence, optional info string, everything up to the closing
     * fence (or end of text for an unterminated fence). The body bound is
     * {@link #MAX_INPUT_CHARS} itself, so a capped input can still be one
     * single code block - i.e. the bound is documentation of intent here, not
     * a behaviour change.
     */
    private static final Pattern FENCED_CODE =
        Pattern.compile("(?m)^[ \\t]*(`{3,}|~{3,})[^\\n]{0,200}\\n(.{0," + MAX_INPUT_CHARS + "}?)"
            + "(?:^[ \\t]*\\1[ \\t]*$|\\z)", Pattern.DOTALL);

    // --- line-level ----------------------------------------------------------
    private static final Pattern HEADING = Pattern.compile("^#{1,6}[ \\t]+");
    /** Also matches a bare "#####" heading line with no text after it. */
    private static final Pattern HEADING_EMPTY = Pattern.compile("^#{1,6}[ \\t]*$");
    private static final Pattern TRAILING_HASHES = Pattern.compile("[ \\t]+#+[ \\t]*$");
    private static final Pattern BLOCKQUOTE = Pattern.compile("^>[ \\t]?");
    /** The {@code ---}/{@code ***}/{@code ___} rule characters recognised by {@link #isHorizontalRule}. */
    private static final String RULE_MARKERS = "-*_";
    private static final Pattern UNORDERED_BULLET = Pattern.compile("^[ \\t]*[-*+\u2022\u25CF\u25AA][ \\t]+");
    private static final Pattern ORDERED_BULLET = Pattern.compile("^[ \\t]*(\\d{1,3})[.)][ \\t]+");
    private static final Pattern TASK_BOX = Pattern.compile("^\\[[ xX]\\][ \\t]+");
    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile("^:?-{1,}:?$");

    /**
     * Repetition bounded at 64 because {@code (?:group)+} is the one regex
     * shape Java implements with one stack frame PER ITERATION (a
     * {@code GroupCurly} node, unlike a plain {@code [class]+}). Measured on
     * the previous unbounded form: {@code ", "} repeated to 8,000 characters
     * threw {@link StackOverflowError} out of {@link #normalizeForSpeech} - an
     * {@code Error}, so every caller's {@code catch (Exception e)} misses it
     * and the voice endpoint 500s. 8,000 characters is an ordinary long agent
     * reply, so this was reachable without an attacker.
     *
     * <p>A run longer than 64 now collapses in chunks rather than in one
     * match ({@code replaceAll} simply continues scanning), which is a
     * graceful degradation on input no human writes - real text has two or
     * three repeats.
     */
    private static final Pattern REPEATED_PUNCTUATION = Pattern.compile("([,;:])(?:[ \\t]{0,16}[,;:]){1,64}");

    /**
     * Sentence terminators, Latin plus the Devanagari danda/double danda - a
     * Hindi or Marathi line already ending in "।" must not have a Latin full
     * stop bolted onto it.
     */
    private static final String SENTENCE_ENDERS = ".!?:;\u0964\u0965\u06D4\u3002";

    private SpeechTextNormalizer() {
    }

    /**
     * @param raw the text as the model produced it (markdown, HTML, emoji and all)
     * @return the same content rewritten for a speech synthesizer. {@code null}
     *     for {@code null} input; never {@code null} or empty for non-empty
     *     input (an empty TTS request fails the provider call outright, so a
     *     normalization that would empty the string falls back to the input).
     *     Input longer than {@link #MAX_INPUT_CHARS} is truncated to that
     *     length first - see that constant for why, and why truncating beats
     *     throwing here.
     */
    public static String normalizeForSpeech(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank()) {
            return raw;
        }

        // Defensive cap BEFORE any regex touches the string, independent of
        // whatever validation the caller did or did not do - see MAX_INPUT_CHARS.
        String source = raw.length() > MAX_INPUT_CHARS ? raw.substring(0, MAX_INPUT_CHARS) : raw;

        String result = normalizeCore(source);
        if (!result.isEmpty()) {
            return result;
        }

        // Normalization emptied the string (input was pure markup/emoji). An
        // empty request fails the provider call, so fall back rather than
        // return "" - a slightly ugly reading beats a failed call. Built from
        // the capped copy; `raw` stays the last resort so the "never empty for
        // non-empty input" contract still holds even for the pathological case
        // where the first MAX_INPUT_CHARS characters are all whitespace.
        String fallback = SpeechCharacters.collapseSpaces(
            stripControlAnsiAndInvisibles(source).replace('\n', ' ').replace('\r', ' ')
        ).strip();
        return fallback.isEmpty() ? raw : fallback;
    }

    /**
     * The same rules as {@link #normalizeForSpeech}, but WITHOUT its
     * never-empty fallback: a fragment that normalizes away to nothing comes
     * back as {@code ""}.
     *
     * <p>The fallback exists because handing an empty string to ElevenLabs or
     * Sarvam fails the whole synthesis call, so a whole-message normalizer must
     * return something. A caller that is streaming a reply in pieces has the
     * opposite need - it can simply not send that piece and go on to the next
     * one - and for it the fallback is actively harmful: a fragment that is
     * exactly {@code "**"} (which is what a token boundary through an emphasis
     * marker produces) would come back as {@code "**"} and be pronounced,
     * which is the very bug this class exists to fix.
     *
     * <p>Fragments are normalized independently, so a fragment is treated as
     * whole lines: {@link SpeechStreamNormalizer} is responsible for only ever
     * cutting the stream where that is true enough (sentence ends, newlines,
     * word boundaries - never inside an open code fence).
     *
     * @param fragment a piece of a longer reply
     * @return the fragment rewritten for a speech synthesizer, or {@code ""}
     *     if there was nothing left to say. {@code null} for {@code null}.
     */
    public static String normalizeFragmentForSpeech(String fragment) {
        if (fragment == null) {
            return null;
        }
        if (fragment.isBlank()) {
            return "";
        }
        // Same defensive cap as normalizeForSpeech - fragments arrive from a
        // streaming buffer that cuts far below this, but the cap must not
        // depend on the caller being that buffer.
        String source = fragment.length() > MAX_INPUT_CHARS ? fragment.substring(0, MAX_INPUT_CHARS) : fragment;
        return normalizeCore(source);
    }

    /** Every rule in this class's javadoc, applied once. May legitimately return {@code ""}. */
    private static String normalizeCore(String raw) {
        String working = stripControlAnsiAndInvisibles(raw);
        working = HTML_COMMENT.matcher(working).replaceAll(" ");
        working = SCRIPT_OR_STYLE.matcher(working).replaceAll(" ");
        working = replaceFencedCodeBlocks(working);
        working = working.replace("\r\n", "\n").replace('\r', '\n')
            .replace('\u2028', '\n').replace('\u2029', '\n');

        List<String> sentences = new ArrayList<>();
        boolean paragraphBreakPending = false;
        for (String rawLine : working.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                paragraphBreakPending = !sentences.isEmpty();
                continue;
            }
            boolean structural = false;

            // Blockquote markers can nest ("> > quoted"), so peel them off in a loop.
            while (true) {
                Matcher quote = BLOCKQUOTE.matcher(line);
                if (!quote.find()) {
                    break;
                }
                line = quote.replaceFirst("").strip();
            }
            if (line.isEmpty()) {
                continue;
            }

            // After the blockquote peel, so "> ---" is recognised as the rule
            // it is rather than surviving to be read out as "dash dash dash".
            if (isHorizontalRule(line)) {
                paragraphBreakPending = !sentences.isEmpty();
                continue;
            }

            if (HEADING_EMPTY.matcher(line).matches()) {
                continue;
            }
            if (HEADING.matcher(line).find()) {
                line = HEADING.matcher(line).replaceFirst("");
                line = TRAILING_HASHES.matcher(line).replaceFirst("");
                structural = true;
            }

            if (isTableRow(line)) {
                String phrase = tableRowToPhrase(line);
                if (phrase == null) {
                    continue; // the |---|:--:| separator row - nothing to say
                }
                line = phrase;
                structural = true;
            }

            Matcher ordered = ORDERED_BULLET.matcher(line);
            if (ordered.find()) {
                // Keep the number - "one, do this" is how a numbered list is read aloud.
                line = ordered.group(1) + ". " + line.substring(ordered.end());
                structural = true;
            } else if (UNORDERED_BULLET.matcher(line).find()) {
                line = UNORDERED_BULLET.matcher(line).replaceFirst("");
                structural = true;
            }
            line = TASK_BOX.matcher(line).replaceFirst("");

            line = SpeechInlineMarkup.normalizeInline(line);
            if (line.isBlank()) {
                continue;
            }

            if (paragraphBreakPending) {
                int last = sentences.size() - 1;
                sentences.set(last, terminate(sentences.get(last)));
                paragraphBreakPending = false;
            }
            sentences.add(structural ? terminate(line) : line);
        }

        String result = SpeechCharacters.collapseSpaces(String.join(" ", sentences)).strip();
        return REPEATED_PUNCTUATION.matcher(result).replaceAll("$1");
    }

    // ------------------------------------------------------------------------
    // block level
    // ------------------------------------------------------------------------

    private static String replaceFencedCodeBlocks(String text) {
        if (text.indexOf('`') < 0 && text.indexOf('~') < 0) {
            return text;
        }
        Matcher m = FENCED_CODE.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement("\n" + CODE_BLOCK_PHRASE + "\n"));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static boolean isTableRow(String line) {
        return line.startsWith("|") && line.indexOf('|', 1) > 0;
    }

    /**
     * {@code ---}, {@code ***}, {@code ___}, {@code - - -} ... - three or more
     * rule characters and nothing else but spaces/tabs. A bullet ("- item")
     * can't match: "item" is neither a marker nor whitespace.
     *
     * <p>Exactly equivalent to the regex this replaces
     * ({@code ^[ \t]*(?:[-*_][ \t]*){3,}$}), written out because that shape is
     * a {@code GroupCurly} - one JVM stack frame per repetition. A line of
     * 8,000 dashes threw {@link StackOverflowError} straight out of
     * {@link #normalizeForSpeech}; being an {@code Error} rather than an
     * exception, it sailed past every caller's {@code catch (Exception e)} and
     * 500'd the request. A plain character scan has no such limit and is
     * linear.
     */
    private static boolean isHorizontalRule(String line) {
        int markers = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (RULE_MARKERS.indexOf(c) >= 0) {
                markers++;
            } else if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return markers >= 3;
    }

    /** @return the row's cells joined by ", ", or {@code null} if this is a {@code |---|:--:|} separator row. */
    private static String tableRowToPhrase(String line) {
        String body = line;
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        boolean allSeparators = true;
        for (String cell : body.split("\\|", -1)) {
            String trimmed = cell.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!TABLE_SEPARATOR_CELL.matcher(trimmed).matches()) {
                allSeparators = false;
            }
            cells.add(trimmed);
        }
        if (cells.isEmpty() || allSeparators) {
            return null;
        }
        return String.join(", ", cells);
    }

    /**
     * Removes ANSI escape sequences, every control character except tab/LF/CR, and the Unicode
     * invisible-formatting characters (bidi overrides and isolates, zero-width characters, BOM, soft
     * hyphen, the Unicode Tag block). See {@link SpeechCharacters#stripControlAnsiAndInvisibles}.
     */
    public static String stripControlAnsiAndInvisibles(String raw) {
        return SpeechCharacters.stripControlAnsiAndInvisibles(raw);
    }

    // ------------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------------

    /** Appends a full stop unless the line already ends in sentence punctuation (danda included). */
    private static String terminate(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        char last = trimmed.charAt(trimmed.length() - 1);
        if (SENTENCE_ENDERS.indexOf(last) >= 0 || last == ',') {
            return trimmed;
        }
        return trimmed + ".";
    }
}
