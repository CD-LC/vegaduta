package com.agenticai.core.guardrails;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The regular expressions the pattern guardrails match, read from classpath resources instead of Java
 * literals, so a deployment can add a language without touching code and the English set is one reviewable
 * file.
 *
 * <p>Locale-keyed sets live at {@code guardrails/patterns/<locale>/<set>.txt}; a set that does not depend
 * on a language (secret formats to redact) at {@code guardrails/patterns/<set>.txt}. The patterns of EVERY
 * locale found on the classpath are in force together, English first: an attacker chooses the language of
 * an injection, so a detector must not depend on the user's locale. Shipping a file for another locale adds
 * patterns; it never replaces the English ones.
 *
 * <p>File format, one entry per line: a blank line or a line starting with {@code #} is ignored; an entry is
 * a Java regular expression, optionally preceded by an inline flag group such as {@code (?i)} or {@code
 * (?im)}, which is turned into the pattern's flags (so the compiled pattern is exactly the one written in code
 * before the move). {@code {GAP}} is replaced by the bounded gap the caller passes (ReDoS guard, see
 * {@link com.agenticai.core.guardrails.impl.JailbreakDetectorGuardrail}). A labelled entry (secret formats)
 * is {@code label :: regex}.
 *
 * <p>The English file of a set is mandatory: a missing or empty one fails fast, because a detector with no
 * patterns would silently stop detecting. A broken line in another locale is logged and skipped.
 */
public final class PatternSets {

    private static final Logger log = LoggerFactory.getLogger(PatternSets.class);

    /** The language whose file must exist. */
    public static final String DEFAULT_LOCALE = "en";

    private static final String BASE = "guardrails/patterns/";
    private static final Pattern FLAG_GROUP = Pattern.compile("^\\(\\?([imsux]+)\\)");
    private static final String LABEL_SEPARATOR = " :: ";

    private static final Map<String, List<Entry>> CACHE = new ConcurrentHashMap<>();

    private PatternSets() {}

    /** One compiled pattern; {@code label} is empty for an unlabelled entry. */
    public record Entry(String label, Pattern pattern, String locale) {}

    /**
     * The locale-keyed set {@code name} over every locale on the classpath, English first. {@code substitutions}
     * replaces {@code {token}} occurrences before compiling.
     */
    public static List<Entry> localized(String name, Map<String, String> substitutions) {
        return CACHE.computeIfAbsent("L|" + name + "|" + new TreeMap<>(substitutions), k -> loadLocalized(name, substitutions));
    }

    public static List<Entry> localized(String name) {
        return localized(name, Map.of());
    }

    /** The language-independent set {@code name}. */
    public static List<Entry> shared(String name) {
        return CACHE.computeIfAbsent("S|" + name, k -> {
            List<Entry> out = new ArrayList<>();
            readInto(BASE + name + ".txt", "", Map.of(), out, true);
            if (out.isEmpty()) {
                throw new IllegalStateException(BASE + name + ".txt is missing or empty in the guardrails module");
            }
            return Collections.unmodifiableList(out);
        });
    }

    private static List<Entry> loadLocalized(String name, Map<String, String> substitutions) {
        Map<String, String> paths = new TreeMap<>((a, b) -> a.equals(b) ? 0 : DEFAULT_LOCALE.equals(a) ? -1 : DEFAULT_LOCALE.equals(b) ? 1 : a.compareTo(b));
        try {
            for (Resource r : new PathMatchingResourcePatternResolver(PatternSets.class.getClassLoader())
                    .getResources("classpath*:" + BASE + "*/" + name + ".txt")) {
                String url = r.getURL().toString();
                int end = url.lastIndexOf('/');
                int start = url.lastIndexOf('/', end - 1);
                paths.putIfAbsent(url.substring(start + 1, end), BASE + url.substring(start + 1, end) + "/" + name + ".txt");
            }
        } catch (IOException e) {
            log.error("Could not scan the pattern files of '{}': {}", name, e.toString());
        }
        paths.putIfAbsent(DEFAULT_LOCALE, BASE + DEFAULT_LOCALE + "/" + name + ".txt");
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, String> p : paths.entrySet()) {
            readInto(p.getValue(), p.getKey(), substitutions, out, DEFAULT_LOCALE.equals(p.getKey()));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(BASE + DEFAULT_LOCALE + "/" + name + ".txt is missing or empty in the guardrails module");
        }
        return Collections.unmodifiableList(out);
    }

    private static void readInto(String path, String locale, Map<String, String> substitutions, List<Entry> out, boolean mandatory) {
        String content;
        try (InputStream in = PatternSets.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                if (mandatory) {
                    throw new IllegalStateException(path + " is missing from the guardrails module");
                }
                return;
            }
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        int lineNo = 0;
        for (String raw : content.replace("\r\n", "\n").split("\n")) {
            lineNo++;
            if (raw.isBlank() || raw.startsWith("#")) {
                continue;
            }
            try {
                out.add(parse(raw, locale, substitutions));
            } catch (IllegalArgumentException e) {
                if (mandatory) {
                    throw new IllegalStateException(path + ":" + lineNo + ": " + e.getMessage(), e);
                }
                log.error("{}:{}: skipped, not a valid pattern: {}", path, lineNo, e.getMessage());
            }
        }
    }

    static Entry parse(String line, String locale, Map<String, String> substitutions) {
        String label = "";
        String regex = line;
        int sep = line.indexOf(LABEL_SEPARATOR);
        if (sep > 0) {
            label = line.substring(0, sep);
            regex = line.substring(sep + LABEL_SEPARATOR.length());
        }
        for (Map.Entry<String, String> s : substitutions.entrySet()) {
            regex = regex.replace("{" + s.getKey() + "}", s.getValue());
        }
        int flags = 0;
        var m = FLAG_GROUP.matcher(regex);
        if (m.find()) {
            for (char c : m.group(1).toCharArray()) {
                flags |= switch (c) {
                    case 'i' -> Pattern.CASE_INSENSITIVE;
                    case 'm' -> Pattern.MULTILINE;
                    case 's' -> Pattern.DOTALL;
                    case 'u' -> Pattern.UNICODE_CASE;
                    case 'x' -> Pattern.COMMENTS;
                    default -> 0;
                };
            }
            regex = regex.substring(m.end());
        }
        return new Entry(label, Pattern.compile(regex, flags), locale);
    }
}
