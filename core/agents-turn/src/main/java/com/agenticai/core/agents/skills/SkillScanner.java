package com.agenticai.core.agents.skills;

import static com.agenticai.core.agents.skills.SkillScanRules.*;

import com.fasterxml.jackson.annotation.JsonValue;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;

/**
 * Static supply-chain scan of one skill (SKILL.md name, description, body and bundled files).
 *
 * <p>Why this exists: a skill is third-party text that ends up in an agent's context. Published
 * research on public skill registries found a meaningful share of skills were malicious, and that
 * "metadata-only" attacks (an instruction hidden in the one-line description every agent always
 * sees) slip past scanners that only read the body. So the description and the file names are
 * scanned with the same rules as the body, and a description gets stricter treatment because it
 * reaches every prompt.
 *
 * <p>What it is NOT: a proof of safety. It is a deterministic, regex-and-Unicode heuristic. A clean
 * result reduces risk; it does not mean the skill is safe, and a hostile author who knows these
 * rules can word around them. The human review gate in {@link SkillIntegrity} / the approve
 * endpoint is the control; this is the triage that tells the reviewer where to look.
 *
 * <p>Pure and static: no Spring, no I/O, no clock, no randomness - same input, same report, so it
 * is unit-testable against fixtures. Unicode references: the Tag block (U+E0000..E007F) is the
 * "ASCII smuggling" channel, bidi overrides are "Trojan Source" (https://trojansource.codes/),
 * zero-width characters and variation selectors are the other invisible-payload channels (Unicode
 * TR39 confusables for the homoglyph table).
 */
public final class SkillScanner {

    /** Bump when rules change so cached reports are discarded. */
    public static final int SCANNER_VERSION = 1;

    static final int MAX_EXCERPT = 120;
    private static final int MAX_SCAN_CHARS = 300_000;
    private static final int MAX_FINDINGS = 60;
    static final int MAX_PER_RULE = 3;
    private static final int HARD_FINDING_CAP = 400;

    private SkillScanner() {}

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum RiskLevel {
        NONE,
        LOW,
        MEDIUM,
        HIGH;

        @JsonValue
        public String wire() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Lenient parse for a stored column; {@code null} when absent or unknown. */
        public static RiskLevel fromWire(String value) {
            if (value == null) {
                return null;
            }
            String v = value.trim();
            for (RiskLevel level : values()) {
                if (level.name().equalsIgnoreCase(v)) {
                    return level;
                }
            }
            return null;
        }

        static RiskLevel of(Severity severity) {
            return switch (severity) {
                case LOW -> LOW;
                case MEDIUM -> MEDIUM;
                case HIGH -> HIGH;
            };
        }

        public boolean atLeast(RiskLevel other) {
            return compareTo(other) >= 0;
        }
    }

    /**
     * @param rule stable rule id (kebab-case), what the UI keys its explanation on
     * @param excerpt at most 120 characters, control/invisible characters shown as a bracketed hex
     *     code point
     * @param where {@code name}, {@code description}, {@code body}, {@code file:<path>} or {@code
     *     file-name:<path>}
     */
    public record Finding(String rule, Severity severity, String excerpt, String where) {}

    public record Report(
            RiskLevel riskLevel, List<Finding> findings, boolean truncated, int scannerVersion) {
        public Report {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }

        public static Report clean() {
            return new Report(RiskLevel.NONE, List.of(), false, SCANNER_VERSION);
        }

        public int count(Severity severity) {
            int n = 0;
            for (Finding f : findings) {
                if (f.severity() == severity) {
                    n++;
                }
            }
            return n;
        }
    }

    enum Kind {
        NAME,
        DESCRIPTION,
        BODY,
        FILE,
        CODE_FILE;

        boolean metadata() {
            return this == NAME || this == DESCRIPTION;
        }
    }

    // ------------------------------------------------------------------ public entry points

    /**
     * Scans one skill. Every argument may be null/empty. {@code files} maps a relative path to its
     * text content (SKILL.md excluded), exactly as {@link SkillDefinition#files()}.
     */
    public static Report scan(
            String name, String description, String body, Map<String, String> files) {
        Collector c = new Collector();
        String n = name == null ? "" : name;
        String d = description == null ? "" : description;
        String b = body == null ? "" : body;

        scanText("name", n, Kind.NAME, c);
        scanText("description", d, Kind.DESCRIPTION, c);
        scanText("body", b, Kind.BODY, c);
        if (files != null) {
            for (Map.Entry<String, String> e : new TreeMap<>(files).entrySet()) {
                String path = e.getKey() == null ? "" : e.getKey();
                SkillScanPayloads.scanFileName(path, c);
                Kind kind = CODE_EXTENSION.matcher(path).find() ? Kind.CODE_FILE : Kind.FILE;
                scanText("file:" + path, e.getValue() == null ? "" : e.getValue(), kind, c);
            }
        }
        checkMetadataMismatch(d, b, c);
        return c.toReport();
    }

    public static Report scan(SkillDefinition skill) {
        return scan(skill.name(), skill.description(), skill.body(), skill.files());
    }

    // ------------------------------------------------------------------ collector

    static final class Collector {
        final List<Finding> findings = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        boolean truncated;

        void add(String rule, Severity severity, String excerpt, String where) {
            String ex = sanitize(excerpt, MAX_EXCERPT);
            String wh = sanitize(where, 80);
            if (!seen.add(rule + "|" + wh + "|" + ex)) {
                return;
            }
            if (findings.size() >= HARD_FINDING_CAP) {
                truncated = true;
                return;
            }
            findings.add(new Finding(rule, severity, ex, wh));
        }

        boolean has(String where, String rule, Severity atLeast) {
            for (Finding f : findings) {
                if (f.where().equals(where)
                        && f.rule().equals(rule)
                        && f.severity().compareTo(atLeast) >= 0) {
                    return true;
                }
            }
            return false;
        }

        Report toReport() {
            // outbound-transfer is the weaker twin of exfiltrate-data; do not report both for one
            // place.
            Set<String> exfilWheres = new HashSet<>();
            for (Finding f : findings) {
                if (f.rule().equals("exfiltrate-data")) {
                    exfilWheres.add(f.where());
                }
            }
            List<Finding> kept = new ArrayList<>();
            for (Finding f : findings) {
                if (f.rule().equals("outbound-transfer") && exfilWheres.contains(f.where())) {
                    continue;
                }
                kept.add(f);
            }
            kept.sort(
                    Comparator.comparing(Finding::severity, Comparator.reverseOrder())
                            .thenComparing(Finding::rule)
                            .thenComparing(Finding::where)
                            .thenComparing(Finding::excerpt));
            RiskLevel risk = RiskLevel.NONE;
            for (Finding f : kept) {
                RiskLevel level = RiskLevel.of(f.severity());
                if (level.compareTo(risk) > 0) {
                    risk = level;
                }
            }
            boolean cut = truncated || kept.size() > MAX_FINDINGS;
            List<Finding> out = kept.size() > MAX_FINDINGS ? kept.subList(0, MAX_FINDINGS) : kept;
            return new Report(risk, out, cut, SCANNER_VERSION);
        }
    }

    // ------------------------------------------------------------------ text scan

    private static void scanText(String where, String raw, Kind kind, Collector c) {
        if (raw.isEmpty()) {
            return;
        }
        String text = raw;
        if (text.length() > MAX_SCAN_CHARS) {
            text = text.substring(0, MAX_SCAN_CHARS);
            c.truncated = true;
            c.add(
                    "scan-truncated",
                    Severity.LOW,
                    "Only the first " + MAX_SCAN_CHARS + " characters were scanned.",
                    where);
        }
        boolean metadata = kind.metadata();
        scanHiddenUnicode(where, text, metadata, c);
        String norm = normalize(text);

        for (PatternRule rule : RULES) {
            if (kind == Kind.CODE_FILE && rule.skipInCode()) {
                continue;
            }
            if (rule.descriptionOnly() && kind != Kind.DESCRIPTION) {
                continue;
            }
            Matcher m = rule.pattern().matcher(norm);
            int found = 0;
            while (found < MAX_PER_RULE && m.find()) {
                found++;
                boolean obfuscated = !rule.pattern().matcher(text).find();
                Severity severity = rule.severity();
                if (obfuscated) {
                    severity = Severity.HIGH;
                    c.add(
                            "obfuscated-text",
                            Severity.HIGH,
                            "A hidden or look-alike character spelled out text that matches \""
                                    + rule.id()
                                    + "\".",
                            where);
                } else if (metadata && rule.bumpInMetadata()) {
                    severity = Severity.HIGH;
                } else if (rule.defensive()) {
                    if (tightNegation(norm, m.start())) {
                        severity = Severity.LOW;
                    } else if (severity == Severity.HIGH
                            && defensiveSentence(norm, m.start(), m.end())) {
                        severity = Severity.MEDIUM;
                    }
                }
                c.add(rule.id(), severity, context(norm, m.start(), m.end()), where);
            }
        }

        SkillScanPayloads.scanUrls(where, norm, kind, c);
        SkillScanPayloads.scanBlobs(where, text, c);
    }

    private static boolean tightNegation(String norm, int start) {
        int from = Math.max(0, start - 30);
        return TIGHT_NEGATION.matcher(norm.substring(from, start)).find();
    }

    /**
     * True when the sentence holding the match also carries "never/do not/untrusted/attack ..."
     * wording. A sentence ends at ". " / "! " / "? " or a blank line - NOT at a single line break,
     * because markdown prose is hard-wrapped mid-sentence.
     */
    private static boolean defensiveSentence(String norm, int start, int end) {
        int from = start;
        int limit = Math.max(0, start - 300);
        while (from > limit && !sentenceBreakBefore(norm, from)) {
            from--;
        }
        int to = end;
        int max = Math.min(norm.length(), end + 300);
        while (to < max && !sentenceBreakAt(norm, to)) {
            to++;
        }
        return DEFENSIVE_CUE.matcher(norm.substring(from, to)).find();
    }

    /** True when a sentence/paragraph boundary sits immediately before index {@code i}. */
    private static boolean sentenceBreakBefore(String s, int i) {
        char prev = s.charAt(i - 1);
        if (prev == '\n' && i >= 2 && s.charAt(i - 2) == '\n') {
            return true;
        }
        return ".!?".indexOf(prev) >= 0 && i < s.length() && Character.isWhitespace(s.charAt(i));
    }

    /** True when a sentence/paragraph boundary starts at index {@code i}. */
    private static boolean sentenceBreakAt(String s, int i) {
        char ch = s.charAt(i);
        if (ch == '\n' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
            return true;
        }
        return ".!?".indexOf(ch) >= 0
                && (i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1)));
    }

    private static String context(String norm, int start, int end) {
        int from = Math.max(0, start - 20);
        int to = Math.min(norm.length(), end + 20);
        return norm.substring(from, to);
    }

    // ------------------------------------------------------------------ hidden unicode

    static void scanHiddenUnicode(
            String where, String text, boolean metadata, Collector c) {
        int tag = 0;
        StringBuilder tagDecoded = new StringBuilder();
        int bidi = 0;
        int zeroWidth = 0;
        int variation = 0;
        int control = 0;
        int confusable = 0;
        int len = text.length();
        int i = 0;
        while (i < len) {
            int cp = text.codePointAt(i);
            int width = Character.charCount(cp);
            if (cp >= 0xE0000 && cp <= 0xE007F) {
                tag++;
                if (cp >= 0xE0020 && cp <= 0xE007E && tagDecoded.length() < 80) {
                    tagDecoded.append((char) (cp - 0xE0000));
                }
            } else if ((cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069)) {
                bidi++;
            } else if (cp == 0x200C || cp == 0x200D) {
                // ZWNJ/ZWJ are legitimate inside Indic/Arabic text and emoji sequences; only
                // suspicious
                // when they sit between two plain ASCII letters/digits.
                char before = i > 0 ? text.charAt(i - 1) : 0;
                char after = i + width < len ? text.charAt(i + width) : 0;
                if (asciiAlnum(before) && asciiAlnum(after)) {
                    zeroWidth++;
                }
            } else if (isZeroWidth(cp)) {
                if (!(cp == 0xFEFF && i == 0)) {
                    zeroWidth++;
                }
            } else if (cp >= 0xE0100 && cp <= 0xE01EF) {
                variation++;
            } else if ((cp < 0x20 && cp != '\n' && cp != '\r' && cp != '\t')
                    || (cp >= 0x7F && cp <= 0x9F)) {
                control++;
            } else if (isConfusableLetter(cp)) {
                confusable++;
            }
            i += width;
        }

        if (tag > 0) {
            String decoded = tagDecoded.length() > 0 ? " spelling: " + tagDecoded : "";
            c.add(
                    "hidden-unicode-tag",
                    Severity.HIGH,
                    tag + " invisible Unicode tag character(s)" + decoded,
                    where);
        }
        if (bidi > 0) {
            c.add(
                    "hidden-unicode-bidi",
                    Severity.HIGH,
                    bidi
                            + " bidirectional override/isolate control character(s) that can make text read differently than it executes",
                    where);
        }
        if (zeroWidth > 0) {
            Severity s = zeroWidth >= 8 || metadata ? Severity.HIGH : Severity.MEDIUM;
            c.add("hidden-zero-width", s, zeroWidth + " zero-width/invisible character(s)", where);
        }
        if (variation > 0) {
            Severity s = variation >= 4 || metadata ? Severity.HIGH : Severity.MEDIUM;
            c.add(
                    "hidden-unicode-variation-selectors",
                    s,
                    variation + " invisible variation-selector character(s)",
                    where);
        }
        if (control > 0) {
            c.add(
                    "control-characters",
                    metadata ? Severity.HIGH : Severity.MEDIUM,
                    control + " non-printing control character(s)",
                    where);
        }
        if (confusable >= 3) {
            c.add(
                    "confusable-letters",
                    metadata ? Severity.HIGH : Severity.MEDIUM,
                    confusable + " full-width or mathematical look-alike letter(s)",
                    where);
        }
        scanMixedScript(where, text, metadata, c);
    }

    private static boolean asciiAlnum(char ch) {
        return ch < 128 && Character.isLetterOrDigit(ch);
    }

    private static boolean isZeroWidth(int cp) {
        return cp == 0x200B
                || cp == 0x2060
                || (cp >= 0x2061 && cp <= 0x2064)
                || cp == 0xFEFF
                || cp == 0x180E
                || cp == 0x034F
                || cp == 0x115F
                || cp == 0x1160
                || cp == 0x3164
                || cp == 0xFFA0;
    }

    private static boolean isConfusableLetter(int cp) {
        return (cp >= 0x1D400 && cp <= 0x1D7FF)
                || (cp >= 0xFF21 && cp <= 0xFF3A)
                || (cp >= 0xFF41 && cp <= 0xFF5A);
    }

    /** A word mixing Latin with Cyrillic/Greek letters is the classic look-alike spoof. */
    private static void scanMixedScript(String where, String text, boolean metadata, Collector c) {
        Set<String> reported = new LinkedHashSet<>();
        StringBuilder word = new StringBuilder();
        EnumSet<Character.UnicodeScript> scripts = EnumSet.noneOf(Character.UnicodeScript.class);
        int i = 0;
        int len = text.length();
        while (i <= len && reported.size() < 5) {
            int cp = i < len ? text.codePointAt(i) : -1;
            if (cp >= 0 && Character.isLetter(cp)) {
                word.appendCodePoint(cp);
                scripts.add(Character.UnicodeScript.of(cp));
            } else {
                if (word.codePointCount(0, word.length()) >= 3
                        && scripts.contains(Character.UnicodeScript.LATIN)
                        && (scripts.contains(Character.UnicodeScript.CYRILLIC)
                                || scripts.contains(Character.UnicodeScript.GREEK))) {
                    reported.add(word.toString());
                }
                word.setLength(0);
                scripts.clear();
            }
            i += cp >= 0 ? Character.charCount(cp) : 1;
        }
        for (String w : reported) {
            c.add(
                    "homoglyph-mixed-script",
                    metadata ? Severity.HIGH : Severity.MEDIUM,
                    "word mixes Latin with Cyrillic/Greek letters: " + w,
                    where);
        }
    }

    // ------------------------------------------------------------------ metadata vs body

    private static void checkMetadataMismatch(String description, String body, Collector c) {
        if (description.isBlank() || body.isBlank()) {
            return;
        }
        String d = normalize(description);
        Matcher claim = READONLY_CLAIM.matcher(d);
        if (!claim.find()) {
            return;
        }
        String claimText = claim.group();
        for (String rule : ACTION_RULE_IDS) {
            if (c.has("body", rule, Severity.MEDIUM)) {
                c.add(
                        "metadata-mismatch",
                        Severity.HIGH,
                        "The description claims \""
                                + claimText
                                + "\" but the instructions trigger \""
                                + rule
                                + "\".",
                        "description");
                return;
            }
        }
        String nb =
                normalize(
                        body.length() > MAX_SCAN_CHARS ? body.substring(0, MAX_SCAN_CHARS) : body);
        Matcher write = WRITE_ACTION.matcher(nb);
        while (write.find()) {
            if (!tightNegation(nb, write.start())) {
                c.add(
                        "metadata-mismatch",
                        Severity.MEDIUM,
                        "The description claims \""
                                + claimText
                                + "\" but the instructions say: "
                                + context(nb, write.start(), write.end()),
                        "description");
                return;
            }
        }
        if (NO_NETWORK_CLAIM.matcher(d).find() && URL.matcher(nb).find()) {
            c.add(
                    "metadata-mismatch",
                    Severity.MEDIUM,
                    "The description claims \""
                            + claimText
                            + "\" but the instructions contain a web address.",
                    "description");
        }
    }

    // ------------------------------------------------------------------ normalisation + sanitising

    /**
     * Folds the text the way a model would read it: invisible characters dropped, Tag-block
     * characters decoded to the ASCII they smuggle, NFKC, a small Cyrillic/Greek look-alike table,
     * lower case.
     */
    static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0xE0020 && cp <= 0xE007E) {
                sb.append((char) (cp - 0xE0000));
            } else if (isInvisible(cp)) {
                continue;
            } else {
                sb.appendCodePoint(cp);
            }
        }
        String folded = Normalizer.normalize(sb, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(folded.length());
        for (int k = 0; k < folded.length(); k++) {
            char ch = folded.charAt(k);
            Character mapped = CONFUSABLES.get(ch);
            out.append(mapped == null ? ch : mapped.charValue());
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** Characters that render as nothing (or only steer rendering) - never text a reader sees. */
    static boolean isInvisible(int cp) {
        if (cp == '\n' || cp == '\r' || cp == '\t') {
            return false;
        }
        if (cp >= 0xE0000 && cp <= 0xE007F) {
            return true;
        }
        if (cp >= 0xFE00 && cp <= 0xFE0F) {
            return true;
        }
        if (cp >= 0xE0100 && cp <= 0xE01EF) {
            return true;
        }
        if (isZeroWidth(cp) || cp == 0x00AD) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.PRIVATE_USE
                || type == Character.UNASSIGNED
                || type == Character.SURROGATE;
    }

    /**
     * Shortens and neutralises text for display: whitespace collapsed, invisible characters shown
     * as a bracketed hex code point.
     */
    static String sanitize(String s, int max) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(s.length(), max + 16));
        boolean lastSpace = false;
        int i = 0;
        while (i < s.length() && sb.length() < max + 16) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (!lastSpace && sb.length() > 0) {
                    sb.append(' ');
                }
                lastSpace = true;
                continue;
            }
            lastSpace = false;
            if (isInvisible(cp)) {
                sb.append("\\u{")
                        .append(Integer.toHexString(cp).toUpperCase(Locale.ROOT))
                        .append('}');
            } else {
                sb.appendCodePoint(cp);
            }
        }
        String out = sb.toString().strip();
        if (out.length() > max) {
            int cut = max - 1;
            if (cut > 0 && Character.isHighSurrogate(out.charAt(cut - 1))) {
                cut--;
            }
            out = out.substring(0, cut) + "…";
        }
        return out;
    }
}
