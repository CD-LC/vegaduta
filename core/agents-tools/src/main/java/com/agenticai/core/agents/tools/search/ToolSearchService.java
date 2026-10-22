package com.agenticai.core.agents.tools.search;

import com.agenticai.core.json.JsonMappers;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dependency-free BM25 ranker over a tool catalog: the "tool search" half of on-demand tool loading
 * (an agent with 100+ tools must not pay for every schema on every turn).
 *
 * <p>Lexical on purpose, like {@code ToolRelevanceSelector}: the catalog is at most a few thousand
 * short documents, the query is a handful of model-chosen keywords, and an embedding call per
 * search would add latency and a failure mode to a path that is meant to be cheap. The ranker is
 * the textbook Okapi BM25 (k1 = 1.2, b = 0.75; Robertson and Zaragoza, "The Probabilistic Relevance
 * Framework: BM25 and Beyond", 2009) with a field-weighted term frequency (BM25F-style linear
 * combination): a term in a tool's NAME counts {@value #NAME_WEIGHT}x, in its PARAMETER NAMES
 * {@value #PARAM_WEIGHT}x and in its DESCRIPTION once. Design reference for the surrounding
 * feature: Anthropic's "tool search tool" (regex/BM25 variants) and LangGraph BigTool - ideas only,
 * no code is shared.
 *
 * <p><b>Deterministic.</b> No clock, no randomness, no hash-order dependence: ties on score break
 * on the tool name (natural String order) and the logarithm is {@link StrictMath#log}, which is
 * bit-for-bit reproducible across JVMs unlike {@link Math#log}.
 *
 * <p><b>Exact-name lookup.</b> A query of the form {@code select:name1,name2} returns exactly those
 * tools (case-insensitive, in the requested order) and nothing else; a query that is itself one
 * tool's exact name puts that tool first. Everything else is ranked; tools that share no term with
 * the query are never returned (no zero-score padding).
 *
 * <p>Stateless and thread-safe: {@link Index} is immutable.
 */
public final class ToolSearchService {

    /**
     * Hard ceiling on results per search ({@code search_tools}'s documented {@code limit <= 8}).
     */
    public static final int MAX_LIMIT = 8;

    /** Results when the caller does not say. */
    public static final int DEFAULT_LIMIT = 5;

    static final int NAME_WEIGHT = 3;
    static final int PARAM_WEIGHT = 2;
    static final int DESC_WEIGHT = 1;

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final String SELECT_PREFIX = "select:";
    private static final int ONE_LINE_MAX = 160;
    private static final int PARAM_DEPTH_LIMIT = 3;
    private static final int PARAM_NAMES_PER_TOOL_LIMIT = 64;

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final Pattern CAMEL_BOUNDARY =
            Pattern.compile("(?<=[\\p{Ll}\\p{N}])(?=\\p{Lu})");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOPWORDS =
            Set.of(
                    "a", "an", "the", "is", "are", "was", "were", "be", "to", "of", "in", "on",
                    "for", "with", "and", "or", "it", "this", "that", "these", "those", "by", "as",
                    "at", "from", "can", "will", "use", "used", "using", "tool", "tools", "any",
                    "all", "your", "you", "i", "me", "my", "do", "does", "need", "want");

    /** A tool as the ranker sees it. {@code inputSchema} may be null or blank. */
    public record ToolEntry(String name, String description, String inputSchema) {

        public static ToolEntry of(ToolCallback callback) {
            ToolDefinition definition = callback.getToolDefinition();
            return new ToolEntry(
                    definition.name(), definition.description(), definition.inputSchema());
        }
    }

    /** One ranked result. {@code exact} is true for a name / {@code select:} lookup. */
    public record Hit(String name, String description, double score, boolean exact) {}

    /** Immutable, searchable view of a tool catalog. Build once per catalog with {@link #index}. */
    public static final class Index {

        private final List<Doc> docs;
        private final Map<String, Doc> byLowerName;
        private final Map<String, Integer> docFrequency;
        private final double averageLength;

        private Index(List<Doc> docs) {
            this.docs = Collections.unmodifiableList(docs);
            Map<String, Doc> names = new HashMap<>();
            Map<String, Integer> df = new HashMap<>();
            long totalLength = 0;
            for (Doc doc : docs) {
                names.put(doc.name.toLowerCase(Locale.ROOT), doc);
                for (String term : doc.termFrequency.keySet()) {
                    df.merge(term, 1, Integer::sum);
                }
                totalLength += doc.length;
            }
            this.byLowerName = names;
            this.docFrequency = df;
            this.averageLength =
                    docs.isEmpty() ? 1.0 : Math.max(1.0, (double) totalLength / docs.size());
        }

        public int size() {
            return docs.size();
        }

        public boolean contains(String name) {
            return name != null && byLowerName.containsKey(name.trim().toLowerCase(Locale.ROOT));
        }

        /**
         * Tool count per source for a "nothing matched" hint: MCP server name, or {@code built-in}.
         */
        public Map<String, Integer> sources() {
            Map<String, Integer> counts = new TreeMap<>();
            for (Doc doc : docs) {
                counts.merge(sourceOf(doc.name), 1, Integer::sum);
            }
            return counts;
        }

        /** The declared top-level parameter names of a tool (empty when unknown). */
        public List<String> parameterNames(String name) {
            Doc doc = name == null ? null : byLowerName.get(name.trim().toLowerCase(Locale.ROOT));
            return doc == null ? List.of() : doc.paramNames;
        }
    }

    private record Doc(
            String name,
            String description,
            List<String> paramNames,
            Map<String, Integer> termFrequency,
            int length) {}

    /** Builds an index; a repeated name keeps its first entry, blank names are skipped. */
    public Index index(Collection<ToolEntry> entries) {
        List<Doc> docs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (entries != null) {
            for (ToolEntry entry : entries) {
                if (entry == null || entry.name() == null || entry.name().isBlank()) {
                    continue;
                }
                if (!seen.add(entry.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                docs.add(toDoc(entry));
            }
        }
        return new Index(docs);
    }

    /** Convenience over {@link #index(Collection)} for a resolved callback list. */
    public Index indexCallbacks(Collection<? extends ToolCallback> callbacks) {
        List<ToolEntry> entries = new ArrayList<>();
        if (callbacks != null) {
            for (ToolCallback callback : callbacks) {
                entries.add(ToolEntry.of(callback));
            }
        }
        return index(entries);
    }

    /**
     * Ranked search. {@code limit} is clamped to {@code 1..}{@value #MAX_LIMIT} (non-positive means
     * {@value #DEFAULT_LIMIT}). A blank query, or one sharing no term with any tool, returns an
     * empty list.
     */
    public List<Hit> search(Index index, String query, int limit) {
        if (index == null || index.size() == 0 || query == null || query.isBlank()) {
            return List.of();
        }
        int max = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String trimmed = query.trim();

        if (trimmed.regionMatches(true, 0, SELECT_PREFIX, 0, SELECT_PREFIX.length())) {
            return selectExact(index, trimmed.substring(SELECT_PREFIX.length()), max);
        }

        List<Hit> hits = new ArrayList<>();
        Doc exact = index.byLowerName.get(trimmed.toLowerCase(Locale.ROOT));
        if (exact != null) {
            hits.add(new Hit(exact.name, exact.description, Double.MAX_VALUE, true));
        }
        for (Hit ranked : rank(index, tokenizeQuery(trimmed))) {
            if (hits.size() >= max) {
                break;
            }
            if (exact != null && ranked.name().equals(exact.name)) {
                continue;
            }
            hits.add(ranked);
        }
        return Collections.unmodifiableList(hits);
    }

    /** Exact (case-insensitive) lookup of one tool; empty when absent. */
    public java.util.Optional<Hit> lookup(Index index, String name) {
        if (index == null || name == null || name.isBlank()) {
            return java.util.Optional.empty();
        }
        Doc doc = index.byLowerName.get(name.trim().toLowerCase(Locale.ROOT));
        return doc == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(new Hit(doc.name, doc.description, Double.MAX_VALUE, true));
    }

    /**
     * First line / sentence of a description, whitespace-collapsed and capped for a result list.
     */
    public static String oneLine(String description) {
        if (description == null) {
            return "";
        }
        String collapsed = description.replaceAll("\\s+", " ").trim();
        int sentenceEnd = collapsed.indexOf(". ");
        if (sentenceEnd > 0 && sentenceEnd < ONE_LINE_MAX) {
            return collapsed.substring(0, sentenceEnd + 1);
        }
        return collapsed.length() <= ONE_LINE_MAX
                ? collapsed
                : collapsed.substring(0, ONE_LINE_MAX - 3) + "...";
    }

    // ------------------------------------------------------------------ internals

    private List<Hit> selectExact(Index index, String csv, int max) {
        List<Hit> hits = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (String raw : csv.split("[,\\s]+")) {
            String name = raw.trim();
            if (name.isEmpty() || hits.size() >= max) {
                continue;
            }
            Doc doc = index.byLowerName.get(name.toLowerCase(Locale.ROOT));
            if (doc != null && taken.add(doc.name)) {
                hits.add(new Hit(doc.name, doc.description, Double.MAX_VALUE, true));
            }
        }
        return Collections.unmodifiableList(hits);
    }

    private List<Hit> rank(Index index, List<String> queryTokens) {
        Set<String> terms = new HashSet<>(queryTokens);
        if (terms.isEmpty()) {
            return List.of();
        }
        int docCount = index.docs.size();
        List<Hit> scored = new ArrayList<>();
        for (Doc doc : index.docs) {
            double score = 0.0;
            for (String term : terms) {
                Integer tf = doc.termFrequency.get(term);
                if (tf == null) {
                    continue;
                }
                int df = index.docFrequency.getOrDefault(term, 0);
                double idf = StrictMath.log(1.0 + (docCount - df + 0.5) / (df + 0.5));
                double norm = tf + K1 * (1.0 - B + B * doc.length / index.averageLength);
                score += idf * (tf * (K1 + 1.0)) / norm;
            }
            if (score > 0.0) {
                scored.add(new Hit(doc.name, doc.description, score, false));
            }
        }
        scored.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::name));
        return scored;
    }

    private static Doc toDoc(ToolEntry entry) {
        Map<String, Integer> tf = new LinkedHashMap<>();
        int length = 0;
        for (String token : tokenize(entry.name(), true)) {
            tf.merge(token, NAME_WEIGHT, Integer::sum);
            length += NAME_WEIGHT;
        }
        List<String> paramNames = parameterNames(entry.inputSchema());
        for (String param : paramNames) {
            for (String token : tokenize(param, true)) {
                tf.merge(token, PARAM_WEIGHT, Integer::sum);
                length += PARAM_WEIGHT;
            }
        }
        for (String token : tokenize(entry.description(), false)) {
            tf.merge(token, DESC_WEIGHT, Integer::sum);
            length += DESC_WEIGHT;
        }
        return new Doc(
                entry.name(),
                entry.description() == null ? "" : entry.description(),
                List.copyOf(paramNames),
                tf,
                Math.max(1, length));
    }

    /**
     * Query terms: the text tokenized both without and with camelCase splitting, so a model that
     * types an identifier ({@code createInvoice}) matches the split name terms and one that types a
     * brand ({@code GitHub}) still matches the whole word in a description. Duplicates are harmless
     * ({@link #rank} works on the distinct set).
     */
    static List<String> tokenizeQuery(String text) {
        List<String> tokens = new ArrayList<>(tokenize(text, false));
        tokens.addAll(tokenize(text, true));
        return tokens;
    }

    /**
     * Lower-cased, underscore/punctuation-split, stop-worded, lightly stemmed terms. {@code
     * splitCamelCase} is for identifiers (tool and parameter names); it is off for prose, where it
     * would cut a brand such as "GitHub" or "YouTube" into meaningless halves.
     */
    static List<String> tokenize(String text, boolean splitCamelCase) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        String split = splitCamelCase ? CAMEL_BOUNDARY.matcher(text).replaceAll(" ") : text;
        Matcher matcher = TOKEN.matcher(split.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group();
            if (STOPWORDS.contains(token)) {
                continue;
            }
            String stemmed = stem(token);
            if (stemmed.length() < 2 && !Character.isDigit(stemmed.charAt(0))) {
                continue;
            }
            tokens.add(stemmed);
        }
        return tokens;
    }

    /**
     * Plural folding only ("emails" -> "email", "queries" -> "query"); applied to documents and
     * queries alike.
     */
    private static String stem(String token) {
        int n = token.length();
        if (n > 4 && token.endsWith("ies")) {
            return token.substring(0, n - 3) + "y";
        }
        if (n > 3
                && token.endsWith("s")
                && !token.endsWith("ss")
                && !token.endsWith("us")
                && !token.endsWith("is")) {
            return token.substring(0, n - 1);
        }
        return token;
    }

    private static List<String> parameterNames(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = MAPPER.readTree(inputSchema);
            List<String> names = new ArrayList<>();
            collectProperties(root, 0, names);
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static void collectProperties(JsonNode node, int depth, List<String> out) {
        if (node == null
                || !node.isObject()
                || depth >= PARAM_DEPTH_LIMIT
                || out.size() >= PARAM_NAMES_PER_TOOL_LIMIT) {
            return;
        }
        JsonNode properties = node.get("properties");
        if (properties == null || !properties.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext() && out.size() < PARAM_NAMES_PER_TOOL_LIMIT) {
            Map.Entry<String, JsonNode> field = fields.next();
            out.add(field.getKey());
            collectProperties(field.getValue(), depth + 1, out);
        }
    }

    private static String sourceOf(String toolName) {
        if (toolName.startsWith("mcp__")) {
            int end = toolName.indexOf("__", 5);
            if (end > 5) {
                return toolName.substring(5, end);
            }
        }
        return "built-in";
    }
}
