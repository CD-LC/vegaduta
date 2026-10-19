package com.agenticai.core.agents.demo.studio;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The deterministic half of {@link DemoStudioModerationService}'s layer (a): text normalisation
 * (case, diacritics, look-alikes, invisible characters, leetspeak, spaced letters) and loading and
 * compiling the term list ({@code demo-studio/moderation-terms.txt}). Split out of the service to
 * keep it under the file-size cap; the service keeps the matching and the public entry points.
 */
final class DemoStudioModerationText {

    private static final Logger log = LoggerFactory.getLogger(DemoStudioModerationService.class);

    private static final String[] SUFFIXES = {"s", "es", "ed", "er", "ers", "ing", "in", "z", "y"};
    private static final String LATIN_BEFORE = "(?<![\\p{L}\\p{M}])";
    private static final String LATIN_AFTER = "(?![\\p{L}\\p{M}])";
    private static final String SCRIPT_BEFORE = "(?<![\\p{L}\\p{M}\\p{N}])";
    private static final String SCRIPT_AFTER = "(?![\\p{L}\\p{M}\\p{N}])";
    private static final String WORD_SEP = "[\\s._\\-]+";
    /** Three or more single letters split by separators: "f u c k", "f.u.c.k". */
    private static final Pattern SPACED_LETTERS =
        Pattern.compile("(?<![\\p{L}\\p{N}])\\p{L}(?:[\\s._\\-*|/,]{1,3}\\p{L}){2,}(?![\\p{L}\\p{N}])");
    private static final Pattern REPEATS = Pattern.compile("(\\p{L})\\1{2,}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private DemoStudioModerationText() {}

    /** Case, diacritics, look-alikes, invisible characters, stretched letters, whitespace. */
    static String normalise(String raw) {
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
        StringBuilder out = new StringBuilder(s.length());
        boolean lastLatin = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isInvisible(cp)) {
                continue;
            }
            int mapped = lookalike(cp);
            if (mapped != cp) {
                out.appendCodePoint(mapped);
                lastLatin = true;
                continue;
            }
            if (Character.getType(cp) == Character.NON_SPACING_MARK) {
                if (lastLatin) {
                    continue; // a combining mark hanging off a Latin letter: strip. Devanagari matras stay.
                }
                out.appendCodePoint(cp);
                continue;
            }
            boolean latin = Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN;
            if (latin && cp > 127) {
                String decomposed = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFD);
                for (int j = 0; j < decomposed.length(); ) {
                    int d = decomposed.codePointAt(j);
                    j += Character.charCount(d);
                    if (Character.getType(d) != Character.NON_SPACING_MARK) {
                        out.appendCodePoint(d);
                    }
                }
            } else {
                out.appendCodePoint(cp);
            }
            lastLatin = latin;
        }
        String collapsed = REPEATS.matcher(out).replaceAll("$1$1");
        return WHITESPACE.matcher(collapsed).replaceAll(" ").strip();
    }

    private static boolean isInvisible(int cp) {
        return (cp >= 0x200B && cp <= 0x200F) || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2060 && cp <= 0x2064)
            || cp == 0xFEFF || cp == 0x00AD || (cp >= 0xFE00 && cp <= 0xFE0F);
    }

    private static int lookalike(int cp) {
        return switch (cp) {
            case 0x0430, 0x03B1 -> 'a';
            case 0x0435, 0x03B5 -> 'e';
            case 0x043E, 0x03BF -> 'o';
            case 0x0440, 0x03C1 -> 'p';
            case 0x0441 -> 'c';
            case 0x0443 -> 'y';
            case 0x0445, 0x03C7 -> 'x';
            case 0x0456, 0x03B9, 0x0131 -> 'i';
            case 0x0455 -> 's';
            case 0x0458 -> 'j';
            case 0x043A, 0x03BA -> 'k';
            case 0x043D -> 'h';
            case 0x0442 -> 't';
            case 0x00F8 -> 'o';
            case 0x0111 -> 'd';
            case 0x0142 -> 'l';
            default -> cp;
        };
    }

    /** 5hit, a$$, b00bs, sh!t, f*ck - folded per token, and only inside tokens that carry letters. */
    static String foldLeet(String plain) {
        String[] tokens = plain.split(" ", -1);
        StringBuilder out = new StringBuilder(plain.length());
        for (int t = 0; t < tokens.length; t++) {
            if (t > 0) {
                out.append(' ');
            }
            String tok = tokens[t];
            int letters = 0;
            int leet = 0;
            for (int i = 0; i < tok.length(); i++) {
                char c = tok.charAt(i);
                if (Character.isLetter(c)) {
                    letters++;
                } else if (isLeet(c)) {
                    leet++;
                }
            }
            if (letters == 0 || leet == 0 || letters + leet < 3) {
                out.append(tok);
                continue;
            }
            for (int i = 0; i < tok.length(); i++) {
                char c = tok.charAt(i);
                boolean between = i > 0 && i < tok.length() - 1
                    && Character.isLetter(tok.charAt(i - 1)) && Character.isLetter(tok.charAt(i + 1));
                switch (c) {
                    case '0' -> out.append('o');
                    case '1' -> out.append('i');
                    case '3', '€' -> out.append('e');
                    case '4' -> out.append('a');
                    case '5', '$' -> out.append('s');
                    case '7' -> out.append('t');
                    case '@' -> out.append('a');
                    case '!', '|', '+' -> out.append(between ? (c == '+' ? 't' : 'i') : c);
                    default -> out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static boolean isLeet(char c) {
        return "013457@$€!|+".indexOf(c) >= 0;
    }

    static String joinSpacedLetters(String folded) {
        Matcher m = SPACED_LETTERS.matcher(folded);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(' ');
            for (int i = 0; i < m.group().length(); i++) {
                char c = m.group().charAt(i);
                if (Character.isLetter(c)) {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    static DemoStudioModerationService.Terms loadTerms(String externalFile) {
        List<String> lines = null;
        if (externalFile != null && !externalFile.isBlank()) {
            try {
                lines = Files.readAllLines(Path.of(externalFile.strip()), StandardCharsets.UTF_8);
                log.info("Studio moderation: term list loaded from {}", externalFile);
            } catch (IOException | RuntimeException e) {
                log.error("Studio moderation: cannot read terms file {} - using the bundled list", externalFile, e);
            }
        }
        if (lines == null) {
            try (InputStream in = DemoStudioModerationService.class.getResourceAsStream("/demo-studio/moderation-terms.txt")) {
                if (in == null) {
                    log.error("Studio moderation: bundled term list is missing - layer (a) has NO terms");
                    return new DemoStudioModerationService.Terms(Map.of(), Map.of(), null);
                }
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    lines = r.lines().toList();
                }
            } catch (IOException e) {
                log.error("Studio moderation: bundled term list unreadable - layer (a) has NO terms", e);
                return new DemoStudioModerationService.Terms(Map.of(), Map.of(), null);
            }
        }
        return parseTerms(lines);
    }

    static DemoStudioModerationService.Terms parseTerms(List<String> lines) {
        Map<String, Set<String>> latinExact = new LinkedHashMap<>();
        Map<String, Set<String>> latinSuffixed = new LinkedHashMap<>();
        Map<String, Set<String>> script = new LinkedHashMap<>();
        Map<String, List<Pattern>> patterns = new LinkedHashMap<>();
        List<String> allow = new ArrayList<>();
        String section = null;
        String category = null;
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                String head = line.substring(1, line.length() - 1).strip().toLowerCase(java.util.Locale.ROOT);
                int colon = head.indexOf(':');
                section = colon < 0 ? head : head.substring(0, colon);
                category = colon < 0 ? null : head.substring(colon + 1);
                continue;
            }
            if (section == null) {
                continue;
            }
            switch (section) {
                case "allow" -> allow.add(line);
                case "words" -> {
                    boolean exact = line.startsWith("=");
                    String term = normalise(exact ? line.substring(1) : line);
                    if (term.isEmpty() || category == null) {
                        break;
                    }
                    if (isLatinTerm(term)) {
                        (exact ? latinExact : latinSuffixed).computeIfAbsent(category, k -> new LinkedHashSet<>()).add(latinRegex(term));
                    } else {
                        script.computeIfAbsent(category, k -> new LinkedHashSet<>()).add(scriptRegex(term));
                    }
                }
                case "pattern" -> {
                    if (category == null) {
                        break;
                    }
                    try {
                        patterns.computeIfAbsent(category, k -> new ArrayList<>()).add(Pattern.compile(line));
                    } catch (PatternSyntaxException e) {
                        log.error("Studio moderation: skipping an invalid pattern in category {}: {}", category, e.getDescription());
                    }
                }
                default -> {
                }
            }
        }
        Set<String> categories = new LinkedHashSet<>();
        categories.addAll(latinExact.keySet());
        categories.addAll(latinSuffixed.keySet());
        categories.addAll(script.keySet());
        Map<String, DemoStudioModerationService.WordMatchers> words = new LinkedHashMap<>();
        for (String c : categories) {
            Pattern latin = null;
            Set<String> suffixed = latinSuffixed.get(c);
            Set<String> exact = latinExact.get(c);
            List<String> parts = new ArrayList<>();
            if (suffixed != null && !suffixed.isEmpty()) {
                parts.add("(?:" + String.join("|", suffixed) + ")(?:" + String.join("|", SUFFIXES) + ")?");
            }
            if (exact != null && !exact.isEmpty()) {
                parts.add("(?:" + String.join("|", exact) + ")");
            }
            if (!parts.isEmpty()) {
                latin = Pattern.compile(LATIN_BEFORE + "(?:" + String.join("|", parts) + ")" + LATIN_AFTER);
            }
            Pattern sc = null;
            Set<String> s = script.get(c);
            if (s != null && !s.isEmpty()) {
                sc = Pattern.compile(SCRIPT_BEFORE + "(?:" + String.join("|", s) + ")" + SCRIPT_AFTER);
            }
            words.put(c, new DemoStudioModerationService.WordMatchers(latin, sc));
        }
        Pattern allowPattern = null;
        if (!allow.isEmpty()) {
            List<String> alts = new ArrayList<>();
            for (String phrase : allow) {
                String n = normalise(phrase);
                if (!n.isEmpty()) {
                    List<String> ws = new ArrayList<>();
                    for (String w : n.split("[\\s\\-]+")) {
                        if (!w.isEmpty()) {
                            ws.add(Pattern.quote(w));
                        }
                    }
                    alts.add(String.join(WORD_SEP, ws));
                }
            }
            if (!alts.isEmpty()) {
                allowPattern = Pattern.compile(SCRIPT_BEFORE + "(?:" + String.join("|", alts) + ")" + SCRIPT_AFTER);
            }
        }
        return new DemoStudioModerationService.Terms(words, patterns, allowPattern);
    }

    private static boolean isLatinTerm(String term) {
        return term.codePoints().anyMatch(cp -> Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN);
    }

    /** Each letter may be stretched (input is pre-collapsed to two) and, after the first, may be masked with '*'. */
    private static String latinRegex(String term) {
        List<String> words = new ArrayList<>();
        for (String w : term.split("[\\s\\-]+")) {
            if (w.isEmpty()) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if (c >= 'a' && c <= 'z') {
                    sb.append(i == 0 ? String.valueOf(c) + "{1,2}" : "[" + c + "*]{1,2}");
                } else {
                    sb.append(Pattern.quote(String.valueOf(c)));
                }
            }
            words.add(sb.toString());
        }
        return String.join(WORD_SEP, words);
    }

    private static String scriptRegex(String term) {
        List<String> words = new ArrayList<>();
        for (String w : term.split("\\s+")) {
            if (!w.isEmpty()) {
                words.add(Pattern.quote(w));
            }
        }
        return String.join("\\s*", words);
    }
}
