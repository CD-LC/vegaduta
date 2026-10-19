package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.AgeSessionSupport;
import com.agenticai.core.tenancy.MissingTenantContextException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link GraphMemoryService#eraseSubject}-focused coverage - the rest of this class ({@link
 * GraphMemoryService#remember}/{@link GraphMemoryService#recallRelated}/{@link
 * GraphMemoryService#supersede}) had zero test coverage before this file existed. Scoping this
 * suite to the newly-added erasure method (rather than retrofitting the other three, which is a
 * separately-sized task of its own) is a deliberate, disclosed scope limit - see
 * AGENT-COORDINATION-2026-07-18.md for the tracked follow-up, not a silent gap. (F-19 later
 * added one {@link GraphMemoryService#remember}-path test here, but only to pin the
 * escape-don't-corrupt contract at an interpolation site the erasure tests don't reach - the
 * broader remember/recall/supersede behavioral coverage gap above still stands. The escaper
 * itself has its own dedicated suite, {@link CypherLiteralEscaperTest}.)
 *
 * <p>Mocks {@link JdbcTemplate}/{@link Connection}/{@link Statement}/{@link ResultSet} directly
 * rather than exercising a real AGE-enabled Postgres - this class's own javadoc already states
 * plainly that "isolated to one new module behind a profile" is the deliberate tradeoff here
 * (no real Postgres/AGE instance is reachable from this sandbox to verify the actual Cypher
 * execution against, the same honestly-disclosed ceiling this session's every other AGE-adjacent
 * claim has carried).
 */
class GraphMemoryServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final Connection connection = mock(Connection.class);
    private final Statement statement = mock(Statement.class);
    private final GraphMemoryService service = new GraphMemoryService(jdbcTemplate);

    private static final String LOAD_PLUGINS = "LOAD '$libdir/plugins/age'";

    @BeforeEach
    void resetAgeState() {
        // The AGE preload probe is cached per JVM; every test starts from "unknown".
        AgeSessionSupport.resetCachedPreloadStateForTests();
        TenantContext.clear();
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private void stubConnectionCallback() throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        // Mirrors the REAL JdbcTemplate#execute(ConnectionCallback) contract: it translates any
        // SQLException the callback throws into an unchecked DataAccessException before it
        // reaches the caller (never a raw checked SQLException) - UncategorizedSQLException is
        // Spring's own fallback translation when no more specific SQLExceptionTranslator mapping
        // applies, which is exactly the untranslated case here since no real DataSource/dialect
        // is involved in this unit test.
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(invocation -> {
            ConnectionCallback<Object> callback = invocation.getArgument(0);
            try {
                return callback.doInConnection(connection);
            } catch (SQLException e) {
                throw new UncategorizedSQLException("execute", "", e);
            }
        });
    }

    private ResultSet countResultReturning(String agtypeCount) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true, false);
        when(rs.getString("vertex_count")).thenReturn(agtypeCount);
        return rs;
    }

    @Test
    void erasesEveryMatchedVertexAndReturnsTheCount() throws SQLException {
        stubConnectionCallback();
        // countResultReturning() opens its own when(...) stub internally - it must be fully
        // evaluated before the outer when(...).thenReturn(...) chain below opens its own, or
        // Mockito's ongoing-stubbing state gets interleaved and throws UnfinishedStubbingException.
        ResultSet countResult = countResultReturning("3");
        when(statement.executeQuery(contains("count(n)"))).thenReturn(countResult);

        int erased = service.eraseSubject("subject-123");

        assertThat(erased).isEqualTo(3);
        verify(statement).execute(LOAD_PLUGINS);
        verify(statement, never()).execute("LOAD 'age'");
        verify(statement).execute(contains("DETACH DELETE n"));
    }

    @Test
    void zeroMatchesSkipsTheDeleteStatementEntirely() throws SQLException {
        stubConnectionCallback();
        ResultSet countResult = countResultReturning("0");
        when(statement.executeQuery(contains("count(n)"))).thenReturn(countResult);

        int erased = service.eraseSubject("subject-with-no-graph-facts");

        assertThat(erased).isZero();
        verify(statement).execute(LOAD_PLUGINS);
        verify(statement, never()).execute(contains("DETACH DELETE"));
    }

    @Test
    void subjectKeyIsEscapedIntoAnInertLiteralBeforeBeingInterpolatedIntoTheCypherQuery() throws SQLException {
        stubConnectionCallback();
        ResultSet countResult = countResultReturning("1");
        when(statement.executeQuery(contains("count(n)"))).thenReturn(countResult);
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);

        // A quote-break plus a dollar-quote terminator in one payload - if escaping were
        // skipped, the leading apostrophe would close the Cypher string literal and the $$
        // would terminate the outer Postgres dollar-quoted SQL literal, letting the MATCH/
        // DETACH DELETE clause run unscoped by subject_key. The new contract is escape-not-
        // strip: the payload must arrive INSIDE the literal, defused, not be deleted from it.
        service.eraseSubject("evil'; MATCH (x) DETACH DELETE x; $$ --");

        verify(statement, atLeastOnce()).executeQuery(queryCaptor.capture());
        String executedQuery = queryCaptor.getAllValues().stream()
            .filter(q -> q.contains("ag_catalog.cypher"))
            .findFirst()
            .orElseThrow();
        // The apostrophe survives, escaped - the raw break-out sequence never appears.
        assertThat(executedQuery).contains("evil\\'; MATCH");
        assertThat(executedQuery).doesNotContain("evil'; MATCH");
        // Exactly the builder's own two $$ delimiters remain - the payload's $$ was encoded
        // to backslash-u0024 pairs, so splitting on $$ yields exactly three segments.
        assertThat(executedQuery.split("\\$\\$", -1)).hasSize(3);
        assertThat(executedQuery).contains("\\u0024\\u0024");
    }

    @Test
    void apostropheEntityNamesSurviveEscapingInsteadOfBeingStrippedOrCorrupted() throws SQLException {
        stubConnectionCallback();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        service.remember(
            UUID.randomUUID(), "subject-1",
            List.of(new GraphTriple("O'Brien", "MANAGER_OF", "Raj"))
        );

        // The AGE load ($libdir/plugins path), then the vertex MERGE, then the edge CREATE (the open-edge check is a query).
        verify(statement, times(3)).execute(sqlCaptor.capture());
        String mergeSql = sqlCaptor.getAllValues().get(1);
        // The old strip-based defense would have written the corrupted name "OBrien" into the
        // graph; the escaper must preserve the apostrophe as an escaped, inert \'.
        assertThat(mergeSql).contains("O\\'Brien");
        assertThat(mergeSql).doesNotContain("OBrien");
    }

    /**
     * Item-8 store-side verification: {@link MemoryWriteArbitrationService}'s UPDATE/MERGE
     * path lands here. The order matters - the stale edge's {@code valid_to} stamp (temporal
     * invalidation, never a DELETE) must run BEFORE the new triple's MERGE inside the same
     * {@code LOAD 'age'} session, so a recalled "current state" never briefly contains both
     * the old and new fact.
     */
    @Test
    void supersedeInvalidatesStaleEdgesThenMergesTheNewTriple() throws SQLException {
        stubConnectionCallback();
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        UUID agentId = UUID.randomUUID();

        service.supersede(agentId, "subject-1", new GraphTriple("Priya", "MANAGER_OF_BY", "Rahul"));

        // The AGE load, the invalidation cypher, then the write (vertex MERGE + edge CREATE) - one session.
        verify(statement, times(4)).execute(sqlCaptor.capture());
        List<String> statements = sqlCaptor.getAllValues();
        assertThat(statements.get(0)).isEqualTo(LOAD_PLUGINS);

        String invalidateSql = statements.get(1);
        assertThat(invalidateSql).contains("ag_catalog.cypher");
        // Invalidation is a valid_to stamp (Zep/Graphiti-style temporal supersession), NOT a
        // DELETE - the old edge stays in the graph for recallRelatedAt's point-in-time reads.
        assertThat(invalidateSql).contains("SET r.valid_to");
        assertThat(invalidateSql).doesNotContain("DELETE");
        // Only stale targets are invalidated: edges pointing at the NEW object are excluded
        // (re-asserting an already-current fact must not invalidate itself).
        assertThat(invalidateSql).contains("toLower(b.name) <> 'rahul'");
        // Already-closed edges are never re-stamped (verified on real AGE: it corrupted history).
        assertThat(invalidateSql).contains("r.valid_to IS NULL");
        assertThat(invalidateSql).contains("MANAGER_OF_BY");
        assertThat(invalidateSql).contains("agent_id: '" + agentId + "'");

        String mergeSql = statements.get(2);
        assertThat(mergeSql).contains("MERGE");
        assertThat(mergeSql).contains("Rahul");
        String createSql = statements.get(3);
        assertThat(createSql).contains("CREATE");
        assertThat(createSql).contains("MANAGER_OF_BY");
        assertThat(createSql).contains("valid_from");
    }

    /**
     * The supersede fail-open contract: if the invalidate+merge statement itself fails, the
     * NEW fact is still written via a plain {@link #remember} - a failed supersede may leave
     * the stale edge valid a little longer but must never lose the correction.
     */
    @Test
    void aFailedInvalidationStillWritesTheNewFactViaPlainMerge() throws SQLException {
        stubConnectionCallback();
        // Fail only the statement carrying the valid_to stamp - the LOAD and, inside the
        // remember() fallback, the MERGE still succeed.
        org.mockito.Mockito.doThrow(new SQLException("AGE rejected the invalidation"))
            .when(statement).execute(contains("valid_to"));
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        service.supersede(UUID.randomUUID(), "subject-1", new GraphTriple("Priya", "MANAGER_OF_BY", "Rahul"));

        verify(statement, org.mockito.Mockito.atLeastOnce()).execute(sqlCaptor.capture());
        assertThat(sqlCaptor.getAllValues())
            .anySatisfy(sql -> assertThat(sql).contains("MERGE").contains("Rahul"));
    }

    @Test
    void aQueryFailureIsNotSwallowed_erasureFailsClosedNotOpen() throws SQLException {
        stubConnectionCallback();
        when(statement.executeQuery(contains("count(n)"))).thenThrow(new SQLException("AGE not loaded on this connection"));

        assertThatThrownBy(() -> service.eraseSubject("subject-123"))
            .isInstanceOf(RuntimeException.class);
    }

    // ------------------------------------------------------------------
    // SEC-001 / SEC-017: no graph name from a system schema
    // ------------------------------------------------------------------

    @Test
    void aContextNamingASystemSchemaNeverReachesAgeAndErasureFailsClosed() throws SQLException {
        stubConnectionCallback();
        TenantContext.set(TenantContext.of("ag_catalog", "sub", Set.of()));

        // Supplementary paths: skipped, never touching the database.
        service.remember(UUID.randomUUID(), "s", List.of(new GraphTriple("a", "R", "b")));
        assertThat(service.recallRelated(UUID.randomUUID(), "s", "a", 1)).isEmpty();
        verify(jdbcTemplate, never()).execute(any(ConnectionCallback.class));

        // Erasure: fails loudly instead of targeting "ag_catalog_kg".
        assertThatThrownBy(() -> service.eraseSubject("s")).isInstanceOf(MissingTenantContextException.class);
        verify(statement, never()).execute(anyString());
    }

    @Test
    void aTenantContextTargetsThatTenantsOwnGraph() throws SQLException {
        stubConnectionCallback();
        TenantContext.set(TenantContext.of("t_abcdefghijklmnopqrstuvwxyz", "sub", Set.of()));
        ResultSet countResult = countResultReturning("0");
        when(statement.executeQuery(contains("count(n)"))).thenReturn(countResult);

        service.eraseSubject("s");

        verify(statement).executeQuery(contains("cypher('t_abcdefghijklmnopqrstuvwxyz_kg'"));
    }

    // ---- forgetFactText (user-initiated forget sync, app.graph-memory.user-forget-sync.enabled) ----

    private ResultSet edgeRows(String... subjectRelationObject) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        int rows = subjectRelationObject.length / 3;
        Boolean[] rest = new Boolean[rows];
        for (int i = 0; i < rows; i++) {
            rest[i] = i < rows - 1;
        }
        when(rs.next()).thenReturn(rows > 0, rest);
        String[] subjects = new String[rows];
        String[] relations = new String[rows];
        String[] objects = new String[rows];
        for (int i = 0; i < rows; i++) {
            subjects[i] = subjectRelationObject[i * 3];
            relations[i] = subjectRelationObject[i * 3 + 1];
            objects[i] = subjectRelationObject[i * 3 + 2];
        }
        if (rows > 0) {
            when(rs.getString("subject")).thenReturn(subjects[0], java.util.Arrays.copyOfRange(subjects, 1, rows));
            when(rs.getString("relation")).thenReturn(relations[0], java.util.Arrays.copyOfRange(relations, 1, rows));
            when(rs.getString("object")).thenReturn(objects[0], java.util.Arrays.copyOfRange(objects, 1, rows));
        }
        return rs;
    }

    @Test
    void forgetFactTextInvalidatesOnlyEdgesTheDeletedTextNamesBothEndsOf() throws SQLException {
        stubConnectionCallback();
        ResultSet edges = edgeRows(
            "\"Priya\"", "\"MANAGER_OF_BY\"", "\"Raj\"",
            "\"Priya\"", "\"WORKS_AT\"", "\"Acme\"");
        when(statement.executeQuery(contains("RETURN a.name, r.type, b.name"))).thenReturn(edges);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        UUID agentId = UUID.randomUUID();

        int invalidated = service.forgetFactText("subject-1", agentId, "Priya's manager is Raj");

        assertThat(invalidated).isEqualTo(1);
        // LOAD 'age' + exactly one invalidation.
        verify(statement, times(2)).execute(sqlCaptor.capture());
        String invalidateSql = sqlCaptor.getAllValues().get(1);
        assertThat(invalidateSql).contains("SET r.valid_to");
        assertThat(invalidateSql).doesNotContain("DELETE");
        assertThat(invalidateSql).contains("name: 'Priya'");
        assertThat(invalidateSql).contains("name: 'Raj'");
        assertThat(invalidateSql).contains("type: 'MANAGER_OF_BY'");
        assertThat(invalidateSql).contains("agent_id: '" + agentId + "'");
        assertThat(invalidateSql).contains("subject_key: 'subject-1'");
        assertThat(invalidateSql).doesNotContain("Acme");
    }

    @Test
    void forgetFactTextLeavesAnEdgeAnotherRemainingFactStillSupports() throws SQLException {
        stubConnectionCallback();
        ResultSet edges = edgeRows("\"Priya\"", "\"MANAGER_OF_BY\"", "\"Raj\"");
        when(statement.executeQuery(contains("RETURN a.name"))).thenReturn(edges);

        int invalidated = service.forgetFactText(
            "subject-1", UUID.randomUUID(), "Priya's manager is Raj", List.of("Raj has managed Priya since 2024"));

        assertThat(invalidated).isZero();
        verify(statement, never()).execute(contains("SET r.valid_to"));
    }

    @Test
    void forgetFactTextUsesTheSharedTierWhenNoAgentIsGiven() throws SQLException {
        stubConnectionCallback();
        ResultSet edges = edgeRows("\"User\"", "\"WORKS_AT\"", "\"Acme\"");
        // The OSS AGE session helper may itself run a query on the connection, so match the edge listing only.
        when(statement.executeQuery(contains("RETURN a.name, r.type, b.name"))).thenReturn(edges);

        int invalidated = service.forgetFactText("subject-1", null, "I work at Acme");

        assertThat(invalidated).isEqualTo(1);
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).executeQuery(queryCaptor.capture());
        String listing = queryCaptor.getAllValues().stream().filter(q -> q.contains("RETURN a.name")).findFirst().orElseThrow();
        assertThat(listing).contains("a.agent_id = '" + GraphMemoryService.SHARED_AGENT_SENTINEL + "'");
        assertThat(listing).contains("r.valid_to IS NULL");
        verify(statement).execute(contains("SET r.valid_to"));
    }

    @Test
    void forgetFactTextPagesThroughEveryEdgeInAFixedOrder() throws SQLException {
        stubConnectionCallback();
        String[] fullPage = new String[GraphMemoryService.FORGET_SCAN_PAGE * 3];
        for (int i = 0; i < GraphMemoryService.FORGET_SCAN_PAGE; i++) {
            fullPage[i * 3] = "\"N" + i + "\"";
            fullPage[i * 3 + 1] = "\"REL\"";
            fullPage[i * 3 + 2] = "\"M" + i + "\"";
        }
        ResultSet first = edgeRows(fullPage);
        // The edge the fact supports only shows up on the second page.
        ResultSet second = edgeRows("\"Priya\"", "\"MANAGER_OF_BY\"", "\"Raj\"");
        when(statement.executeQuery(contains("RETURN a.name, r.type, b.name"))).thenReturn(first, second);

        int invalidated = service.forgetFactText("subject-1", UUID.randomUUID(), "Priya's manager is Raj");

        assertThat(invalidated).isEqualTo(1);
        ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).executeQuery(queryCaptor.capture());
        List<String> listings = queryCaptor.getAllValues().stream().filter(q -> q.contains("RETURN a.name")).toList();
        assertThat(listings).hasSize(2);
        assertThat(listings.get(0)).contains("ORDER BY a.name, r.type, b.name SKIP 0 LIMIT 500");
        assertThat(listings.get(1)).contains("SKIP 500 LIMIT 500");
    }

    @Test
    void forgetFactTextFailsOpenAndIgnoresBlankInput() throws SQLException {
        assertThat(service.forgetFactText("subject-1", UUID.randomUUID(), "  ")).isZero();
        assertThat(service.forgetFactText(" ", UUID.randomUUID(), "Priya's manager is Raj")).isZero();
        verify(jdbcTemplate, never()).execute(any(ConnectionCallback.class));

        stubConnectionCallback();
        when(statement.executeQuery(contains("RETURN a.name"))).thenThrow(new SQLException("graph does not exist"));
        assertThat(service.forgetFactText("subject-1", UUID.randomUUID(), "Priya's manager is Raj")).isZero();
    }

    @Test
    void factSupportsEdgeMatchesWholeNamesAndFirstPersonSelfOnly() {
        assertThat(GraphFactTextMatcher.factSupportsEdge("Priya's manager is Raj", "Priya", "Raj")).isTrue();
        assertThat(GraphFactTextMatcher.factSupportsEdge("priya reports to RAJ", "Priya", "Raj")).isTrue();
        // Whole words only: "Raj" is not named by "Rajesh".
        assertThat(GraphFactTextMatcher.factSupportsEdge("Priya's manager is Rajesh", "Priya", "Raj")).isFalse();
        assertThat(GraphFactTextMatcher.factSupportsEdge("Priya likes tea", "Priya", "Raj")).isFalse();
        // First-person text stands in for a self entity, but only when the other end is named.
        assertThat(GraphFactTextMatcher.factSupportsEdge("I work at Acme", "User", "Acme")).isTrue();
        assertThat(GraphFactTextMatcher.factSupportsEdge("My shop is in Pune", "Pune", "the user")).isTrue();
        assertThat(GraphFactTextMatcher.factSupportsEdge("Priya works at Acme", "User", "Acme")).isFalse();
        assertThat(GraphFactTextMatcher.factSupportsEdge("I like tea", "User", "Acme")).isFalse();
        // Names that cannot be round-tripped safely are never matched.
        assertThat(GraphFactTextMatcher.factSupportsEdge("a\\\\b knows Raj", "a\\\\b", "Raj")).isFalse();
        assertThat(GraphFactTextMatcher.factSupportsEdge(null, "Priya", "Raj")).isFalse();
        assertThat(GraphFactTextMatcher.factSupportsEdge("Priya and Raj", " ", "Raj")).isFalse();
    }
}
