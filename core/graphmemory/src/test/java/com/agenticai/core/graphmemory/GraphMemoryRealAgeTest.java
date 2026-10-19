package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * Real Apache AGE verification of {@link GraphMemoryService} and {@link MemoryWriteArbitrationService}
 * (the mock-based {@code GraphMemoryServiceTest} and the in-memory arbitration harness cannot see
 * what AGE actually accepts). Storage is REAL AGE + Postgres; only the LLM is scripted
 * ({@link ScriptedChats}).
 *
 * <p>Skipped unless {@code MEMEVAL_PG_URL} is set, so CI without Docker stays green. To run:
 * <pre>
 * docker run -d --name e2elocal-memeval-pg-1 -e POSTGRES_PASSWORD=memeval -e POSTGRES_DB=memeval \
 *   -p 127.0.0.1:55433:5432 deploy-postgres:latest postgres -c shared_preload_libraries=age
 * docker exec e2elocal-memeval-pg-1 psql -U postgres -d memeval -c "CREATE EXTENSION vector; CREATE EXTENSION age;"
 * MEMEVAL_PG_URL=jdbc:postgresql://127.0.0.1:55433/memeval MEMEVAL_PG_USER=postgres MEMEVAL_PG_PASSWORD=memeval \
 *   mvn -o -T1 test -pl graphmemory -am -Dtest=GraphMemoryRealAgeTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * The graph schema is created by executing the real {@code V44__graph_memory.sql} tenant migration
 * into two throw-away tenant schemas ({@code memeval_a}, {@code memeval_b}); they are dropped at the end.
 */
@EnabledIfEnvironmentVariable(named = "MEMEVAL_PG_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphMemoryRealAgeTest {

    private static final String TENANT_A = "memeval_a";
    private static final String TENANT_B = "memeval_b";
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_AGENT = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final Set<String> MULTI = Set.of("SPEAKS", "HAS_PET", "LIKES", "VISITED");

    private String url;
    private String user;
    private String password;
    private SingleConnectionDataSource serviceDs;
    private JdbcTemplate raw;
    private SingleConnectionDataSource rawDs;
    private GraphMemoryService service;
    private final AtomicLong clock = new AtomicLong();
    private final MemoryWriteArbitrationService arbitration = new MemoryWriteArbitrationService();
    private String subject;

    @BeforeAll
    void connectAndApplyTheRealGraphMigration() throws Exception {
        url = System.getenv("MEMEVAL_PG_URL");
        user = System.getenv().getOrDefault("MEMEVAL_PG_USER", "postgres");
        password = System.getenv().getOrDefault("MEMEVAL_PG_PASSWORD", "memeval");
        serviceDs = new SingleConnectionDataSource(url, user, password, true);
        rawDs = new SingleConnectionDataSource(url, user, password, true);
        raw = new JdbcTemplate(rawDs);
        service = new GraphMemoryService(new JdbcTemplate(serviceDs));
        arbitration.setEnabled(true);

        String v44 = Files.readString(findV44(), StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            for (String tenant : List.of(TENANT_A, TENANT_B)) {
                st.execute("DROP SCHEMA IF EXISTS " + tenant + " CASCADE");
                st.execute("CREATE SCHEMA " + tenant);
                st.execute("SET search_path TO " + tenant);
                st.execute(v44); // exactly what Flyway runs for a new tenant
                st.execute("RESET search_path");
            }
            try (ResultSet rs = st.executeQuery("SELECT name FROM ag_catalog.ag_graph ORDER BY name")) {
                List<String> graphs = new ArrayList<>();
                while (rs.next()) {
                    graphs.add(rs.getString(1));
                }
                assertThat(graphs).contains(TENANT_A + "_kg", TENANT_B + "_kg");
            }
            try (ResultSet rs = st.executeQuery("SELECT version() || ' | age ' || (SELECT extversion FROM pg_extension WHERE extname='age')")) {
                rs.next();
                System.out.println("[memeval] real database: " + rs.getString(1));
            }
        }
    }

    @AfterAll
    void dropTenants() throws Exception {
        TenantContext.clear();
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            for (String tenant : List.of(TENANT_A, TENANT_B)) {
                st.execute("SELECT ag_catalog.drop_graph('" + tenant + "_kg', true)");
                st.execute("DROP SCHEMA IF EXISTS " + tenant + " CASCADE");
            }
        }
        serviceDs.destroy();
        rawDs.destroy();
    }

    @BeforeEach
    void freshSubjectInTenantA() {
        useTenant(TENANT_A);
        subject = "sk-" + UUID.randomUUID();
        clock.set(1000);
        service.setClock(clock::get);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static void useTenant(String tenant) {
        TenantContext.set(TenantContext.of(tenant, "memeval-test", Set.of()));
    }

    private static Path findV44() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("app/src/main/resources/db/migration/tenant/V44__graph_memory.sql");
            if (Files.exists(candidate)) {
                return candidate;
            }
            candidate = dir.resolve("core/app/src/main/resources/db/migration/tenant/V44__graph_memory.sql");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("V44__graph_memory.sql not found above " + Path.of("").toAbsolutePath());
    }

    // ------------------------------------------------------------------ raw helpers (separate connection)

    private <T> T rawAge(AgeFn<T> fn) {
        return raw.execute((ConnectionCallback<T>) conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("LOAD 'age'");
                st.execute("SET search_path = ag_catalog, \"$user\", public");
                return fn.apply(st);
            }
        });
    }

    private interface AgeFn<T> {
        T apply(Statement st) throws SQLException;
    }

    private static String bare(String agtype) {
        if (agtype == null || agtype.equals("null")) {
            return "-";
        }
        return agtype.length() >= 2 && agtype.startsWith("\"") && agtype.endsWith("\"") ? agtype.substring(1, agtype.length() - 1) : agtype;
    }

    /** Every edge (open and closed) of one subject key, as {@code subject|TYPE|object|valid_from|valid_to}, sorted. */
    private List<String> rawEdges(String tenant, String subjectKey) {
        return rawAge(st -> {
            List<String> out = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                "SELECT * FROM ag_catalog.cypher('" + tenant + "_kg', $$ MATCH (a:Entity {subject_key: '" + subjectKey
                    + "'})-[r:RELATION]->(b:Entity) RETURN a.name, r.type, b.name, r.valid_from, r.valid_to $$) "
                    + "AS (a ag_catalog.agtype, t ag_catalog.agtype, b ag_catalog.agtype, f ag_catalog.agtype, x ag_catalog.agtype)")) {
                while (rs.next()) {
                    out.add(bare(rs.getString(1)) + "|" + bare(rs.getString(2)) + "|" + bare(rs.getString(3)) + "|"
                        + bare(rs.getString(4)) + "|" + bare(rs.getString(5)));
                }
            }
            Collections.sort(out);
            return out;
        });
    }

    private int rawVertexCount(String tenant, String subjectKey) {
        return rawAge(st -> {
            try (ResultSet rs = st.executeQuery(
                "SELECT * FROM ag_catalog.cypher('" + tenant + "_kg', $$ MATCH (n:Entity {subject_key: '" + subjectKey
                    + "'}) RETURN count(n) $$) AS (c ag_catalog.agtype)")) {
                rs.next();
                return Integer.parseInt(rs.getString(1));
            }
        });
    }

    private void write(long at, ChatClient chat, GraphTriple... triples) {
        clock.set(at);
        arbitration.arbitrateAndWrite(chat, service, AGENT, subject, List.of(triples));
    }

    private static GraphTriple t(String s, String r, String o) {
        return new GraphTriple(s, r, o);
    }

    private ChatClient rules() {
        return ScriptedChats.rules(MULTI, false);
    }

    private static String verdict(String s, String r, String o, String decision) {
        return "[{\"subject\":\"" + s + "\",\"relation\":\"" + r + "\",\"object\":\"" + o + "\",\"decision\":\"" + decision + "\"}]";
    }

    private List<GraphFact> now(String hint) {
        return service.recallRelated(AGENT, subject, hint, 1);
    }

    private List<GraphFact> at(long ms, String hint) {
        return service.recallRelatedAt(AGENT, subject, hint, 1, Instant.ofEpochMilli(ms));
    }

    // ------------------------------------------------------------------ the scenarios

    @Test
    void controlWithoutAgeOnTheSearchPathPropertyMapMergeFailsOnRealAge() throws Exception {
        // The root cause of defect (1): the pre-fix service never put ag_catalog on the search_path.
        try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
            st.execute("LOAD 'age'");
            assertThatThrownBy(() -> st.execute(
                "SELECT * FROM ag_catalog.cypher('" + TENANT_A + "_kg', $$ MERGE (a:Entity {name: 'x', agent_id: 'y', subject_key: 'z'}) $$) AS (v ag_catalog.agtype)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("operator does not exist");
            // ...and the list predicate the old recall query used is a syntax error on AGE 1.6.0.
            assertThatThrownBy(() -> st.execute(
                "SELECT * FROM ag_catalog.cypher('" + TENANT_A + "_kg', $$ MATCH (a:Entity)-[r:RELATION*1..2]-(b:Entity) WHERE all(e IN r WHERE e.valid_to IS NULL) RETURN a $$) AS (v ag_catalog.agtype)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("syntax error");
        }
    }

    @Test
    void theServiceRestoresTheSessionSearchPathItFoundEvenWhenItIsATenantSchema() throws Exception {
        try (Connection c = serviceDs.getConnection(); Statement st = c.createStatement()) {
            st.execute("SET search_path TO \"" + TENANT_A + "\"");
            service.remember(AGENT, subject, List.of(t("User", "LIVES_IN", "Pune")));
            service.recallRelated(AGENT, subject, "user", 1);
            service.supersede(AGENT, subject, t("User", "LIVES_IN", "Mumbai"));
            service.eraseSubject("sk-nothing-" + UUID.randomUUID());
            try (ResultSet rs = st.executeQuery("SHOW search_path")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo(TENANT_A);
            }
            st.execute("RESET search_path");
        }
        assertThat(rawEdges(TENANT_A, subject)).hasSize(2);
    }

    @Test
    void addWritesAnOpenEdgeAndRecallKeepsTheDirection() {
        write(1000, rules(), t("Priya", "MANAGER_OF_BY", "Raj"));

        assertThat(rawEdges(TENANT_A, subject)).containsExactly("Priya|MANAGER_OF_BY|Raj|1000|-");
        assertThat(now("priya")).containsExactly(new GraphFact("Priya", "MANAGER_OF_BY", "Raj"));
        assertThat(now("Raj")).containsExactly(new GraphFact("Priya", "MANAGER_OF_BY", "Raj"));
    }

    @Test
    void updateSupersedesTheOldEdgeAndAsOfAnswersBeforeAndAfter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        arbitration.setMeterRegistry(registry);
        write(1000, rules(), t("User", "LIVES_IN", "Pune"));
        write(2000, rules(), t("User", "LIVES_IN", "Mumbai")); // ideal arbitrator -> UPDATE

        assertThat(rawEdges(TENANT_A, subject)).containsExactlyInAnyOrder(
            "User|LIVES_IN|Pune|1000|2000", "User|LIVES_IN|Mumbai|2000|-");
        assertThat(registry.counter("graph.memory.arbitration.decisions", "decision", "UPDATE").count()).isEqualTo(1.0);
        assertThat(now("user")).containsExactly(new GraphFact("User", "LIVES_IN", "Mumbai"));
        assertThat(at(500, "user")).isEmpty();
        assertThat(at(1500, "user")).containsExactly(new GraphFact("User", "LIVES_IN", "Pune"));
        assertThat(at(1999, "user")).containsExactly(new GraphFact("User", "LIVES_IN", "Pune"));
        assertThat(at(2000, "user")).as("valid_to is exclusive, valid_from inclusive").containsExactly(new GraphFact("User", "LIVES_IN", "Mumbai"));
        assertThat(at(9999, "user")).containsExactly(new GraphFact("User", "LIVES_IN", "Mumbai"));
        arbitration.setMeterRegistry(null);
    }

    @Test
    void aChainOfSupersedesNeverRestampsHistory() {
        write(1000, rules(), t("User", "WORKS_AT", "Acme"));
        write(2000, rules(), t("User", "WORKS_AT", "Globex"));
        write(3000, rules(), t("User", "WORKS_AT", "Initech"));

        assertThat(rawEdges(TENANT_A, subject)).containsExactlyInAnyOrder(
            "User|WORKS_AT|Acme|1000|2000", "User|WORKS_AT|Globex|2000|3000", "User|WORKS_AT|Initech|3000|-");
        assertThat(at(1500, "user")).containsExactly(new GraphFact("User", "WORKS_AT", "Acme"));
        assertThat(at(2500, "user")).containsExactly(new GraphFact("User", "WORKS_AT", "Globex"));
        assertThat(now("user")).containsExactly(new GraphFact("User", "WORKS_AT", "Initech"));
    }

    @Test
    void aFactThatComesBackAfterBeingSupersededIsValidAgainWithItsOwnWindow() {
        write(1000, rules(), t("User", "LIVES_IN", "Pune"));
        write(2000, rules(), t("User", "LIVES_IN", "Mumbai"));
        write(3000, rules(), t("User", "LIVES_IN", "Pune"));

        assertThat(rawEdges(TENANT_A, subject)).containsExactlyInAnyOrder(
            "User|LIVES_IN|Pune|1000|2000", "User|LIVES_IN|Mumbai|2000|3000", "User|LIVES_IN|Pune|3000|-");
        assertThat(now("user")).containsExactly(new GraphFact("User", "LIVES_IN", "Pune"));
        assertThat(at(2500, "user")).containsExactly(new GraphFact("User", "LIVES_IN", "Mumbai"));
    }

    @Test
    void contradictionIsResolvedByAnUpdateVerdictAndKeptSideBySideByAnAddVerdict() {
        write(1000, rules(), t("User", "DIET", "vegetarian"));
        write(2000, rules(), t("User", "DIET", "omnivore"));
        assertThat(now("user")).containsExactly(new GraphFact("User", "DIET", "omnivore"));

        // Documented limitation, not a desired behaviour: when the model says ADD for a contradicting
        // single-valued fact, both stay valid and the graph holds the contradiction.
        String subjectTwo = subject + "-b";
        subject = subjectTwo;
        write(1000, rules(), t("User", "DIET", "vegetarian"));
        write(2000, ScriptedChats.fixed(verdict("User", "DIET", "omnivore", "ADD")), t("User", "DIET", "omnivore"));
        assertThat(now("user")).containsExactlyInAnyOrder(
            new GraphFact("User", "DIET", "vegetarian"), new GraphFact("User", "DIET", "omnivore"));
    }

    @Test
    void mergeVerdictReplacesTheOlderWording() {
        write(1000, rules(), t("User", "LIVES_IN", "Pune"));
        write(2000, ScriptedChats.fixed(verdict("User", "LIVES_IN", "Pune, India", "MERGE")), t("User", "LIVES_IN", "Pune, India"));

        assertThat(rawEdges(TENANT_A, subject)).containsExactlyInAnyOrder(
            "User|LIVES_IN|Pune|1000|2000", "User|LIVES_IN|Pune, India|2000|-");
        assertThat(now("user")).containsExactly(new GraphFact("User", "LIVES_IN", "Pune, India"));
    }

    @Test
    void discardWritesNothingAndAnUnverifiedDiscardIsDowngradedToAdd() {
        write(1000, rules(), t("User", "LIVES_IN", "Pune"));
        write(2000, ScriptedChats.fixed(verdict("User", "LIVES_IN", "Pune", "DISCARD")), t("User", "LIVES_IN", "Pune"));
        assertThat(rawEdges(TENANT_A, subject)).containsExactly("User|LIVES_IN|Pune|1000|-");

        write(3000, ScriptedChats.fixed(verdict("User", "LIKES", "jazz", "DISCARD")), t("User", "LIKES", "jazz"));
        assertThat(rawEdges(TENANT_A, subject)).containsExactlyInAnyOrder("User|LIVES_IN|Pune|1000|-", "User|LIKES|jazz|3000|-");
    }

    @Test
    void multiValuedFactsAreAddedNotSuperseded() {
        write(1000, rules(), t("User", "SPEAKS", "Hindi"));
        write(2000, rules(), t("User", "SPEAKS", "English"));
        assertThat(now("user")).containsExactlyInAnyOrder(new GraphFact("User", "SPEAKS", "Hindi"), new GraphFact("User", "SPEAKS", "English"));
        assertThat(at(1500, "user")).containsExactly(new GraphFact("User", "SPEAKS", "Hindi"));
    }

    @Test
    void tenantsAreIsolatedFromEachOtherAndFromEachOthersErasure() {
        write(1000, rules(), t("User", "LIVES_IN", "Pune"));
        useTenant(TENANT_B);
        clock.set(1000);
        arbitration.arbitrateAndWrite(rules(), service, AGENT, subject, List.of(t("User", "LIVES_IN", "Delhi")));
        arbitration.arbitrateAndWrite(rules(), service, AGENT, subject, List.of(t("User", "LIVES_IN", "Goa")));

        assertThat(now("user")).containsExactly(new GraphFact("User", "LIVES_IN", "Goa"));
        assertThat(rawEdges(TENANT_B, subject)).hasSize(2);
        assertThat(rawEdges(TENANT_A, subject)).containsExactly("User|LIVES_IN|Pune|1000|-");

        assertThat(service.eraseSubject(subject)).isEqualTo(3); // User, Delhi, Goa
        assertThat(rawEdges(TENANT_B, subject)).isEmpty();
        useTenant(TENANT_A);
        assertThat(now("user")).containsExactly(new GraphFact("User", "LIVES_IN", "Pune"));
    }

    @Test
    void agentsAndSubjectKeysAreIsolatedWithinATenantAndTheSharedTierIsVisibleToAll() {
        write(1000, rules(), t("User", "LIVES_IN", "Pune"));
        assertThat(service.recallRelated(OTHER_AGENT, subject, "user", 1)).isEmpty();
        assertThat(service.recallRelated(AGENT, subject + "-other", "user", 1)).isEmpty();

        service.rememberShared(subject, List.of(t("Acme", "LOCATED_IN", "Berlin")));
        assertThat(service.recallSharedRelated(subject, "acme", 1)).containsExactly(new GraphFact("Acme", "LOCATED_IN", "Berlin"));
        assertThat(service.recallSharedRelated(subject + "-other", "acme", 1)).isEmpty();
        assertThat(now("acme")).as("the shared tier is not in the agent's private tier").isEmpty();
    }

    @Test
    void writesAreIdempotent() {
        service.remember(AGENT, subject, List.of(t("User", "LIVES_IN", "Pune")));
        clock.set(5000);
        service.remember(AGENT, subject, List.of(t("User", "LIVES_IN", "Pune")));
        service.remember(AGENT, subject, List.of(t("user", "lives_in", "pune"))); // case-insensitive duplicate
        service.supersede(AGENT, subject, t("User", "LIVES_IN", "Pune")); // restating the current value
        write(6000, rules(), t("User", "LIVES_IN", "Pune")); // arbitration: DISCARD

        assertThat(rawEdges(TENANT_A, subject)).containsExactly("User|LIVES_IN|Pune|1000|-");
        assertThat(rawVertexCount(TENANT_A, subject)).isEqualTo(2);
    }

    @Test
    void supersedeMatchesTheSubjectCaseInsensitively() {
        service.remember(AGENT, subject, List.of(t("Sam", "LIVES_IN", "Pune")));
        clock.set(2000);
        service.supersede(AGENT, subject, t("sam", "LIVES_IN", "Mumbai"));

        assertThat(rawEdges(TENANT_A, subject)).containsExactlyInAnyOrder("Sam|LIVES_IN|Pune|1000|2000", "sam|LIVES_IN|Mumbai|2000|-");
        assertThat(now("sam")).containsExactly(new GraphFact("sam", "LIVES_IN", "Mumbai"));
    }

    @Test
    void awkwardNamesRoundTripThroughAge() {
        List<GraphTriple> triples = List.of(
            t("O'Brien", "LIVES_IN", "Zoë's café"),
            t("User", "SAID", "He said \"hi\""),
            t("User", "LIKES", "पुणे"),
            t("User", "PAID", "cost $$ 5 \\ 6"));
        service.remember(AGENT, subject, triples);

        assertThat(now("o'brien")).containsExactly(new GraphFact("O'Brien", "LIVES_IN", "Zoë's café"));
        assertThat(now("user")).containsExactlyInAnyOrder(
            new GraphFact("User", "SAID", "He said \"hi\""),
            new GraphFact("User", "LIKES", "पुणे"),
            new GraphFact("User", "PAID", "cost $$ 5 \\ 6"));
    }

    @Test
    void multiHopRecallReturnsEveryEdgeOnValidPathsInTheirOwnDirection() {
        service.remember(AGENT, subject, List.of(t("Priya", "MANAGER_OF_BY", "Raj"), t("Raj", "WORKS_AT", "Acme")));

        assertThat(service.recallRelated(AGENT, subject, "priya", 1)).containsExactly(new GraphFact("Priya", "MANAGER_OF_BY", "Raj"));
        assertThat(service.recallRelated(AGENT, subject, "priya", 2)).containsExactlyInAnyOrder(
            new GraphFact("Priya", "MANAGER_OF_BY", "Raj"), new GraphFact("Raj", "WORKS_AT", "Acme"));
        assertThat(service.recallRelated(AGENT, subject, "acme", 2)).containsExactlyInAnyOrder(
            new GraphFact("Priya", "MANAGER_OF_BY", "Raj"), new GraphFact("Raj", "WORKS_AT", "Acme"));

        clock.set(2000);
        service.supersede(AGENT, subject, t("Raj", "WORKS_AT", "Globex"));
        assertThat(service.recallRelated(AGENT, subject, "priya", 2)).containsExactlyInAnyOrder(
            new GraphFact("Priya", "MANAGER_OF_BY", "Raj"), new GraphFact("Raj", "WORKS_AT", "Globex"));
        assertThat(service.recallRelatedAt(AGENT, subject, "priya", 2, Instant.ofEpochMilli(1500))).containsExactlyInAnyOrder(
            new GraphFact("Priya", "MANAGER_OF_BY", "Raj"), new GraphFact("Raj", "WORKS_AT", "Acme"));
    }

    @Test
    void forgetThenRememberAgainReopensTheFactAndEraseRemovesEverything() {
        service.remember(AGENT, subject, List.of(t("User", "LIVES_IN", "Pune")));
        clock.set(2000);
        assertThat(service.forgetFactText(subject, AGENT, "I live in Pune")).isEqualTo(1);
        assertThat(now("user")).isEmpty();
        assertThat(at(1500, "user")).containsExactly(new GraphFact("User", "LIVES_IN", "Pune"));

        clock.set(3000);
        service.remember(AGENT, subject, List.of(t("User", "LIVES_IN", "Pune")));
        assertThat(now("user")).containsExactly(new GraphFact("User", "LIVES_IN", "Pune"));
        assertThat(rawEdges(TENANT_A, subject)).containsExactlyInAnyOrder("User|LIVES_IN|Pune|1000|2000", "User|LIVES_IN|Pune|3000|-");

        String other = subject + "-keep";
        service.remember(AGENT, other, List.of(t("User", "LIVES_IN", "Delhi")));
        assertThat(service.eraseSubject(subject)).isEqualTo(2);
        assertThat(rawEdges(TENANT_A, subject)).isEmpty();
        assertThat(service.eraseSubject(subject)).isZero();
        assertThat(rawEdges(TENANT_A, other)).hasSize(1);
    }

    /** Informational only (no assertions on time): one connection, local container, small graph - NOT a load test. */
    @Test
    void informationalLatencyOfTheStorageCalls() {
        int n = 60;
        long[] remember = new long[n];
        long[] supersede = new long[n];
        long[] recall = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            service.remember(AGENT, subject, List.of(t("User", "VISITED", "city-" + i)));
            long t1 = System.nanoTime();
            service.supersede(AGENT, subject, t("User", "LIVES_IN", "home-" + i));
            long t2 = System.nanoTime();
            service.recallRelated(AGENT, subject, "user", 1);
            long t3 = System.nanoTime();
            remember[i] = t1 - t0;
            supersede[i] = t2 - t1;
            recall[i] = t3 - t2;
        }
        System.out.println("[memeval] storage latency ms (median/p95 of " + n + ", one local connection): remember "
            + ms(remember, 0.5) + "/" + ms(remember, 0.95) + ", supersede " + ms(supersede, 0.5) + "/" + ms(supersede, 0.95)
            + ", recall(1 hop, ~" + (2 * n) + " edges for the subject, " + n + " of them closed) " + ms(recall, 0.5) + "/" + ms(recall, 0.95));
        assertThat(now("user")).hasSize(Math.min(GraphMemoryService.MAX_RECALL_FACTS, n + 1));
        service.eraseSubject(subject);
    }

    private static String ms(long[] nanos, double q) {
        long[] sorted = nanos.clone();
        java.util.Arrays.sort(sorted);
        return String.format(java.util.Locale.ROOT, "%.1f", sorted[(int) Math.min(sorted.length - 1, Math.floor(q * sorted.length))] / 1e6);
    }

    @Test
    void longMemEvalLiteGivesTheSameResultsOnRealAgeAsOnTheInMemoryGraph() throws Exception {
        MemoryEval.Fixture fixture = MemoryEval.loadFixture();
        Map<MemoryEval.Mode, List<MemoryEval.CaseResult>> memory =
            MemoryEval.runAll(fixture, new InMemoryTemporalGraphMemory(), new MemoryEval.ExtractiveAnswerer(), "mem");
        Map<MemoryEval.Mode, List<MemoryEval.CaseResult>> age =
            MemoryEval.runAll(fixture, service, new MemoryEval.ExtractiveAnswerer(), "age-" + UUID.randomUUID());

        String report = MemoryEval.report("real Apache AGE (tenant " + TENANT_A + ")", age);
        System.out.println(report);
        Path out = Path.of("target", "memeval-report-age.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);

        for (MemoryEval.Mode mode : MemoryEval.Mode.values()) {
            List<String> expected = memory.get(mode).stream().map(MemoryEval.CaseResult::signature).toList();
            List<String> actual = age.get(mode).stream().map(MemoryEval.CaseResult::signature).toList();
            assertThat(actual).as("per-case parity, mode " + mode).isEqualTo(expected);
        }
        // Everything the eval wrote has been erased again.
        Integer leftover = rawAge(st -> {
            try (ResultSet rs = st.executeQuery("SELECT * FROM ag_catalog.cypher('" + TENANT_A
                + "_kg', $$ MATCH (n:Entity) WHERE n.subject_key STARTS WITH 'age-' RETURN count(n) $$) AS (c ag_catalog.agtype)")) {
                rs.next();
                return Integer.parseInt(rs.getString(1));
            }
        });
        assertThat(leftover).isZero();
    }
}
