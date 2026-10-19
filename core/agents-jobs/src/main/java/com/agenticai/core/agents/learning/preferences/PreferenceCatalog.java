package com.agenticai.core.agents.learning.preferences;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixed vocabulary of style requests the loop recognizes, and the one sentence each becomes when the same request
 * keeps coming back. Detection is plain pattern matching on a short message (no model call); the phrasing is a fixed
 * template, so nothing the person wrote is ever copied into a stored preference.
 *
 * <p>A request that is negated ("don't make it shorter") or is not a short instruction (a pasted document that happens
 * to contain "shorter") does not count.
 */
public final class PreferenceCatalog {

    /** A recognized style request: a stable key (what is counted) and the preference sentence it becomes. */
    public record Signal(String key, String phrase) {}

    private record Rule(String key, String phrase, Pattern pattern, boolean negatable) {}

    private static final int MAX_CHARS = 300;

    private static final Pattern NEGATION =
            Pattern.compile("\\b(don'?t|do not|never|not|no need to|without)\\b[^.!?]{0,20}$");

    private static final Set<String> LANGUAGES =
            Set.of(
                    "hindi", "kannada", "tamil", "telugu", "malayalam", "marathi", "bengali", "gujarati",
                    "punjabi", "urdu", "english", "spanish", "french", "german", "portuguese", "italian",
                    "japanese", "korean", "chinese", "arabic", "russian", "dutch", "turkish", "hinglish");

    private static final Pattern LANGUAGE =
            Pattern.compile(
                    "\\b(?:reply|respond|answer|write|speak|talk|always|translate)\\b(?:\\s+to me|\\s+back)?(?:\\s+only)?"
                            + "\\s+(?:in|into)\\s+([a-z]+)");

    private static final Rule[] RULES = {
        rule(
                "short-replies",
                "Prefers short, concise replies",
                "\\b(shorter|briefer|more concise|be concise|keep it (short|brief)|too long|too wordy|too verbose|"
                        + "less wordy|short(er)? (answer|reply|response)s?|one[- ]liners?|in (one|1) (line|sentence)|tl;? ?dr)\\b",
                true),
        rule(
                "detailed-replies",
                "Prefers detailed, thorough replies when the question needs it",
                "\\b(more detail(s|ed)?|in (more )?detail|elaborate|explain (it )?more|go deeper|more thorough|"
                        + "too (short|brief|shallow))\\b",
                true),
        rule(
                "no-emojis",
                "Does not want emojis in replies",
                "\\b(no|without|stop using|stop with the|don'?t use|do not use|avoid|remove|lose)( the| any)? emojis?\\b",
                false),
        rule(
                "bullet-points",
                "Prefers answers as bullet points",
                "\\b(in|as|with|use|using) bullet(ed)?( points| list|s)?\\b|\\bbullet(ed)? (list|points)\\b",
                true),
        rule(
                "answer-first",
                "Wants the answer first, without preamble or filler",
                "\\b(get to the point|straight to the point|just the answer|answer first|skip the (intro|preamble|fluff|"
                        + "pleasantries|filler)|no (preamble|fluff|filler|pleasantries))\\b",
                true),
        rule(
                "formal-tone",
                "Prefers a formal, professional tone",
                "\\b(more formal|formal tone|professional tone|be (more )?professional|less casual)\\b",
                true),
        rule(
                "casual-tone",
                "Prefers a casual, friendly tone",
                "\\b(more casual|casual tone|less formal|friendlier|more friendly|more conversational)\\b",
                true),
    };

    private PreferenceCatalog() {}

    private static Rule rule(String key, String phrase, String regex, boolean negatable) {
        return new Rule(key, phrase, Pattern.compile(regex), negatable);
    }

    /** The signals in a short message, in a stable order; empty for anything that is not a style instruction. */
    public static Map<String, Signal> detect(String message) {
        Map<String, Signal> found = new LinkedHashMap<>();
        if (message == null) {
            return found;
        }
        String text = message.strip().replace('’', '\'').toLowerCase(Locale.ROOT);
        if (text.isEmpty() || text.length() > MAX_CHARS) {
            return found;
        }
        for (Rule r : RULES) {
            Matcher m = r.pattern().matcher(text);
            while (m.find()) {
                if (r.negatable() && negated(text, m.start())) {
                    continue;
                }
                found.putIfAbsent(r.key(), new Signal(r.key(), r.phrase()));
                break;
            }
        }
        Matcher lang = LANGUAGE.matcher(text);
        while (lang.find()) {
            String language = lang.group(1);
            if (LANGUAGES.contains(language) && !negated(text, lang.start())) {
                String name = Character.toUpperCase(language.charAt(0)) + language.substring(1);
                found.putIfAbsent(
                        "language:" + language, new Signal("language:" + language, "Prefers replies in " + name));
                break;
            }
        }
        return found;
    }

    private static boolean negated(String text, int matchStart) {
        String before = text.substring(Math.max(0, matchStart - 30), matchStart);
        return NEGATION.matcher(before).find();
    }
}
