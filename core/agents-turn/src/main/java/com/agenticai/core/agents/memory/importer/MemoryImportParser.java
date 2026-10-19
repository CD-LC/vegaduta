package com.agenticai.core.agents.memory.importer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text a user pasted from another assistant ("list everything you remember about me") into
 * candidate memory facts. Pure and deterministic: no model call, no I/O, no Spring.
 *
 * <p>The paste is untrusted: it can carry another assistant's chatter, markdown, invisible Unicode
 * (TAG block, zero-width, bidi controls), text that tries to instruct VegaDuta's agents, and secrets the
 * user did not mean to share. Every line is cleaned and screened here; a line that fails is kept as a
 * <em>rejected</em> candidate with a stable reason code (so the UI can say why) and is never saved.
 * {@link #screen} runs the same checks on a single fact so the import endpoint can re-verify what the
 * client sends instead of trusting the preview.
 *
 * <p>Reason codes: {@value #R_TOO_SHORT}, {@value #R_NO_CONTENT}, {@value #R_TOO_LONG},
 * {@value #R_INJECTION}, {@value #R_INSTRUCTION}, {@value #R_UNSAFE}, {@value #R_CREDENTIALS},
 * {@value #R_SECRET}, {@value #R_CARD}, {@value #R_ID_NUMBER}, {@value #R_OVER_LIMIT}.
 */
public final class MemoryImportParser {

    public static final String R_TOO_SHORT = "too_short";
    public static final String R_NO_CONTENT = "no_content";
    public static final String R_TOO_LONG = "too_long";
    public static final String R_INJECTION = "prompt_injection";
    public static final String R_INSTRUCTION = "instruction";
    public static final String R_UNSAFE = "unsafe_content";
    public static final String R_CREDENTIALS = "credentials";
    public static final String R_SECRET = "secret";
    public static final String R_CARD = "card_number";
    public static final String R_ID_NUMBER = "id_number";
    public static final String R_OVER_LIMIT = "over_limit";

    /** Shown instead of the text of a line rejected as sensitive, so the preview never echoes a secret. */
    public static final String HIDDEN = "[hidden]";

    /** Longest single fact kept. A longer line is split into sentences first; a sentence over it is rejected. */
    public static final int DEFAULT_MAX_FACT_CHARS = 500;
    static final int MIN_FACT_CHARS = 3;
    private static final int DISPLAY_CHARS = 200;

    private static final Set<String> SENSITIVE_REASONS = Set.of(R_CREDENTIALS, R_SECRET, R_CARD, R_ID_NUMBER);

    private MemoryImportParser() {
    }

    /** One cleaned line. {@code rejectedReason} is null for a usable fact; {@code index} is its client-local id. */
    public record Candidate(int index, String text, String rejectedReason) {
        public boolean rejected() {
            return rejectedReason != null;
        }
    }

    /**
     * @param candidates       usable and rejected lines in paste order (in-paste duplicates are collapsed away)
     * @param lines            non-blank lines read from the paste, headings and chatter included
     * @param pasteDuplicates  usable lines dropped because the same fact appeared earlier in the paste
     */
    public record ParseResult(List<Candidate> candidates, int lines, int pasteDuplicates) {
    }

    /** A single fact after cleaning; {@code rejectedReason} null = ok. */
    public record Screened(String text, String rejectedReason) {
    }

    // ---- patterns ----

    private static final String MONTHS = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?";
    private static final String DATE = "(?:\\d{4}-\\d{2}(?:-\\d{2})?(?:[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?Z?)?"
        + "|\\d{1,2}/\\d{1,2}/\\d{2,4}"
        + "|" + MONTHS + " \\d{1,2},? \\d{4}"
        + "|\\d{1,2} " + MONTHS + " \\d{4})";
    private static final Pattern LEADING_DATE = Pattern.compile(
        "^(?:[\\[(]\\s*" + DATE + "\\s*[\\])]\\s*[:\\-–—]?|" + DATE + "\\s*[:\\-–—])\\s*",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_MARKER = Pattern.compile(
        "^(?:>+\\s*|(?:[-*+•·▪◦‣–—]|\\d{1,3}[.)]|\\(\\d{1,3}\\)|\\[[ xX]\\])\\s+)");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}(?:\\s|$)");
    private static final Pattern RULE_LINE = Pattern.compile("^[-*_=|:\\s]{3,}$");
    private static final Pattern FENCE = Pattern.compile("^(?:```|~~~)");
    private static final Pattern CHATTER = Pattern.compile(
        "^(?:sure|certainly|of course|absolutely|here(?:'s| is| are)|below (?:is|are)|i hope this helps|hope this helps"
            + "|let me know|is there anything|feel free|note that|please note)\\b.*",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern NO_CONTENT = Pattern.compile(
        "^(?:none|n/a|na|nothing|unknown|\\(none\\)|not (?:available|applicable)|no (?:memories|memory|information|data|details)\\b.*"
            + "|no (?:stored|saved) .*|i (?:do not|don't|dont) (?:have|know|remember) (?:any|anything|enough|stored|saved)\\b.*"
            + "|i have no (?:stored|saved) .*|i (?:haven't|have not) (?:stored|saved) .*)\\.?$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern BOLD_ONLY = Pattern.compile("^(?:\\*\\*|__)[^*_]+(?:\\*\\*|__)\\s*:?$");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern MD_BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern MD_BOLD_UNDERSCORE = Pattern.compile("__(.+?)__");
    private static final Pattern MD_ITALIC_STAR = Pattern.compile("(?<![\\w*])\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*(?![\\w*])");
    private static final Pattern MD_ITALIC_UNDERSCORE = Pattern.compile("(?<![\\w_])_(?!\\s)([^_\\n]+?)(?<!\\s)_(?![\\w_])");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s  -   　]+");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?。])\\s+(?=[\\p{Lu}\"“'(\\d])");

    private static final int CI = Pattern.CASE_INSENSITIVE;

    private static final Pattern[] INJECTION = {
        Pattern.compile("\\b(?:ignore|disregard|forget|override|bypass)\\b[^.\\n]{0,40}\\b(?:previous|prior|above|earlier|preceding|all|any|your|the)\\b"
            + "[^.\\n]{0,30}\\b(?:instructions?|prompts?|rules?|directions?|guidelines?|messages?|context|constraints?)\\b", CI),
        Pattern.compile("(?:^|[.!?;]\\s+)(?:system|developer|assistant)\\s*(?:prompt|message|instructions?)?\\s*:", CI),
        Pattern.compile("<\\|?(?:system|im_start|im_end|endoftext|assistant|user)\\|?>|<\\|[a-z_]+\\|>|\\[/?(?:INST|SYS)]|<</?SYS>>", CI),
        Pattern.compile("#{2,}\\s*(?:system|instruction)", CI),
        Pattern.compile("\\bnew\\s+(?:system\\s+)?instructions?\\b", CI),
        Pattern.compile("\\b(?:reveal|show|print|repeat|leak|output)\\b[^.\\n]{0,30}\\b(?:system|hidden|initial)\\s+(?:prompt|instructions?)\\b", CI),
        Pattern.compile("\\bjailbreak|\\bDAN mode\\b|\\bdeveloper mode\\b", CI),
        Pattern.compile("\\bfrom now on\\b[^.\\n]{0,40}\\b(?:you|always|never|ignore)\\b", CI),
        Pattern.compile("\\byou\\s+(?:must|should|shall|will|need to|have to)\\s+(?:now\\s+)?(?:always|never|ignore|obey|follow|act|pretend|respond|reply|answer|say|call)\\b", CI),
        Pattern.compile("\\bpretend (?:to be|you are|you're)\\b", CI),
        Pattern.compile("\\bdo not (?:tell|reveal|disclose|mention)\\b[^.\\n]{0,30}\\b(?:the user|anyone|them)\\b", CI),
    };
    private static final Pattern UNSAFE = Pattern.compile(
        "<\\s*/?\\s*(?:script|iframe|img|svg|object|embed|style|link|meta)\\b|\\bjavascript:\\S|\\bdata:text/html|!\\[[^\\]]*]\\([^)]*\\)", CI);
    private static final Pattern INSTRUCTION_START = Pattern.compile(
        "^(?:do not|don't|dont|please|make sure|be sure|ensure|remember to|ignore|forget|disregard|pretend|respond|reply"
            + "|answer|act as|from now on)\\b"
            + "|^(?:always|never)\\s+(?:respond|reply|answer|use|call|say|write|include|ask|speak|address|format|keep|start|end|refer|give|provide|mention|add|avoid)\\b"
            + "|^you(?:'re| are) (?:now |an? )(?:helpful|ai|assistant|expert|language model|chatbot|bot)\\b"
            + "|^your (?:role|task|job|goal|purpose) is\\b",
        CI);

    private static final Pattern URL_CREDENTIALS = Pattern.compile("[a-z][a-z0-9+.-]*://[^\\s/@]+@", CI);
    private static final Pattern URL_SECRET_PARAM = Pattern.compile(
        "[?&](?:token|access_token|api[_-]?key|apikey|key|secret|password|passwd|sig|signature|auth)=[^\\s&]{6,}", CI);
    private static final Pattern PRIVATE_KEY = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");
    private static final Pattern KEY_PREFIXES = Pattern.compile(
        "\\b(?:sk|pk|rk)[-_][A-Za-z0-9_-]{16,}|\\bAKIA[0-9A-Z]{16}\\b|\\bAIza[0-9A-Za-z_-]{30,}|\\bgh[pousr]_[A-Za-z0-9]{30,}"
            + "|\\bgithub_pat_[A-Za-z0-9_]{30,}|\\bxox[abposr]-[A-Za-z0-9-]{10,}|\\bglpat-[A-Za-z0-9_-]{20,}|\\bnpm_[A-Za-z0-9]{30,}"
            + "|\\bhf_[A-Za-z0-9]{30,}|\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern CREDENTIAL_PHRASE = Pattern.compile(
        "\\b(?:password|passwd|passcode|passphrase|cvv|cvc|pin|otp|api[ _-]?key|secret[ _-]?key|access[ _-]?token|auth(?:orization)? token|private key)"
            + "\\b\\s*(?:is|are|was|=|:)\\s*\\S+", CI);
    private static final Pattern OPAQUE_CHARSET = Pattern.compile("[A-Za-z0-9+/=_-]+");
    private static final Pattern UUID_LIKE = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern CARD_CANDIDATE = Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");
    private static final Pattern SSN = Pattern.compile("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)");
    private static final Pattern AADHAAR = Pattern.compile("(?<!\\d)[2-9]\\d{3}[ -]?\\d{4}[ -]?\\d{4}(?!\\d)");
    private static final Pattern PAN = Pattern.compile("\\b[A-Z]{5}\\d{4}[A-Z]\\b");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?\\b");

    // ---- public API ----

    /**
     * @param raw        the pasted text (the caller has already enforced the payload cap)
     * @param maxFacts   how many usable facts to keep; further usable lines come back rejected as {@value #R_OVER_LIMIT}
     * @param maxFactChars per-fact length cap, normally {@link #DEFAULT_MAX_FACT_CHARS}
     */
    public static ParseResult parse(String raw, int maxFacts, int maxFactChars) {
        if (raw == null || raw.isBlank()) {
            return new ParseResult(List.of(), 0, 0);
        }
        int factCap = Math.max(1, maxFacts);
        int charCap = Math.max(MIN_FACT_CHARS, maxFactChars);
        String cleaned = stripInvisible(raw);
        int nonBlank = 0;
        int pasteDuplicates = 0;
        int kept = 0;
        List<String[]> pending = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : cleaned.split("\n", -1)) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            nonBlank++;
            if (FENCE.matcher(t).find() || HEADING.matcher(t).find() || RULE_LINE.matcher(t).matches()
                || BOLD_ONLY.matcher(t).matches()) {
                continue;
            }
            if (UNSAFE.matcher(t).find()) {
                pending.add(new String[] {displayText(collapse(t), R_UNSAFE), R_UNSAFE});
                continue;
            }
            String text = stripMarkup(t);
            if (text.isEmpty() || text.endsWith(":") || CHATTER.matcher(text).matches()) {
                continue;
            }
            String lineSecret = secretReason(t);
            if (lineSecret != null) {
                pending.add(new String[] {HIDDEN, lineSecret});
                continue;
            }
            List<String> pieces = text.length() > charCap ? splitSentences(text) : List.of(text);
            for (String piece : pieces) {
                String p = collapse(piece);
                if (p.length() < MIN_FACT_CHARS && pieces.size() > 1) {
                    continue;
                }
                String reason = rejectionReason(p, charCap);
                if (reason == null) {
                    if (!seen.add(dedupeKey(p))) {
                        pasteDuplicates++;
                        continue;
                    }
                    if (kept >= factCap) {
                        pending.add(new String[] {p, R_OVER_LIMIT});
                        continue;
                    }
                    kept++;
                    pending.add(new String[] {p, null});
                } else {
                    pending.add(new String[] {displayText(p, reason), reason});
                }
            }
        }
        List<Candidate> out = new ArrayList<>(pending.size());
        for (String[] row : pending) {
            out.add(new Candidate(out.size(), row[0], row[1]));
        }
        return new ParseResult(out, nonBlank, pasteDuplicates);
    }

    /** Cleans one fact the way {@link #parse} would (invisible characters, whitespace) and screens it. */
    public static Screened screen(String raw, int maxFactChars) {
        if (raw == null) {
            return new Screened("", R_TOO_SHORT);
        }
        String text = collapse(stripInvisible(raw).replace('\n', ' '));
        int cap = Math.max(MIN_FACT_CHARS, maxFactChars);
        String reason = secretReason(text);
        if (reason == null) {
            reason = rejectionReason(text, cap);
        }
        return new Screened(reason != null && SENSITIVE_REASONS.contains(reason) ? HIDDEN : text, reason);
    }

    /** Case-, punctuation- and spacing-insensitive identity of a fact, for dedupe in and against memory. */
    public static String dedupeKey(String text) {
        if (text == null) {
            return "";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean space = true;
        for (int i = 0; i < lower.length(); ) {
            int cp = lower.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(cp);
                space = false;
            } else if (!space) {
                sb.append(' ');
                space = true;
            }
        }
        return sb.toString().strip();
    }

    public static boolean isSensitiveReason(String reason) {
        return reason != null && SENSITIVE_REASONS.contains(reason);
    }

    // ---- cleaning ----

    /**
     * Drops control characters and invisible/format code points: the Unicode TAG block (used to smuggle hidden
     * instructions), zero-width and bidi controls, soft hyphens, private-use and unpaired surrogates. Keeps
     * newlines (tabs become spaces). ZWNJ/ZWJ are kept only between letters/marks that are not both Latin,
     * where Indic and Persian scripts need them; elsewhere (including emoji sequences) they are dropped.
     */
    static String stripInvisible(String s) {
        int[] cps = s.codePoints().toArray();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < cps.length; i++) {
            int cp = cps[i];
            if (cp == '\n') {
                sb.append('\n');
                continue;
            }
            if (cp == '\t') {
                sb.append(' ');
                continue;
            }
            if (cp == 0x200C || cp == 0x200D) {
                boolean before = i > 0 && isLetterOrMark(cps[i - 1]);
                boolean after = i + 1 < cps.length && isLetterOrMark(cps[i + 1]);
                boolean bothLatin = before && after && isLatin(cps[i - 1]) && isLatin(cps[i + 1]);
                if (before && after && !bothLatin) {
                    sb.appendCodePoint(cp);
                }
                continue;
            }
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.PRIVATE_USE
                || type == Character.SURROGATE || (cp >= 0xE0000 && cp <= 0xE007F) || cp == 0x180E) {
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    private static boolean isLatin(int cp) {
        return Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN;
    }

    private static boolean isLetterOrMark(int cp) {
        return Character.isLetter(cp) || Character.getType(cp) == Character.NON_SPACING_MARK
            || Character.getType(cp) == Character.COMBINING_SPACING_MARK;
    }

    /** Removes bullets, numbering, quote markers, a leading date, markdown emphasis/links/code and wrapping quotes. */
    static String stripMarkup(String line) {
        String s = line.strip();
        for (int i = 0; i < 3; i++) {
            Matcher m = LEADING_MARKER.matcher(s);
            if (!m.find()) {
                break;
            }
            s = s.substring(m.end()).stripLeading();
        }
        Matcher d = LEADING_DATE.matcher(s);
        if (d.find()) {
            s = s.substring(d.end()).stripLeading();
            Matcher m = LEADING_MARKER.matcher(s);
            if (m.find()) {
                s = s.substring(m.end()).stripLeading();
            }
        }
        s = MD_LINK.matcher(s).replaceAll("$1");
        s = MD_BOLD.matcher(s).replaceAll("$1");
        s = MD_BOLD_UNDERSCORE.matcher(s).replaceAll("$1");
        s = MD_ITALIC_STAR.matcher(s).replaceAll("$1");
        s = MD_ITALIC_UNDERSCORE.matcher(s).replaceAll("$1");
        s = s.replace("`", "").replace("**", "");
        s = collapse(s);
        return unwrapQuotes(s);
    }

    private static String unwrapQuotes(String in) {
        String s = in;
        for (int i = 0; i < 2 && s.length() >= 2; i++) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            boolean wrapped = (a == '"' && b == '"') || (a == '“' && b == '”') || (a == '\'' && b == '\'')
                || (a == '‘' && b == '’') || (a == '«' && b == '»');
            if (!wrapped) {
                break;
            }
            s = s.substring(1, s.length() - 1).strip();
        }
        return s;
    }

    private static String collapse(String s) {
        return WHITESPACE.matcher(s).replaceAll(" ").strip();
    }

    private static List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        for (String s : SENTENCE_BREAK.split(text)) {
            String t = s.strip();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out.isEmpty() ? List.of(text) : out;
    }

    private static String displayText(String text, String reason) {
        if (SENSITIVE_REASONS.contains(reason)) {
            return HIDDEN;
        }
        return text.length() > DISPLAY_CHARS ? text.substring(0, DISPLAY_CHARS) + "…" : text;
    }

    // ---- screening ----

    /** Null when the (already cleaned) text is a usable fact. Sensitive checks run first so a secret is never echoed. */
    static String rejectionReason(String text, int maxFactChars) {
        String secret = secretReason(text);
        if (secret != null) {
            return secret;
        }
        if (text.length() < MIN_FACT_CHARS || text.codePoints().noneMatch(Character::isLetter)) {
            return R_TOO_SHORT;
        }
        if (NO_CONTENT.matcher(text).matches()) {
            return R_NO_CONTENT;
        }
        if (text.length() > maxFactChars) {
            return R_TOO_LONG;
        }
        for (Pattern p : INJECTION) {
            if (p.matcher(text).find()) {
                return R_INJECTION;
            }
        }
        if (UNSAFE.matcher(text).find()) {
            return R_UNSAFE;
        }
        if (INSTRUCTION_START.matcher(text).find()) {
            return R_INSTRUCTION;
        }
        return null;
    }

    /** Credentials, API-key-like tokens, long opaque strings, card numbers and government-ID patterns. */
    static String secretReason(String text) {
        if (URL_CREDENTIALS.matcher(text).find() || URL_SECRET_PARAM.matcher(text).find()) {
            return R_CREDENTIALS;
        }
        if (PRIVATE_KEY.matcher(text).find() || KEY_PREFIXES.matcher(text).find() || CREDENTIAL_PHRASE.matcher(text).find()) {
            return R_SECRET;
        }
        for (String token : text.split("\\s+")) {
            String t = token.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9=]+$", "");
            if (looksOpaque(t)) {
                return R_SECRET;
            }
        }
        Matcher card = CARD_CANDIDATE.matcher(text);
        while (card.find()) {
            String digits = card.group().replaceAll("\\D", "");
            if (digits.length() >= 13 && digits.length() <= 19 && luhnValid(digits)) {
                return R_CARD;
            }
        }
        if (SSN.matcher(text).find() || PAN.matcher(text).find() || IBAN.matcher(text).find()) {
            return R_ID_NUMBER;
        }
        Matcher aadhaar = AADHAAR.matcher(text);
        while (aadhaar.find()) {
            if (verhoeffValid(aadhaar.group().replaceAll("\\D", ""))) {
                return R_ID_NUMBER;
            }
        }
        return null;
    }

    private static boolean looksOpaque(String t) {
        if (t.length() < 32 || !OPAQUE_CHARSET.matcher(t).matches()) {
            return false;
        }
        boolean digit = false;
        boolean letter = false;
        int separators = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (Character.isLetter(c)) {
                letter = true;
            } else if (c == '-' || c == '_') {
                separators++;
            }
        }
        if (!digit || !letter) {
            return false;
        }
        // A hyphenated slug ("my-long-article-title-from-2024") is not a key; a UUID still is an opaque identifier.
        return separators <= 3 || UUID_LIKE.matcher(t).matches();
    }

    static boolean luhnValid(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (dbl) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private static final int[][] VERHOEFF_D = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
        {3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
        {6, 5, 9, 8, 7, 1, 0, 4, 3, 2}, {7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
        {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
    private static final int[][] VERHOEFF_P = {
        {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
        {8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
        {2, 7, 9, 3, 8, 0, 6, 4, 1, 5}, {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

    /** Aadhaar numbers carry a Verhoeff check digit; using it keeps "2024 2025 2026" from looking like an ID. */
    static boolean verhoeffValid(String digits) {
        if (digits.length() != 12) {
            return false;
        }
        int c = 0;
        for (int i = 0; i < digits.length(); i++) {
            int n = digits.charAt(digits.length() - 1 - i) - '0';
            c = VERHOEFF_D[c][VERHOEFF_P[i % 8][n]];
        }
        return c == 0;
    }
}
