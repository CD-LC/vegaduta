package com.agenticai.core.graphmemory;

import static com.agenticai.core.graphmemory.GraphFactTextMatcher.edgesOnlyThisFactSupports;

import com.agenticai.core.graphmemory.GraphFactTextMatcher.ValidEdge;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory stand-in for the AGE-backed {@link GraphMemoryService} with the same bi-temporal
 * semantics the real one has after the 2026-10-05 real-AGE verification (see that class's javadoc):
 * vertices are identified by exact name per (tier, subject key); an edge is OPEN until {@code
 * valid_to} is stamped; a write only skips when an OPEN edge for the same fact exists (names
 * compared case-insensitively); supersede closes open edges with the same subject+relation and a
 * different object using the SAME timestamp as the new edge's {@code valid_from}; recall enumerates
 * edge-simple paths of 1..hops edges from every vertex whose name contains the hint and requires
 * every edge on the path to be valid.
 *
 * <p>Used by the arbitration harness-style tests and by the LongMemEval-lite eval when no real AGE
 * is available. {@code GraphMemoryRealAgeTest} asserts the eval gives identical per-case results on
 * both, which is what justifies using this stand-in at all.
 */
class InMemoryTemporalGraphMemory extends GraphMemoryService {

    private record Edge(String tier, String subjectKey, String subject, String relation, String object, long from, Long[] to) {
        boolean open() {
            return to[0] == null;
        }
    }

    private final List<Edge> edges = new ArrayList<>();

    InMemoryTemporalGraphMemory() {
        super(null);
    }

    /** Raw edge dump for assertions: {@code subject|RELATION|object|from|to}. */
    List<String> dump(String subjectKey) {
        List<String> out = new ArrayList<>();
        for (Edge e : edges) {
            if (e.subjectKey().equals(subjectKey)) {
                out.add(e.subject() + "|" + e.relation() + "|" + e.object() + "|" + e.from() + "|" + e.to()[0]);
            }
        }
        return out;
    }

    @Override
    public void remember(UUID agentId, String subjectKey, List<GraphTriple> triples) {
        if (triples == null) {
            return;
        }
        for (GraphTriple t : triples) {
            write(agentId.toString(), subjectKey, t, nowMillis());
        }
    }

    @Override
    public void rememberShared(String subjectKey, List<GraphTriple> triples) {
        if (triples == null) {
            return;
        }
        for (GraphTriple t : triples) {
            write(SHARED_AGENT_SENTINEL, subjectKey, t, nowMillis());
        }
    }

    @Override
    public void supersede(UUID agentId, String subjectKey, GraphTriple triple) {
        if (triple == null) {
            return;
        }
        long now = nowMillis();
        String tier = agentId.toString();
        for (Edge e : edges) {
            if (e.open() && e.tier().equals(tier) && e.subjectKey().equals(subjectKey)
                && e.relation().equals(rel(triple)) && e.subject().equalsIgnoreCase(triple.subject())
                && !e.object().equalsIgnoreCase(triple.object())) {
                e.to()[0] = now;
            }
        }
        write(tier, subjectKey, triple, now);
    }

    @Override
    public List<GraphFact> recallRelated(UUID agentId, String subjectKey, String entityHint, int hops) {
        return recallRelatedAt(agentId, subjectKey, entityHint, hops, null);
    }

    @Override
    public List<GraphFact> recallRelatedAt(UUID agentId, String subjectKey, String entityHint, int hops, Instant asOf) {
        return recall(agentId.toString(), subjectKey, entityHint, hops, asOf);
    }

    @Override
    public List<GraphFact> recallSharedRelated(String subjectKey, String entityHint, int hops) {
        return recall(SHARED_AGENT_SENTINEL, subjectKey, entityHint, hops, null);
    }

    @Override
    public int eraseSubject(String subjectKey) {
        Set<String> vertices = new LinkedHashSet<>();
        for (Edge e : edges) {
            if (e.subjectKey().equals(subjectKey)) {
                vertices.add(e.tier() + "\u0000" + e.subject());
                vertices.add(e.tier() + "\u0000" + e.object());
            }
        }
        edges.removeIf(e -> e.subjectKey().equals(subjectKey));
        return vertices.size();
    }

    @Override
    public int forgetFactText(String subjectKey, UUID agentId, String factText, Collection<String> otherFactTexts) {
        if (subjectKey == null || subjectKey.isBlank() || factText == null || factText.isBlank()) {
            return 0;
        }
        String tier = agentId == null ? SHARED_AGENT_SENTINEL : agentId.toString();
        List<ValidEdge> open = new ArrayList<>();
        for (Edge e : edges) {
            if (e.open() && e.tier().equals(tier) && e.subjectKey().equals(subjectKey)) {
                open.add(new ValidEdge(e.subject(), e.relation(), e.object()));
            }
        }
        long now = nowMillis();
        int count = 0;
        for (ValidEdge v : edgesOnlyThisFactSupports(open, factText, otherFactTexts)) {
            for (Edge e : edges) {
                if (e.open() && e.tier().equals(tier) && e.subjectKey().equals(subjectKey)
                    && e.subject().equals(v.subject()) && e.relation().equals(v.relation()) && e.object().equals(v.object())) {
                    e.to()[0] = now;
                }
            }
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------------

    private static String rel(GraphTriple t) {
        return t.relation().toUpperCase(Locale.ROOT);
    }

    private void write(String tier, String subjectKey, GraphTriple t, long now) {
        for (Edge e : edges) {
            if (e.open() && e.tier().equals(tier) && e.subjectKey().equals(subjectKey)
                && e.relation().equals(rel(t)) && e.subject().equalsIgnoreCase(t.subject())
                && e.object().equalsIgnoreCase(t.object())) {
                return;
            }
        }
        edges.add(new Edge(tier, subjectKey, t.subject(), rel(t), t.object(), now, new Long[] {null}));
    }

    private List<GraphFact> recall(String tier, String subjectKey, String hint, int hops, Instant asOf) {
        if (hint == null || hint.isBlank()) {
            return List.of();
        }
        int bounded = Math.max(1, Math.min(hops, 3));
        String needle = hint.toLowerCase(Locale.ROOT);
        List<Edge> scoped = new ArrayList<>();
        for (Edge e : edges) {
            if (e.tier().equals(tier) && e.subjectKey().equals(subjectKey)) {
                scoped.add(e);
            }
        }
        Set<String> anchors = new LinkedHashSet<>();
        for (Edge e : scoped) {
            anchors.add(e.subject());
            anchors.add(e.object());
        }
        Set<GraphFact> facts = new LinkedHashSet<>();
        for (String anchor : anchors) {
            if (anchor.toLowerCase(Locale.ROOT).contains(needle)) {
                walk(scoped, anchor, new ArrayList<>(), bounded, asOf == null ? null : asOf.toEpochMilli(), facts);
            }
        }
        List<GraphFact> out = new ArrayList<>(facts);
        return out.size() > MAX_RECALL_FACTS ? new ArrayList<>(out.subList(0, MAX_RECALL_FACTS)) : out;
    }

    /** Every edge-simple path of 1..maxLen edges from {@code at}; a path counts only if all its edges are valid. */
    private void walk(List<Edge> scoped, String at, List<Edge> path, int maxLen, Long asOf, Set<GraphFact> out) {
        if (path.size() >= maxLen) {
            return;
        }
        for (Edge e : scoped) {
            if (path.contains(e)) {
                continue;
            }
            String next = e.subject().equals(at) ? e.object() : e.object().equals(at) ? e.subject() : null;
            if (next == null) {
                continue;
            }
            List<Edge> extended = new ArrayList<>(path);
            extended.add(e);
            boolean allValid = extended.stream().allMatch(x -> valid(x, asOf));
            if (allValid) {
                for (Edge x : extended) {
                    out.add(new GraphFact(x.subject(), x.relation(), x.object()));
                }
                walk(scoped, next, extended, maxLen, asOf, out);
            }
        }
    }

    private static boolean valid(Edge e, Long asOf) {
        if (asOf == null) {
            return e.open();
        }
        return e.from() <= asOf && (e.to()[0] == null || e.to()[0] > asOf);
    }
}
