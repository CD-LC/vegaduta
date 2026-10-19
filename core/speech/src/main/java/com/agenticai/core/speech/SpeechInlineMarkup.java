package com.agenticai.core.speech;

import static com.agenticai.core.speech.SpeechCharacters.collapseSpaces;
import static com.agenticai.core.speech.SpeechCharacters.decodeEntities;
import static com.agenticai.core.speech.SpeechCharacters.stripSymbolsAndFoldPunctuation;
import static com.agenticai.core.speech.SpeechTextNormalizer.EMAIL_PHRASE;
import static com.agenticai.core.speech.SpeechTextNormalizer.LINK_PHRASE;

import java.util.regex.Pattern;

/**
 * The inline (within one line) rules of {@link SpeechTextNormalizer}: HTML tags and entities,
 * links and images, inline code, bare URLs and emails, emphasis markers, snake_case and the
 * residual-markup sweep. Run once per already-structured line by the normalizer.
 */
final class SpeechInlineMarkup {

    // Every quantifier in this class is bounded - see SpeechTextNormalizer.MAX_INPUT_CHARS for the
    // measured reason. These all run on a SINGLE stripped line (normalizeInline
    // is called per line), so "\n" inside the negated classes below is belt and
    // braces rather than a behaviour change.
    private static final Pattern AUTOLINK =
        Pattern.compile("<((?:https?|mailto):[^>\\s]{1,500})>", Pattern.CASE_INSENSITIVE);
    /** 300 characters of attributes covers any tag an LLM emits; a longer one keeps its angle brackets and is swept by {@link #RESIDUAL_MARKUP}/{@link SpeechCharacters#stripSymbolsAndFoldPunctuation} instead. */
    private static final Pattern HTML_TAG = Pattern.compile("</?[A-Za-z][^>\\n]{0,300}>");
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[([^\\]\\n]{0,200})\\]\\([^)\\n]{0,500}\\)");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]\\n]{0,200})\\]\\([^)\\n]{0,500}\\)");
    private static final Pattern MD_REFERENCE_LINK = Pattern.compile("\\[([^\\]\\n]{1,200})\\]\\[[^\\]\\n]{0,200}\\]");
    private static final Pattern INLINE_CODE_DOUBLE = Pattern.compile("``([^`]{1,2000})``");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\\n]{0,2000})`");
    private static final Pattern BARE_URL =
        Pattern.compile("(?:https?://|www\\.)[^\\s<>\\[\\]()]{1,2000}", Pattern.CASE_INSENSITIVE);
    /**
     * The domain is matched as explicit dot-terminated labels rather than as
     * the old {@code [\p{L}\p{N}.-]+\.[A-Za-z]{2,}}: because that class
     * contained {@code .} itself, the engine had O(n) ways to choose where the
     * final dot was, and "x@" followed by "a." repeated cost 44 seconds at
     * 32 KB. Labels anchored on a literal {@code .} are unambiguous, so this
     * form is linear. The local part is capped at RFC 5321's 64 characters and
     * the TLD at 24 (the longest IANA-delegated TLD is 24). Consecutive or
     * leading dots ({@code a@..com}) no longer match - a tightening, and one
     * that only ever removes false positives.
     */
    private static final Pattern BARE_EMAIL = Pattern.compile(
        "(?:mailto:)?[\\p{L}\\p{N}._%+-]{1,64}@(?:[\\p{L}\\p{N}-]{1,63}\\.){1,8}[A-Za-z]{2,24}",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern BOLD_STARS = Pattern.compile("\\*\\*([^*]{1,2000})\\*\\*");
    private static final Pattern BOLD_UNDERSCORES = Pattern.compile("__([^_]{1,2000})__");
    private static final Pattern STRIKETHROUGH = Pattern.compile("~~([^~]{1,2000})~~");
    private static final Pattern ITALIC_STAR = Pattern.compile("\\*([^*\\n]{1,2000})\\*");
    /**
     * Only an underscore that is NOT flanked by a letter/digit delimits italics -
     * {@code \p{L}}/{@code \p{N}} (never {@code \w}) so this is Unicode-correct
     * for Indic text too, and so {@code snake_case_identifiers} are left for
     * {@link #WORD_UNDERSCORE} to turn into spaces instead.
     */
    private static final Pattern ITALIC_UNDERSCORE =
        Pattern.compile("(?<![\\p{L}\\p{N}_])_([^_\\n]{1,2000})_(?![\\p{L}\\p{N}_])");
    /** snake_case -> "snake case"; an underscore between word characters is a separator, not a word. */
    private static final Pattern WORD_UNDERSCORE = Pattern.compile("(?<=[\\p{L}\\p{N}])_(?=[\\p{L}\\p{N}])");
    /** Leftover markdown punctuation that has no spoken meaning once the structure above is gone. */
    private static final Pattern RESIDUAL_MARKUP = Pattern.compile("[*`~|\\\\]+");

    private SpeechInlineMarkup() {
    }

    static String normalizeInline(String line) {
        String s = line;

        s = AUTOLINK.matcher(s).replaceAll(" " + LINK_PHRASE + " ");
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = decodeEntities(s);
        // Second pass: "&lt;b&gt;" has only just become "<b>" and would otherwise
        // be read out as "less than b greater than".
        s = HTML_TAG.matcher(s).replaceAll(" ");

        // "](" / "][" are necessary conditions for these three, so the guards
        // change nothing semantically - same idiom as SpeechTextNormalizer#replaceFencedCodeBlocks
        // and decodeEntities. They matter because a line of nothing but "["
        // otherwise gives every offset a failing start to backtrack out of.
        if (s.indexOf("](") >= 0) {
            s = MD_IMAGE.matcher(s).replaceAll("$1");
            s = MD_LINK.matcher(s).replaceAll("$1");
        }
        if (s.indexOf("][") >= 0) {
            s = MD_REFERENCE_LINK.matcher(s).replaceAll("$1");
        }

        s = INLINE_CODE_DOUBLE.matcher(s).replaceAll("$1");
        s = INLINE_CODE.matcher(s).replaceAll("$1");

        // Before emphasis stripping: a URL can legitimately contain * and _,
        // and mangling it into half a word is worse than replacing the whole
        // thing with the phrase.
        s = BARE_URL.matcher(s).replaceAll(LINK_PHRASE);
        s = BARE_EMAIL.matcher(s).replaceAll(EMAIL_PHRASE);

        s = BOLD_STARS.matcher(s).replaceAll("$1");
        s = BOLD_UNDERSCORES.matcher(s).replaceAll("$1");
        s = STRIKETHROUGH.matcher(s).replaceAll("$1");
        s = ITALIC_STAR.matcher(s).replaceAll("$1");
        s = ITALIC_UNDERSCORE.matcher(s).replaceAll("$1");
        s = WORD_UNDERSCORE.matcher(s).replaceAll(" ");

        s = stripSymbolsAndFoldPunctuation(s);
        s = RESIDUAL_MARKUP.matcher(s).replaceAll(" ");
        s = collapseSpaces(s).strip();
        return s;
    }
}
