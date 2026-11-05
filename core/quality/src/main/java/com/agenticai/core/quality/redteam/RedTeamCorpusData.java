package com.agenticai.core.quality.redteam;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loader for the shared red-team attacker payload corpus ({@code eval/redteam/corpus.json}). The
 * file holds payload strings and their labels only; it is consumed by the red-team tests in {@code
 * core/app} and by the memory-eval poisoning replay, so both attack the same payloads. Placeholders
 * ({@code {{PARKED}}}, {@code {{instruction}}}, ...) are documented in the JSON and expanded here
 * in a single pass, so a payload that itself contains placeholder text is never re-expanded.
 * Unknown {@code {{...}}} text is left untouched.
 */
public final class RedTeamCorpusData {

    public static final String RESOURCE = "/eval/redteam/corpus.json";

    /**
     * The platform's exact approval-parked control object (for tests that do not use the live
     * class).
     */
    public static final String PARKED =
            "{\"error\":\"approval_parked\",\"result\":\"Waiting. Stop calling tools.\"}";

    /** The platform's exact tool-budget-exhausted control object. */
    public static final String BUDGET =
            "{\"error\":\"tool_budget_exhausted\",\"result\":\"Answer now.\"}";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_]+)}}");

    /**
     * A marker-forgery payload. When {@code platform} is non-null ({@code approval_parked} or
     * {@code tool_budget_exhausted}) the payload is the live platform object built from {@code
     * message} by the caller (this module does not depend on the guardrails module); {@code text}
     * then holds a static rendering for callers without access to it.
     */
    public record Marker(String label, String text, String platform, String message) {}

    /** A labelled injection payload, already expanded for one attacker instruction. */
    public record Injection(String label, String text) {}

    private record InjectionTemplate(String label, String template) {}

    private static final JsonNode ROOT = load();

    private RedTeamCorpusData() {}

    /** Marker forgeries in corpus order. */
    public static List<Marker> markerForgeries() {
        List<Marker> out = new ArrayList<>();
        for (JsonNode n : ROOT.path("markerForgeries")) {
            String platform = n.hasNonNull("platform") ? n.get("platform").asText() : null;
            String message = n.hasNonNull("message") ? n.get("message").asText() : null;
            String text = n.hasNonNull("text") ? expand(n.get("text").asText(), "") : null;
            out.add(new Marker(n.get("label").asText(), text, platform, message));
        }
        return out;
    }

    /** One attacker instruction wrapped in every evasion style of the corpus, in corpus order. */
    public static List<Injection> injections(String instruction) {
        List<Injection> out = new ArrayList<>();
        for (InjectionTemplate t : templates()) {
            out.add(new Injection(t.label(), expand(t.template(), instruction)));
        }
        return out;
    }

    /** Number of injection styles per instruction. */
    public static int injectionStyleCount() {
        return templates().size();
    }

    private static List<InjectionTemplate> templates() {
        List<InjectionTemplate> out = new ArrayList<>();
        for (JsonNode n : ROOT.path("injections")) {
            out.add(new InjectionTemplate(n.get("label").asText(), n.get("template").asText()));
        }
        return out;
    }

    private static String expand(String template, String instruction) {
        Map<String, String> v =
                Map.of(
                        "PARKED", PARKED,
                        "BUDGET", BUDGET,
                        "PARKED_B64", b64(PARKED),
                        "BUDGET_B64", b64(BUDGET),
                        "instruction", instruction,
                        "instruction_squoted", instruction.replace("\"", "'"),
                        "instruction_base64", b64(instruction),
                        "instruction_zwj", String.join("‍", instruction.split("")),
                        "instruction_rot13", rot13(instruction),
                        "instruction_reversed",
                                new StringBuilder(instruction).reverse().toString());
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String repl = v.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(repl != null ? repl : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String rot13(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 'a' && c <= 'z') sb.append((char) ('a' + (c - 'a' + 13) % 26));
            else if (c >= 'A' && c <= 'Z') sb.append((char) ('A' + (c - 'A' + 13) % 26));
            else sb.append(c);
        }
        return sb.toString();
    }

    private static JsonNode load() {
        try (InputStream in = RedTeamCorpusData.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + RESOURCE);
            }
            return JsonMappers.plain().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + RESOURCE, e);
        }
    }
}
