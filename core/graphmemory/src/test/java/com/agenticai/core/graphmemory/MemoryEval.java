package com.agenticai.core.graphmemory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.ai.chat.client.ChatClient;

/**
 * <b>LongMemEval-lite (own fixtures)</b> - a deterministic retrieval/QA evaluation of the graph
 * memory write path (plain {@link GraphMemoryService#remember} vs {@link
 * MemoryWriteArbitrationService}) over its read path ({@link GraphMemoryService#recallRelatedAt}).
 *
 * <p><b>This is NOT the public LongMemEval benchmark</b> and its numbers are not comparable to it.
 * It borrows the five ability categories (single-session recall, multi-session aggregation,
 * knowledge update, temporal reasoning, abstention) and nothing else: the fixtures are our own,
 * small, and hand-written; facts are GOLD-EXTRACTED triples (the extraction LLM is not measured);
 * the question frame (entity / relation / direction / as-of session) is supplied by the fixture
 * (question understanding is not measured); the default {@link Answerer} is extractive and
 * deterministic (an LLM answerer's reading/hallucination behaviour is not measured). What IS
 * measured is storage, update handling and retrieval: did the memory still hold the right facts,
 * did stale facts get retired, did the as-of read return the right past state, did spurious facts
 * leak into answers.
 *
 * <p>Runs against any {@link GraphMemoryService}: {@link InMemoryTemporalGraphMemory} (default, no
 * database) or the real AGE-backed one (see {@code GraphMemoryRealAgeTest} and {@link
 * MemoryEvalRunner}). Arbitration decisions come from {@link ScriptedChats} (an ideal and a naive
 * rule-based stand-in for the LLM), so the ON numbers are what the pipeline achieves GIVEN those
 * decisions, not what a real model would decide.
 */
final class MemoryEval {

    static final String FIXTURE_NAME = "LongMemEval-lite (own fixtures)";
    static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-00000000e7a1");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int ANSWER_TOP_K = 10;
    private static final List<Integer> RECALL_KS = List.of(3, 5);

    private MemoryEval() {}

    // ------------------------------------------------------------------ model

    record Session(
            String id, long at, List<String> say, List<String> forget, List<GraphTriple> facts) {}

    record Question(
            String text,
            String entity,
            String relation,
            String kind,
            boolean inbound,
            int hops,
            String asOfSession,
            List<String> expected,
            List<String> gold,
            List<String> stale,
            boolean abstain) {}

    record EvalCase(String id, String category, List<Session> sessions, Question question) {}

    record Fixture(String name, Set<String> multiValued, List<EvalCase> cases) {}

    record Answer(List<String> values, boolean abstained) {}

    /**
     * Turns the retrieved facts into an answer. The default is {@link ExtractiveAnswerer}; an LLM
     * answerer (prompt = question + facts, parse the reply, abstain on "not in memory") is the
     * obvious next implementation and is deliberately NOT written here - this seam exists so it can
     * be added without touching the harness.
     */
    interface Answerer {
        Answer answer(Question question, List<GraphFact> retrieved);
    }

    /**
     * Reads the answer straight off the retrieved facts matching the question frame; abstains on
     * none.
     */
    static final class ExtractiveAnswerer implements Answerer {
        @Override
        public Answer answer(Question q, List<GraphFact> retrieved) {
            Set<String> seen = new LinkedHashSet<>();
            List<String> values = new ArrayList<>();
            for (GraphFact f : retrieved) {
                if (!f.relation().equalsIgnoreCase(q.relation())) {
                    continue;
                }
                String anchor = q.inbound() ? f.object() : f.subject();
                String value = q.inbound() ? f.subject() : f.object();
                if (anchor.equalsIgnoreCase(q.entity())
                        && seen.add(value.toLowerCase(Locale.ROOT))) {
                    values.add(value);
                }
            }
            if (values.isEmpty()) {
                return new Answer(List.of(), true);
            }
            if ("count".equals(q.kind())) {
                return new Answer(List.of(String.valueOf(values.size())), false);
            }
            return new Answer(values, false);
        }
    }

    enum Mode {
        OFF("arbitration OFF (plain remember)"),
        ON_IDEAL("arbitration ON, ideal arbitrator"),
        ON_NAIVE("arbitration ON, naive arbitrator (every relation single-valued)");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    record CaseResult(
            String caseId,
            String category,
            boolean answerable,
            boolean correct,
            boolean hit3,
            boolean hit5,
            boolean hasStale,
            boolean staleLeak,
            boolean hallucinated,
            List<String> answer) {
        /** Parity key used to compare two backends: everything that is not timing. */
        String signature() {
            return caseId
                    + "|"
                    + correct
                    + "|"
                    + hit3
                    + "|"
                    + hit5
                    + "|"
                    + staleLeak
                    + "|"
                    + hallucinated
                    + "|"
                    + answer;
        }
    }

    // ------------------------------------------------------------------ fixture

    static Fixture loadFixture() {
        try (InputStream in =
                MemoryEval.class.getResourceAsStream("/memeval/longmemeval-lite.json")) {
            if (in == null) {
                throw new IllegalStateException(
                        "fixture /memeval/longmemeval-lite.json not on classpath");
            }
            JsonNode root = MAPPER.readTree(in);
            Set<String> multi = new LinkedHashSet<>();
            root.get("multi").forEach(n -> multi.add(n.asText()));
            List<EvalCase> cases = new ArrayList<>();
            for (JsonNode c : root.get("cases")) {
                List<Session> sessions = new ArrayList<>();
                for (JsonNode s : c.get("sessions")) {
                    List<GraphTriple> facts = new ArrayList<>();
                    s.path("facts")
                            .forEach(
                                    t ->
                                            facts.add(
                                                    new GraphTriple(
                                                            t.get(0).asText(),
                                                            t.get(1).asText(),
                                                            t.get(2).asText())));
                    sessions.add(
                            new Session(
                                    s.get("id").asText(),
                                    LocalDate.parse(s.get("t").asText())
                                            .atStartOfDay()
                                            .toInstant(ZoneOffset.UTC)
                                            .toEpochMilli(),
                                    strings(s.path("say")),
                                    strings(s.path("forget")),
                                    facts));
                }
                JsonNode q = c.get("q");
                List<String> expected = strings(q.path("exp"));
                List<String> gold = q.has("gold") ? strings(q.get("gold")) : expected;
                cases.add(
                        new EvalCase(
                                c.get("id").asText(),
                                c.get("cat").asText(),
                                sessions,
                                new Question(
                                        q.get("text").asText(),
                                        q.get("ent").asText(),
                                        q.get("rel").asText(),
                                        q.path("kind").asText("lookup"),
                                        "in".equals(q.path("dir").asText("out")),
                                        q.path("hops").asInt(1),
                                        q.has("asof") ? q.get("asof").asText() : null,
                                        expected,
                                        gold,
                                        strings(q.path("stale")),
                                        q.path("abstain").asBoolean(false))));
            }
            return new Fixture(root.get("name").asText(), multi, cases);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    // ------------------------------------------------------------------ run

    /**
     * Runs every mode against {@code store}; each case uses (and afterwards erases) its own subject
     * key.
     */
    static Map<Mode, List<CaseResult>> runAll(
            Fixture fixture, GraphMemoryService store, Answerer answerer, String runId) {
        Map<Mode, List<CaseResult>> out = new EnumMap<>(Mode.class);
        for (Mode mode : Mode.values()) {
            out.put(mode, run(fixture, mode, store, answerer, runId));
        }
        return out;
    }

    static List<CaseResult> run(
            Fixture fixture, Mode mode, GraphMemoryService store, Answerer answerer, String runId) {
        MemoryWriteArbitrationService arbitration = new MemoryWriteArbitrationService();
        arbitration.setEnabled(mode != Mode.OFF);
        ChatClient chat = ScriptedChats.rules(fixture.multiValued(), mode == Mode.ON_NAIVE);
        List<CaseResult> results = new ArrayList<>();
        for (EvalCase c : fixture.cases()) {
            String subjectKey = runId + ":" + mode.name() + ":" + c.id();
            try {
                for (Session s : c.sessions()) {
                    store.setClock(() -> s.at());
                    for (String text : s.forget()) {
                        store.forgetFactText(subjectKey, AGENT, text);
                    }
                    if (!s.facts().isEmpty()) {
                        arbitration.arbitrateAndWrite(chat, store, AGENT, subjectKey, s.facts());
                    }
                }
                results.add(evaluate(c, store, answerer, subjectKey));
            } finally {
                store.setClock(null);
                store.eraseSubject(subjectKey);
            }
        }
        return results;
    }

    private static CaseResult evaluate(
            EvalCase c, GraphMemoryService store, Answerer answerer, String subjectKey) {
        Question q = c.question();
        Instant asOf = null;
        if (q.asOfSession() != null) {
            long at =
                    c.sessions().stream()
                            .filter(s -> s.id().equals(q.asOfSession()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    c.id()
                                                            + ": unknown asof session "
                                                            + q.asOfSession()))
                            .at();
            asOf =
                    Instant.ofEpochMilli(
                            at + 12L * 3600_000L); // just after that session, before the next one
        }
        List<GraphFact> ranked =
                rank(q, store.recallRelatedAt(AGENT, subjectKey, q.entity(), q.hops(), asOf));
        Answer answer =
                answerer.answer(q, ranked.subList(0, Math.min(ANSWER_TOP_K, ranked.size())));

        boolean correct;
        if (q.abstain()) {
            correct = answer.abstained();
        } else {
            correct = !answer.abstained() && lower(answer.values()).equals(lower(q.expected()));
        }
        boolean hit3 = false;
        boolean hit5 = false;
        if (!q.abstain()) {
            hit3 = goldIn(q, ranked, 3);
            hit5 = goldIn(q, ranked, 5);
        }
        boolean hasStale = !q.stale().isEmpty();
        boolean leak =
                hasStale
                        && !answer.abstained()
                        && lower(answer.values()).stream()
                                .anyMatch(v -> lower(q.stale()).contains(v));
        boolean hallucinated = q.abstain() && !answer.abstained();
        return new CaseResult(
                c.id(),
                c.category(),
                !q.abstain(),
                correct,
                hit3,
                hit5,
                hasStale,
                leak,
                hallucinated,
                answer.values());
    }

    private static boolean goldIn(Question q, List<GraphFact> ranked, int k) {
        List<GraphFact> top = ranked.subList(0, Math.min(k, ranked.size()));
        for (String g : q.gold()) {
            boolean found =
                    top.stream()
                            .anyMatch(
                                    f ->
                                            f.relation().equalsIgnoreCase(q.relation())
                                                    && (q.inbound()
                                                            ? f.object()
                                                                            .equalsIgnoreCase(
                                                                                    q.entity())
                                                                    && f.subject()
                                                                            .equalsIgnoreCase(g)
                                                            : f.subject()
                                                                            .equalsIgnoreCase(
                                                                                    q.entity())
                                                                    && f.object()
                                                                            .equalsIgnoreCase(g)));
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> lower(List<String> values) {
        Set<String> out = new TreeSet<>();
        values.forEach(v -> out.add(v.toLowerCase(Locale.ROOT)));
        return out;
    }

    // ------------------------------------------------------------------ ranking (retrieval
    // quality)

    private static final Set<String> STOP =
            Set.of(
                    "do",
                    "i",
                    "my",
                    "the",
                    "what",
                    "which",
                    "where",
                    "who",
                    "how",
                    "is",
                    "are",
                    "was",
                    "were",
                    "did",
                    "in",
                    "of",
                    "a",
                    "an",
                    "have",
                    "has",
                    "had",
                    "to",
                    "me",
                    "now",
                    "these",
                    "days",
                    "currently",
                    "before",
                    "after",
                    "between",
                    "and",
                    "many",
                    "much",
                    "does",
                    "at",
                    "first",
                    "when",
                    "told",
                    "you",
                    "about",
                    "our",
                    "two",
                    "become",
                    "became");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");

    /**
     * Lexical relevance of each retrieved fact to the question text, ties broken alphabetically
     * (deterministic across backends).
     */
    static List<GraphFact> rank(Question q, List<GraphFact> facts) {
        Set<String> questionStems = stems(q.text());
        List<GraphFact> sorted = new ArrayList<>(new LinkedHashSet<>(facts));
        sorted.sort(
                Comparator.comparingInt((GraphFact f) -> -score(questionStems, f))
                        .thenComparing(GraphFact::asSentence));
        return sorted;
    }

    private static int score(Set<String> questionStems, GraphFact f) {
        Set<String> factStems =
                stems((f.subject() + " " + f.relation().replace('_', ' ') + " " + f.object()));
        int score = 0;
        for (String s : questionStems) {
            if (factStems.contains(s)) {
                score++;
            }
        }
        return score;
    }

    private static Set<String> stems(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : NON_WORD.split(text.toLowerCase(Locale.ROOT))) {
            if (w.isBlank() || STOP.contains(w)) {
                continue;
            }
            out.add(stem(w));
        }
        return out;
    }

    private static String stem(String w) {
        if (w.endsWith("ies") && w.length() > 4) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.endsWith("ing") && w.length() > 5) {
            return w.substring(0, w.length() - 3);
        }
        if (w.endsWith("ed") && w.length() > 4) {
            return w.substring(0, w.length() - 2);
        }
        if (w.endsWith("es") && w.length() > 4) {
            return w.substring(0, w.length() - 2);
        }
        if (w.endsWith("s") && w.length() > 3) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    // ------------------------------------------------------------------ report

    record Row(int n, int correct, int answerable, int hit3, int hit5) {}

    private static Map<String, Row> byCategory(List<CaseResult> results) {
        Map<String, int[]> acc = new LinkedHashMap<>();
        for (CaseResult r : results) {
            int[] a = acc.computeIfAbsent(r.category(), k -> new int[5]);
            a[0]++;
            a[1] += r.correct() ? 1 : 0;
            a[2] += r.answerable() ? 1 : 0;
            a[3] += r.hit3() ? 1 : 0;
            a[4] += r.hit5() ? 1 : 0;
        }
        Map<String, Row> out = new LinkedHashMap<>();
        acc.forEach((k, a) -> out.put(k, new Row(a[0], a[1], a[2], a[3], a[4])));
        return out;
    }

    private static String pct(int num, int den) {
        return den == 0 ? "  n/a" : String.format(Locale.ROOT, "%5.1f", 100.0 * num / den);
    }

    static double accuracy(List<CaseResult> results, String category) {
        int n = 0;
        int ok = 0;
        for (CaseResult r : results) {
            if (category == null || r.category().equals(category)) {
                n++;
                ok += r.correct() ? 1 : 0;
            }
        }
        return n == 0 ? 0 : (double) ok / n;
    }

    static double staleLeakRate(List<CaseResult> results) {
        int n = 0;
        int leaks = 0;
        for (CaseResult r : results) {
            if (r.hasStale()) {
                n++;
                leaks += r.staleLeak() ? 1 : 0;
            }
        }
        return n == 0 ? 0 : (double) leaks / n;
    }

    static double hallucinationRate(List<CaseResult> results) {
        int n = 0;
        int bad = 0;
        for (CaseResult r : results) {
            if (!r.answerable()) {
                n++;
                bad += r.hallucinated() ? 1 : 0;
            }
        }
        return n == 0 ? 0 : (double) bad / n;
    }

    static String report(String backend, Map<Mode, List<CaseResult>> all) {
        StringBuilder sb = new StringBuilder();
        sb.append(FIXTURE_NAME).append(" - backend: ").append(backend).append("\n");
        sb.append(
                        "(own fixtures; NOT the public LongMemEval benchmark; gold-extracted facts, extractive answerer,")
                .append(" scripted arbitrator - see MemoryEval javadoc)\n");
        for (Map.Entry<Mode, List<CaseResult>> e : all.entrySet()) {
            List<CaseResult> rs = e.getValue();
            sb.append("\n== ").append(e.getKey().label).append(" ==\n");
            sb.append(
                    String.format(
                            Locale.ROOT,
                            "%-28s %3s %9s %10s %10s%n",
                            "category",
                            "n",
                            "acc %",
                            "recall@3 %",
                            "recall@5 %"));
            Map<String, Row> rows = byCategory(rs);
            rows.forEach(
                    (cat, row) ->
                            sb.append(
                                    String.format(
                                            Locale.ROOT,
                                            "%-28s %3d %9s %10s %10s%n",
                                            cat,
                                            row.n(),
                                            pct(row.correct(), row.n()),
                                            pct(row.hit3(), row.answerable()),
                                            pct(row.hit5(), row.answerable()))));
            int n = rs.size();
            int ok = (int) rs.stream().filter(CaseResult::correct).count();
            int answerable = (int) rs.stream().filter(CaseResult::answerable).count();
            int h3 = (int) rs.stream().filter(CaseResult::hit3).count();
            int h5 = (int) rs.stream().filter(CaseResult::hit5).count();
            double macro =
                    rows.keySet().stream().mapToDouble(c -> accuracy(rs, c)).average().orElse(0)
                            * 100;
            sb.append(
                    String.format(
                            Locale.ROOT,
                            "%-28s %3d %9s %10s %10s%n",
                            "OVERALL (micro)",
                            n,
                            pct(ok, n),
                            pct(h3, answerable),
                            pct(h5, answerable)));
            sb.append(
                    String.format(
                            Locale.ROOT,
                            "category-averaged accuracy: %.1f%%   update-correctness (knowledge_update): %.1f%%   "
                                    + "stale-value leak rate (cases with a stale value): %.1f%%   abstention accuracy: %.1f%%   hallucination rate: %.1f%%%n",
                            macro,
                            accuracy(rs, "knowledge_update") * 100,
                            staleLeakRate(rs) * 100,
                            accuracy(rs, "abstention") * 100,
                            hallucinationRate(rs) * 100));
        }
        List<CaseResult> off = all.get(Mode.OFF);
        if (off != null) {
            for (Mode m : List.of(Mode.ON_IDEAL, Mode.ON_NAIVE)) {
                List<CaseResult> on = all.get(m);
                if (on == null) {
                    continue;
                }
                sb.append("\n== delta: ")
                        .append(m.label)
                        .append(" minus OFF (percentage points of accuracy) ==\n");
                Map<String, Row> rows = byCategory(on);
                for (String cat : rows.keySet()) {
                    sb.append(
                            String.format(
                                    Locale.ROOT,
                                    "%-28s %+6.1f%n",
                                    cat,
                                    (accuracy(on, cat) - accuracy(off, cat)) * 100));
                }
                sb.append(
                        String.format(
                                Locale.ROOT,
                                "%-28s %+6.1f   (stale-leak rate %+.1f pp)%n",
                                "OVERALL (micro)",
                                (accuracy(on, null) - accuracy(off, null)) * 100,
                                (staleLeakRate(on) - staleLeakRate(off)) * 100));
                List<String> flipped = new ArrayList<>();
                for (int i = 0; i < on.size(); i++) {
                    if (on.get(i).correct() != off.get(i).correct()) {
                        flipped.add(on.get(i).caseId() + (on.get(i).correct() ? "(+)" : "(-)"));
                    }
                }
                sb.append("cases that changed: ").append(flipped).append("\n");
            }
        }
        return sb.toString();
    }
}
