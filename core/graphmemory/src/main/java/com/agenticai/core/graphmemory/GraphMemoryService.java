package com.agenticai.core.graphmemory;

import static com.agenticai.core.graphmemory.GraphFactTextMatcher.edgesOnlyThisFactSupports;
import static com.agenticai.core.graphmemory.GraphMemoryAgtype.parseAgtypeInt;
import static com.agenticai.core.graphmemory.GraphMemoryAgtype.parsePathFacts;
import static com.agenticai.core.graphmemory.GraphMemoryAgtype.stripAgtypeQuotes;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.buildForgetInvalidateCypher;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.buildSupersedeInvalidateCypher;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.cypherCall;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.sanitize;
import static com.agenticai.core.graphmemory.GraphMemoryCypher.singleEdgeFilter;

import com.agenticai.core.graphmemory.GraphFactTextMatcher.ValidEdge;
import com.agenticai.core.tenancy.AgeSessionSupport;
import com.agenticai.core.tenancy.MissingTenantContextException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Apache AGE-backed graph memory, alongside (not replacing) {@code AgentMemoryService}'s
 * pgvector-backed semantic recall. A Postgres extension was chosen over a standalone graph database
 * so graph and relational data share one store.
 *
 * <p><b>Why every method uses {@link JdbcTemplate#execute(ConnectionCallback)}, not the usual
 * {@code jdbcTemplate.update(...)}/{@code query(...)}:</b> Apache AGE requires its library to be
 * loaded (via {@link AgeSessionSupport#open}, which skips the load when AGE is preloaded, otherwise
 * uses the non-superuser-safe {@code LOAD '$libdir/plugins/age'} and puts {@code ag_catalog} on the
 * search_path) - historically {@code LOAD 'age'} to run in the SAME database session as any {@code
 * ag_catalog.cypher(...)} call that follows it - a Postgres/AGE-specific quirk where the
 * extension's custom {@code agtype} and functions aren't usable on a connection until that session
 * has loaded them. Two separate {@code JdbcTemplate} calls can be served by two different pooled
 * connections, which would silently break this; a {@link ConnectionCallback} guarantees both
 * statements run on the one, same, borrowed connection.
 *
 * <p>Every vertex/edge is tagged with {@code agent_id}/{@code subject_key} properties and every
 * query filters on both, mirroring {@code AgentMemoryService}'s exact scoping - graph memory is
 * additive per-agent-per-subject context, never a cross-agent or cross-subject shared graph within
 * a tenant, even though every tenant's graph is itself already schema-isolated (one graph per
 * tenant, see {@link GraphMemorySchemaSupport}).
 *
 * <p><b>Verified against a real Apache AGE 1.6.0 / PostgreSQL 16 (2026-10-05, {@code
 * GraphMemoryRealAgeTest}, env-guarded by {@code MEMEVAL_PG_URL}), which found and fixed three
 * defects the mock-based unit tests could not see:</b> (1) every Cypher statement that uses a
 * property map ({@code MERGE}/{@code MATCH (n {k: v})}) needs {@code ag_catalog} on the session
 * {@code search_path} (AGE rewrites the map match to an unqualified {@code agtype @> agtype}); a
 * plain pooled connection does not have it, so every write failed (swallowed by the fail-open
 * contract) - {@link #onAge} now adds it for the callback and restores the previous value
 * afterwards, which matters because this JdbcTemplate may share a Hibernate connection whose {@code
 * search_path} is the tenant schema. (2) AGE has no {@code all(x IN list WHERE ...)} predicate
 * (syntax error), so the old recall query always failed and returned nothing; recall now returns
 * the matched <em>paths</em> and applies the validity window in Java ({@link
 * GraphMemoryAgtype#parsePathFacts}). (3) a {@code MERGE} on the edge re-matched a CLOSED edge, so
 * a fact that came back after being superseded (A, then B, then A again) or after a user forget
 * stayed invisible forever, and a second supersede re-stamped {@code valid_to} on already-closed
 * edges, corrupting {@link #recallRelatedAt}; writes now only treat an OPEN edge as "already
 * known".
 *
 * <p>The agtype parsing is still text-based (the driver hands back agtype as text): scalar names
 * are unquoted and paths parsed as JSON by {@link GraphMemoryAgtype}; Cypher text and its escaping
 * live in {@link GraphMemoryCypher}. See the build plan's risk section: "isolated to one new module
 * behind a profile" is deliberate.
 */
@Service
@Profile("graph-memory")
public class GraphMemoryService {

    private static final Logger log = LoggerFactory.getLogger(GraphMemoryService.class);

    /** Facts returned by one recall (the pre-existing cap), and the path rows read to fill it. */
    static final int MAX_RECALL_FACTS = 20;

    private static final int MAX_RECALL_PATHS = 200;

    private static final String ONE_COLUMN = "v ag_catalog.agtype";

    /**
     * Sentinel value written as the {@code agent_id} property on every vertex in the tenant-shared
     * graph tier (Phase 2 of the Multi-Agent Graph Memory plan). Using the same graph ({@code
     * <schema>_kg}) but a distinguished value for {@code agent_id} means: (a) no new graph or
     * migration is needed, (b) {@link #eraseSubject} already covers shared facts because its Cypher
     * filters by {@code subject_key} alone — not {@code agent_id}.
     */
    public static final String SHARED_AGENT_SENTINEL = "__tenant_shared__";

    private final JdbcTemplate jdbcTemplate;
    private LongSupplier clock = System::currentTimeMillis;

    public GraphMemoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Test/eval seam: the epoch-ms source for {@code valid_from}/{@code valid_to} stamps. */
    void setClock(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    long nowMillis() {
        return clock.getAsLong();
    }

    /** One unit of work on an AGE-ready session; see {@link #onAge}. */
    @FunctionalInterface
    private interface AgeWork<T> {
        T run(Statement st) throws SQLException;
    }

    /**
     * Runs {@code work} on one borrowed connection that has AGE loaded and {@code ag_catalog} first
     * on its {@code search_path} ({@link AgeSessionSupport#open}: preload-aware, non-superuser-safe
     * load), then puts the previous {@code search_path} back when the session handle closes.
     * Verified need: without the search_path entry AGE's {@code agtype @> agtype} (used by every
     * property-map {@code MERGE}/{@code MATCH}) does not resolve and the statement fails.
     */
    private <T> T onAge(AgeWork<T> work) {
        return jdbcTemplate.execute(
                (ConnectionCallback<T>)
                        conn -> {
                            try (AgeSessionSupport.Session age = AgeSessionSupport.open(conn);
                                    Statement st = conn.createStatement()) {
                                return work.run(st);
                            }
                        });
    }

    /**
     * Writes each triple into the graph as a currently-valid edge. A fact that is already currently
     * valid (same subject, relation and object, compared case-insensitively on the names) is left
     * alone, so re-remembering never creates duplicate vertices/edges; a fact whose previous edge
     * was closed ({@code valid_to} set - superseded, or forgotten by the user) gets a NEW open
     * edge, because the old one is history. Fails open: a graph write failure is logged and
     * swallowed, never propagated to the caller - see {@code RememberFactToolCallback}, whose
     * primary job (vector memory) has already succeeded by the time this runs.
     */
    public void remember(UUID agentId, String subjectKey, List<GraphTriple> triples) {
        if (triples == null || triples.isEmpty()) {
            return;
        }
        String graphName = graphNameOrNull();
        if (graphName == null) {
            return;
        }
        String agentIdStr = sanitize(agentId.toString());
        String subjectKeyStr = sanitize(subjectKey);

        try {
            onAge(
                    st -> {
                        for (GraphTriple triple : triples) {
                            writeTriple(
                                    st, graphName, agentIdStr, subjectKeyStr, triple, nowMillis());
                        }
                        return null;
                    });
            log.debug(
                    "Wrote {} graph triple(s) for agent {} subject '{}'",
                    triples.size(),
                    agentId,
                    subjectKey);
        } catch (Exception ex) {
            log.warn(
                    "Graph memory write failed for agent {} subject '{}' - vector memory is unaffected",
                    agentId,
                    subjectKey,
                    ex);
        }
    }

    /**
     * Multi-hop traversal out from every vertex whose name loosely matches {@code entityHint}
     * (case-insensitive substring - callers rarely know an entity's exact stored casing), up to
     * {@code hops} relationship steps, scoped to this agent+subject. Returns an empty list (never
     * throws) if the graph doesn't exist yet, the extension isn't installed on this schema, or any
     * other failure occurs - graph recall is supplementary context; a missing graph must never
     * break a chat turn. Facts keep the direction they were written in.
     */
    public List<GraphFact> recallRelated(
            UUID agentId, String subjectKey, String entityHint, int hops) {
        return recallRelatedAt(agentId, subjectKey, entityHint, hops, null);
    }

    /**
     * Temporal recall (P1): {@code asOf == null} is the ordinary "currently-valid facts" query
     * (every edge on the path lacks a {@code valid_to} stamp - pre-temporal edges have none at all,
     * which counts as still valid, so old rows stay correct); a non-null {@code asOf} answers "what
     * did the graph believe at that instant" - every edge on the path must have become valid at or
     * before {@code asOf} and not yet been superseded by then. This is the Zep/Graphiti-style
     * point-in-time read the write side's {@code valid_from}/{@code valid_to} stamps exist to
     * serve.
     */
    public List<GraphFact> recallRelatedAt(
            UUID agentId, String subjectKey, String entityHint, int hops, Instant asOf) {
        if (entityHint == null || entityHint.isBlank()) {
            return List.of();
        }
        String graphName = graphNameOrNull();
        if (graphName == null) {
            return List.of();
        }

        try {
            return recallFacts(
                    graphName,
                    sanitize(agentId.toString()),
                    sanitize(subjectKey),
                    entityHint,
                    hops,
                    asOf);
        } catch (Exception ex) {
            log.warn(
                    "Graph memory recall failed for agent {} subject '{}' entity '{}' - continuing with vector recall only",
                    agentId,
                    subjectKey,
                    entityHint,
                    ex);
            return List.of();
        }
    }

    /**
     * Master Roadmap 2026 item #11 (Memory write arbitration) - used only when {@link
     * MemoryWriteArbitrationService} has decided a candidate triple's (subject, relation) pair
     * already exists with a different object and should replace it, not sit alongside it (an
     * UPDATE/MERGE decision). Invalidates every currently-valid {@code RELATION} edge of this exact
     * type out of {@code triple.subject()} whose target is NOT {@code triple.object()} (names
     * compared case-insensitively; scoped to this agent+subject, same filter every other query in
     * this class uses), then writes the new triple exactly like {@link #remember} does, with the
     * SAME timestamp as the old edge's {@code valid_to} so there is no gap or overlap.
     * "Invalidates" means {@code SET valid_to}, not {@code DELETE}: temporal memory keeps the
     * superseded edge in the graph so {@link #recallRelatedAt} can answer point-in-time questions,
     * while every current-state read skips it. Edges that are already closed are never touched
     * again (re-stamping them would move their {@code valid_to} and corrupt history). The stale
     * target vertex itself is left in place regardless - it may still be reachable via other edges,
     * and a dangling no-longer-connected vertex is a harmless leftover, not a correctness problem.
     *
     * <p>Fails open the same way {@link #remember} does, with one addition: if the invalidate+write
     * throws, this still falls back to a plain {@link #remember} call for the new triple - a failed
     * supersede must never mean the new (corrected) fact is lost, only that the old (now-wrong) one
     * might briefly remain valid alongside it until the next successful write.
     */
    public void supersede(UUID agentId, String subjectKey, GraphTriple triple) {
        if (triple == null) {
            return;
        }
        String graphName = graphNameOrNull();
        if (graphName == null) {
            return;
        }
        String agentIdStr = sanitize(agentId.toString());
        String subjectKeyStr = sanitize(subjectKey);

        try {
            onAge(
                    st -> {
                        long now = nowMillis();
                        st.execute(
                                cypherCall(
                                        graphName,
                                        buildSupersedeInvalidateCypher(
                                                agentIdStr, subjectKeyStr, triple, now),
                                        ONE_COLUMN));
                        writeTriple(st, graphName, agentIdStr, subjectKeyStr, triple, now);
                        return null;
                    });
            log.debug(
                    "Superseded stale '{}' edge(s) from '{}' with an updated fact for agent {} subject '{}'",
                    triple.relation(),
                    triple.subject(),
                    agentId,
                    subjectKey);
        } catch (Exception ex) {
            log.warn(
                    "Graph memory supersede failed for agent {} subject '{}' - falling back to a plain merge so the new fact isn't lost",
                    agentId,
                    subjectKey,
                    ex);
            remember(agentId, subjectKey, List.of(triple));
        }
    }

    /**
     * Phase 2 of the Multi-Agent Graph Memory (MAG) plan — tenant-shared graph writes. Identical to
     * {@link #remember} except vertices are tagged with {@link #SHARED_AGENT_SENTINEL} instead of a
     * real {@code agentId}, making them visible to every agent in this tenant via {@link
     * #recallSharedRelated}. Subject-key scoping is preserved so one data subject's shared facts
     * are never visible under a different subject's context. Fails open, identical to {@link
     * #remember}.
     */
    public void rememberShared(String subjectKey, List<GraphTriple> triples) {
        if (triples == null || triples.isEmpty()) {
            return;
        }
        String graphName = graphNameOrNull();
        if (graphName == null) {
            return;
        }
        String subjectKeyStr = sanitize(subjectKey);
        try {
            onAge(
                    st -> {
                        for (GraphTriple triple : triples) {
                            writeTriple(
                                    st,
                                    graphName,
                                    SHARED_AGENT_SENTINEL,
                                    subjectKeyStr,
                                    triple,
                                    nowMillis());
                        }
                        return null;
                    });
            log.debug(
                    "Wrote {} shared graph triple(s) for subject '{}'", triples.size(), subjectKey);
        } catch (Exception ex) {
            log.warn(
                    "Shared graph memory write failed for subject '{}' - vector memory is unaffected",
                    subjectKey,
                    ex);
        }
    }

    /**
     * Phase 2 of the MAG plan — tenant-shared graph recall. Queries vertices written by {@link
     * #rememberShared} (tagged with {@link #SHARED_AGENT_SENTINEL}), so every agent that has the
     * {@code shareMemoryWithTenant} flag enabled can retrieve what any other agent has written to
     * the shared tier for this subject. Fails open, identical to {@link #recallRelated}.
     */
    public List<GraphFact> recallSharedRelated(String subjectKey, String entityHint, int hops) {
        if (entityHint == null || entityHint.isBlank()) {
            return List.of();
        }
        String graphName = graphNameOrNull();
        if (graphName == null) {
            return List.of();
        }

        try {
            return recallFacts(
                    graphName, SHARED_AGENT_SENTINEL, sanitize(subjectKey), entityHint, hops, null);
        } catch (Exception ex) {
            log.warn(
                    "Shared graph recall failed for subject '{}' entity '{}' - continuing without shared graph context",
                    subjectKey,
                    entityHint,
                    ex);
            return List.of();
        }
    }

    /**
     * #815 Governance follow-up (PII handling review) - GDPR Article 17 "right to erasure" for a
     * single data subject's graph-memory facts. Closes the gap {@code
     * ChannelSessionService#eraseDataSubject}'s own javadoc already flags in this codebase ("Does
     * NOT touch: GraphMemoryService-extracted entities... see this method's follow-up note in
     * AI-GOVERNANCE-ASSESSMENT.md") - see this method's own callers for how the two are wired
     * together.
     *
     * <p>Scoped by {@code subject_key} ALONE, across every agent this tenant has, not just one -
     * the same "every agent, not just the currently-bound one" reasoning {@code
     * AgentMemoryService#forgetAllForSubject} already documents: a data subject who has talked to
     * three different agents has three different agent-scoped memories of them, and an erasure
     * request is about the PERSON, not about any one agent relationship.
     *
     * <p><b>Unlike every other method on this class, this one does NOT fail open.</b> {@link
     * #remember}/{@link #recallRelated}/{@link #supersede} degrade gracefully because graph memory
     * is supplementary context to a chat turn - a write or read failing there must never break the
     * conversation. An erasure request is different: silently swallowing a failure here would mean
     * reporting "erased" (or simply not surfacing an error) while the subject's data may still sit
     * in the graph - exactly the failure mode {@code TenantDataErasureService#eraseCurrentTenant}
     * already establishes the precedent against (its own {@code DROP SCHEMA} failure is rethrown,
     * not logged-and-swallowed, for the same reason). Any failure here propagates as an unchecked
     * exception.
     *
     * @return how many {@code Entity} vertices (and, via {@code DETACH DELETE}, every edge attached
     *     to them) were removed. Zero is a legitimate outcome - this subject was never written to
     *     the graph - not an error.
     */
    public int eraseSubject(String subjectKey) {
        String graphName = GraphMemorySchemaSupport.currentGraphName();
        String subjectKeyStr = sanitize(subjectKey);

        Integer erased =
                onAge(
                        st -> {
                            int matched = 0;
                            try (ResultSet rs =
                                    st.executeQuery(
                                            cypherCall(
                                                    graphName,
                                                    "MATCH (n:Entity {subject_key: '"
                                                            + subjectKeyStr
                                                            + "'}) RETURN count(n)",
                                                    "vertex_count ag_catalog.agtype"))) {
                                if (rs != null && rs.next()) {
                                    matched = parseAgtypeInt(rs.getString("vertex_count"));
                                }
                            }

                            if (matched > 0) {
                                // DETACH DELETE removes the vertex AND every edge attached to it in
                                // one
                                // statement - a subject's Entity vertex is very likely the endpoint
                                // of edges
                                // from other subjects' vertices too (shared entities, e.g. a
                                // company both
                                // Priya and Raj are linked to), and this deliberately only removes
                                // the
                                // deleted subject's OWN vertices (already filtered by subject_key
                                // above) -
                                // any edge whose OTHER endpoint belongs to a different subject
                                // simply loses
                                // that one edge, the other subject's own vertex is untouched.
                                st.execute(
                                        cypherCall(
                                                graphName,
                                                "MATCH (n:Entity {subject_key: '"
                                                        + subjectKeyStr
                                                        + "'}) DETACH DELETE n",
                                                ONE_COLUMN));
                            }
                            return matched;
                        });

        int erasedCount = erased == null ? 0 : erased;
        // No subject key in the line: this is reachable by every user (self-service forget-all) and
        // the key is an identity.
        log.info(
                "Erased {} graph-memory entit{} for one subject across every agent",
                erasedCount,
                erasedCount == 1 ? "y" : "ies");
        return erasedCount;
    }

    /**
     * Fail-open twin of {@link GraphMemorySchemaSupport#currentGraphName()} for the supplementary
     * read/write paths: no tenant schema on this thread means "no graph context", logged, never a
     * broken chat turn - and never a fallback to a shared graph. {@link #eraseSubject} deliberately
     * does NOT use this (erasure fails closed).
     */
    private static String graphNameOrNull() {
        try {
            return GraphMemorySchemaSupport.currentGraphName();
        } catch (MissingTenantContextException e) {
            log.warn("Graph memory skipped - {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // User-initiated forget sync (app.graph-memory.user-forget-sync.enabled,
    // default OFF).
    // ------------------------------------------------------------------

    /** Edges read per page by {@link #forgetFactText}. */
    static final int FORGET_SCAN_PAGE = 500;

    /**
     * Most pages one {@link #forgetFactText} call reads (so at most 10,000 currently-valid edges).
     */
    static final int FORGET_SCAN_MAX_PAGES = 20;

    /**
     * As {@link #forgetFactText(String, UUID, String, Collection)} with no other facts to protect.
     */
    public int forgetFactText(String subjectKey, UUID agentId, String factText) {
        return forgetFactText(subjectKey, agentId, factText, List.of());
    }

    /**
     * Best-effort graph side of "the user deleted (or rewrote) this remembered fact".
     *
     * <p>Edges carry no fact id, so an edge cannot be tied to the one fact that produced it. This
     * invalidates - {@code valid_to} stamp, the same temporal mechanism {@link #supersede} uses,
     * never a DELETE - every currently-valid edge in the fact's own tier whose two entity names
     * both occur in {@code factText}, unless one of {@code otherFactTexts} (the user's remaining
     * facts in that tier) names both as well. No LLM call: the match is on the stored names, so it
     * is deterministic and costs one read plus one write per edge.
     *
     * <p>The subject's currently-valid edges are read in a fixed order, {@value #FORGET_SCAN_PAGE}
     * at a time, until a short page (at most {@value #FORGET_SCAN_MAX_PAGES} pages), so the result
     * does not depend on which rows the database happened to return first.
     *
     * <p>Known limits: an edge whose entity names were paraphrased by the extractor (not present
     * verbatim in the fact text) is left in place, and names containing a backslash are skipped.
     * When the fact was edited rather than deleted, nothing is written for the new text - the graph
     * learns it again only when an agent next saves it. {@link #eraseSubject} remains the complete
     * erasure path.
     *
     * <p>Fails open like {@link #remember}: any failure is logged and reported as 0.
     *
     * @param agentId the agent whose private tier the fact lives in, or {@code null} for the
     *     tenant-shared tier ({@link #SHARED_AGENT_SENTINEL})
     * @return how many edges were invalidated
     */
    public int forgetFactText(
            String subjectKey, UUID agentId, String factText, Collection<String> otherFactTexts) {
        if (subjectKey == null || subjectKey.isBlank() || factText == null || factText.isBlank()) {
            return 0;
        }
        Collection<String> others = otherFactTexts == null ? List.of() : otherFactTexts;
        try {
            String graphName = GraphMemorySchemaSupport.currentGraphName();
            String tierStr = agentId == null ? SHARED_AGENT_SENTINEL : sanitize(agentId.toString());
            String subjectKeyStr = sanitize(subjectKey);
            String listCypher =
                    "MATCH (a:Entity)-[r:RELATION]->(b:Entity) "
                            + "WHERE a.agent_id = '"
                            + tierStr
                            + "' AND a.subject_key = '"
                            + subjectKeyStr
                            + "' "
                            + "AND b.agent_id = '"
                            + tierStr
                            + "' AND b.subject_key = '"
                            + subjectKeyStr
                            + "' "
                            + "AND r.valid_to IS NULL "
                            + "RETURN a.name, r.type, b.name ORDER BY a.name, r.type, b.name";

            Integer invalidated =
                    onAge(
                            st -> {
                                // All reads first, then the writes: invalidating while paging would
                                // shift the offsets.
                                List<ValidEdge> edges = new ArrayList<>();
                                for (int page = 0; page < FORGET_SCAN_MAX_PAGES; page++) {
                                    int rows = 0;
                                    try (ResultSet rs =
                                            st.executeQuery(
                                                    cypherCall(
                                                            graphName,
                                                            listCypher
                                                                    + " SKIP "
                                                                    + (page * FORGET_SCAN_PAGE)
                                                                    + " LIMIT "
                                                                    + FORGET_SCAN_PAGE,
                                                            "subject ag_catalog.agtype, relation ag_catalog.agtype, object ag_catalog.agtype"))) {
                                        while (rs != null && rs.next()) {
                                            rows++;
                                            edges.add(
                                                    new ValidEdge(
                                                            stripAgtypeQuotes(
                                                                    rs.getString("subject")),
                                                            stripAgtypeQuotes(
                                                                    rs.getString("relation")),
                                                            stripAgtypeQuotes(
                                                                    rs.getString("object"))));
                                        }
                                    }
                                    if (rows < FORGET_SCAN_PAGE) {
                                        break;
                                    }
                                }
                                long now = nowMillis();
                                int count = 0;
                                for (ValidEdge edge :
                                        edgesOnlyThisFactSupports(edges, factText, others)) {
                                    st.execute(
                                            cypherCall(
                                                    graphName,
                                                    buildForgetInvalidateCypher(
                                                            tierStr, subjectKeyStr, edge, now),
                                                    ONE_COLUMN));
                                    count++;
                                }
                                return count;
                            });
            int count = invalidated == null ? 0 : invalidated;
            log.debug("Invalidated {} graph edge(s) after a user-initiated fact removal", count);
            return count;
        } catch (Exception ex) {
            log.warn("Graph memory forget-sync failed - vector memory is unaffected", ex);
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    /**
     * Temporal validity (P1 developer-extensibility, Zep/Graphiti-style): edges carry {@code
     * valid_from} (epoch-ms when the fact became true) and {@code valid_to} (epoch-ms when it
     * stopped being true - set by supersede/forget, never on a plain write). Both are plain edge
     * properties on the schemaless agtype - no migration, and every edge written before the
     * temporal change simply has neither property, which the recall filters treat as "still valid"
     * ({@code valid_to} absent) / "always was" ({@code valid_from} absent), so old rows remain
     * correct forever.
     *
     * <p>Three statements, not one {@code MERGE}: (1) is there already an OPEN edge for this exact
     * fact? then nothing to do (idempotent); (2) {@code MERGE} the two vertices; (3) {@code CREATE}
     * the edge with its {@code valid_from}. A single {@code MERGE (a)-[r {type}]->(b)} would also
     * match a CLOSED edge and silently resurrect nothing - the fact would stay invalid forever.
     * Verified on real AGE. The open-edge check compares names case-insensitively.
     */
    private void writeTriple(
            Statement st,
            String graphName,
            String tierStr,
            String subjectKeyStr,
            GraphTriple triple,
            long now)
            throws SQLException {
        String subject = sanitize(triple.subject());
        String relation = sanitize(triple.relation().toUpperCase(Locale.ROOT));
        String object = sanitize(triple.object());
        String subjectLower = sanitize(triple.subject().toLowerCase(Locale.ROOT));
        String objectLower = sanitize(triple.object().toLowerCase(Locale.ROOT));

        if (hasOpenEdge(
                st, graphName, tierStr, subjectKeyStr, subjectLower, relation, objectLower)) {
            return;
        }
        st.execute(
                cypherCall(
                        graphName,
                        "MERGE (a:Entity {name: '"
                                + subject
                                + "', agent_id: '"
                                + tierStr
                                + "', subject_key: '"
                                + subjectKeyStr
                                + "'}) "
                                + "MERGE (b:Entity {name: '"
                                + object
                                + "', agent_id: '"
                                + tierStr
                                + "', subject_key: '"
                                + subjectKeyStr
                                + "'})",
                        ONE_COLUMN));
        st.execute(
                cypherCall(
                        graphName,
                        "MATCH (a:Entity {name: '"
                                + subject
                                + "', agent_id: '"
                                + tierStr
                                + "', subject_key: '"
                                + subjectKeyStr
                                + "'}), "
                                + "(b:Entity {name: '"
                                + object
                                + "', agent_id: '"
                                + tierStr
                                + "', subject_key: '"
                                + subjectKeyStr
                                + "'}) "
                                + "CREATE (a)-[:RELATION {type: '"
                                + relation
                                + "', valid_from: "
                                + now
                                + "}]->(b)",
                        ONE_COLUMN));
    }

    private boolean hasOpenEdge(
            Statement st,
            String graphName,
            String tierStr,
            String subjectKeyStr,
            String subjectLower,
            String relation,
            String objectLower)
            throws SQLException {
        String cypher =
                "MATCH (a:Entity {agent_id: '"
                        + tierStr
                        + "', subject_key: '"
                        + subjectKeyStr
                        + "'})"
                        + "-[r:RELATION {type: '"
                        + relation
                        + "'}]->"
                        + "(b:Entity {agent_id: '"
                        + tierStr
                        + "', subject_key: '"
                        + subjectKeyStr
                        + "'}) "
                        + "WHERE toLower(a.name) = '"
                        + subjectLower
                        + "' AND toLower(b.name) = '"
                        + objectLower
                        + "' "
                        + "AND r.valid_to IS NULL RETURN count(r)";
        try (ResultSet rs =
                st.executeQuery(cypherCall(graphName, cypher, "open_edges ag_catalog.agtype"))) {
            return rs != null && rs.next() && parseAgtypeInt(rs.getString("open_edges")) > 0;
        }
    }

    // ------------------------------------------------------------------
    // Recall
    // ------------------------------------------------------------------

    private List<GraphFact> recallFacts(
            String graphName,
            String tierStr,
            String subjectKeyStr,
            String entityHint,
            int hops,
            Instant asOf) {
        String hintStr = sanitize(entityHint.toLowerCase(Locale.ROOT));
        int boundedHops = Math.max(1, Math.min(hops, 3));
        Long asOfMillis = asOf == null ? null : asOf.toEpochMilli();

        // One hop: the validity window can be pushed into Cypher (r is a single edge), so the row
        // LIMIT only ever counts valid paths. Several hops: r is a LIST of edges and AGE has no
        // list predicate, so every path is read and filtered in Java (parsePathFacts).
        String pattern =
                boundedHops == 1 ? "-[r:RELATION]-" : "-[r:RELATION*1.." + boundedHops + "]-";
        String edgeFilter = boundedHops == 1 ? " AND " + singleEdgeFilter(asOfMillis) : "";
        String cypher =
                "MATCH p = (a:Entity)"
                        + pattern
                        + "(b:Entity) "
                        + "WHERE a.agent_id = '"
                        + tierStr
                        + "' AND a.subject_key = '"
                        + subjectKeyStr
                        + "' "
                        + "AND toLower(a.name) CONTAINS '"
                        + hintStr
                        + "' "
                        + "AND b.agent_id = '"
                        + tierStr
                        + "' AND b.subject_key = '"
                        + subjectKeyStr
                        + "'"
                        + edgeFilter
                        + " "
                        + "RETURN p LIMIT "
                        + MAX_RECALL_PATHS;

        List<GraphFact> facts =
                onAge(
                        st -> {
                            Set<GraphFact> result = new LinkedHashSet<>();
                            try (ResultSet rs =
                                    st.executeQuery(
                                            cypherCall(graphName, cypher, "p ag_catalog.agtype"))) {
                                while (rs != null
                                        && rs.next()
                                        && result.size() < MAX_RECALL_FACTS) {
                                    try {
                                        for (GraphFact fact :
                                                parsePathFacts(rs.getString("p"), asOfMillis)) {
                                            if (result.size() < MAX_RECALL_FACTS) {
                                                result.add(fact);
                                            }
                                        }
                                    } catch (tools.jackson.core.JacksonException ex) {
                                        throw new SQLException(
                                                "Unparseable agtype path: "
                                                        + ex.getOriginalMessage(),
                                                ex);
                                    }
                                }
                            }
                            return new ArrayList<>(result);
                        });
        return facts == null ? List.of() : facts;
    }
}
