package com.agenticai.core.agents.tools.platformguide;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * What the Assistant knows about setting this platform up: the topic catalogue and the bundled
 * documentation, both read from the classpath so every install (a container with no {@code docs/}
 * folder included) answers from the same text. Immutable once built; ranking is plain keyword
 * scoring so a small model gets a short, relevant list rather than a document dump.
 */
public final class PlatformGuide {

    private static final String BASE = "platform-guide/";
    private static final int MIN_TOPIC_SCORE = 3;
    private static final int MAX_EXCERPT = 700;
    private static final Set<String> STOP =
            Set.of(
                    "the", "and", "for", "how", "can", "you", "with", "this", "that", "from",
                    "into", "what", "why", "does", "not", "are", "use", "using", "want", "need",
                    "please", "have", "has", "set", "setup", "get", "make", "its", "our", "your",
                    "any", "some", "about", "work", "working", "way", "tell", "show", "help");

    /** A heading-delimited slice of a bundled doc. */
    public record DocSection(String doc, String heading, String text) {}

    public record DocHit(String doc, String heading, String excerpt, int score) {}

    public record TopicHit(SetupTopic topic, int score) {}

    private final List<SetupTopic> topics;
    private final Map<String, SetupTopic> byId = new LinkedHashMap<>();
    private final List<DocSection> sections;

    PlatformGuide(List<SetupTopic> topics, List<DocSection> sections) {
        this.topics = List.copyOf(topics);
        this.sections = List.copyOf(sections);
        for (SetupTopic t : topics) {
            byId.put(t.id(), t);
        }
    }

    public static PlatformGuide load(ObjectMapper mapper) throws IOException {
        List<SetupTopic> topics;
        try (InputStream in =
                PlatformGuide.class.getClassLoader().getResourceAsStream(BASE + "topics.json")) {
            if (in == null) {
                throw new IOException("platform-guide/topics.json is missing from the classpath");
            }
            topics = mapper.readValue(in, new TypeReference<List<SetupTopic>>() {});
        }
        List<DocSection> sections = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver(PlatformGuide.class.getClassLoader());
        for (Resource r : resolver.getResources("classpath*:" + BASE + "docs/**/*.md")) {
            String url = r.getURL().toString();
            String doc = url.substring(url.indexOf(BASE + "docs/") + (BASE + "docs/").length());
            try (InputStream in = r.getInputStream()) {
                sections.addAll(split(doc, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        return new PlatformGuide(topics, sections);
    }

    static List<DocSection> split(String doc, String markdown) {
        List<DocSection> out = new ArrayList<>();
        String title = doc;
        String heading = null;
        StringBuilder body = new StringBuilder();
        boolean inFence = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("```")) {
                inFence = !inFence;
            }
            boolean isHeading = !inFence && line.startsWith("#");
            if (isHeading && line.startsWith("# ") && heading == null && body.isEmpty()) {
                title = line.substring(2).trim();
                heading = title;
                continue;
            }
            if (isHeading && (line.startsWith("## ") || line.startsWith("### "))) {
                flush(out, doc, heading == null ? title : heading, body);
                heading = line.replaceFirst("^#+\\s*", "").trim();
                continue;
            }
            body.append(line).append('\n');
        }
        flush(out, doc, heading == null ? title : heading, body);
        return out;
    }

    private static void flush(
            List<DocSection> out, String doc, String heading, StringBuilder body) {
        String text = body.toString().strip();
        if (!text.isEmpty()) {
            out.add(new DocSection(doc, heading, text));
        }
        body.setLength(0);
    }

    public List<SetupTopic> topics() {
        return topics;
    }

    public Optional<SetupTopic> topic(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    public int docSectionCount() {
        return sections.size();
    }

    static List<String> terms(String query) {
        if (query == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String t : query.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
            if (t.length() >= 2 && !STOP.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Topics whose keywords, title or summary fit the request, best first. */
    public List<TopicHit> rankTopics(String query, int limit) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<String> terms = terms(query);
        List<TopicHit> hits = new ArrayList<>();
        for (SetupTopic t : topics) {
            int score = 0;
            for (String k : t.keywords()) {
                if (q.contains(k)) {
                    score += k.contains(" ") ? 8 : 6;
                } else {
                    for (String term : terms) {
                        if (k.equals(term)) {
                            score += 4;
                        } else if (term.length() >= 4 && k.contains(term)) {
                            score += 2;
                        }
                    }
                }
            }
            String title = t.title().toLowerCase(Locale.ROOT);
            String summary = t.summary().toLowerCase(Locale.ROOT);
            for (String term : terms) {
                if (title.contains(term)) {
                    score += 2;
                }
                if (summary.contains(term)) {
                    score += 1;
                }
            }
            if (score >= MIN_TOPIC_SCORE) {
                hits.add(new TopicHit(t, score));
            }
        }
        hits.sort(
                Comparator.comparingInt(TopicHit::score)
                        .reversed()
                        .thenComparing(h -> h.topic().id()));
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    /**
     * Doc sections that mention the request's words, best first (a heading match counts triple).
     */
    public List<DocHit> searchDocs(String query, int limit) {
        List<String> terms = terms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<DocHit> hits = new ArrayList<>();
        for (DocSection s : sections) {
            String heading = s.heading().toLowerCase(Locale.ROOT);
            String text = s.text().toLowerCase(Locale.ROOT);
            int score = 0;
            int matched = 0;
            for (String term : terms) {
                int h = heading.contains(term) ? 3 : 0;
                int b = text.contains(term) ? 1 : 0;
                if (h + b > 0) {
                    matched++;
                }
                score += h + b;
            }
            if (matched == 0 || (terms.size() > 1 && matched < 2)) {
                continue;
            }
            hits.add(new DocHit(s.doc(), s.heading(), excerpt(s.text(), terms), score));
        }
        hits.sort(Comparator.comparingInt(DocHit::score).reversed().thenComparing(DocHit::doc));
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    private static String excerpt(String text, List<String> terms) {
        if (text.length() <= MAX_EXCERPT) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int at = -1;
        for (String term : terms) {
            int i = lower.indexOf(term);
            if (i >= 0 && (at < 0 || i < at)) {
                at = i;
            }
        }
        int from = Math.max(0, Math.min(at < 0 ? 0 : at - 120, text.length() - MAX_EXCERPT));
        int to = Math.min(text.length(), from + MAX_EXCERPT);
        return (from > 0 ? "..." : "")
                + text.substring(from, to).strip()
                + (to < text.length() ? "..." : "");
    }
}
