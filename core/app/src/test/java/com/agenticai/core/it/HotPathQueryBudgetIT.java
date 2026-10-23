package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.ReasoningDepth;
import com.agenticai.core.agents.tools.AgentToolProvider;
import com.agenticai.core.agents.tools.script.UserToolScript;
import com.agenticai.core.agents.tools.script.UserToolScriptService;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.version.AgentVersionResolutionService;
import com.agenticai.core.providers.OllamaCredential;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.ProviderService;
import com.agenticai.core.providers.dto.ProviderActivationRequest;
import com.agenticai.core.security.AccountType;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.HotpathLookups;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.FeatureFlagService;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantMigrationService;
import com.agenticai.core.tenancy.TenantRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Query budget for the hot path of a chat turn: how many database connections and statements ONE
 * turn costs on a real Postgres, measured twice. {@link
 * #resolvingToolsForOneTurnStaysWithinTheRecordedBudget} counts tool resolution alone; {@link
 * #aWholeTurnToTheFirstTokenStaysWithinTheRecordedBudget} counts the whole turn from the pre-flight
 * checks to the first token, against a mock model server, so every database read the platform does
 * before the model answers is in the number.
 *
 * <p>Why it matters: each connection checkout in a tenant schema also pays two {@code SET
 * search_path} round trips (see {@code SchemaMultiTenantConnectionProvider}), so a transaction
 * costs about three round trips on top of its query. The numbers here are what a turn pays before
 * the model is even called, and what grows with every new tool resolver. The test fails if they
 * grow past the recorded budget, so a change that adds a per-turn query has to say so.
 *
 * <p>Hibernate's own statistics are used (no extra dependency). {@code connectCount} is the number
 * of connection acquisitions, {@code prepareStatementCount} the number of statements.
 */
@Tag("integration")
@TestPropertySource(
        properties = {
            "spring.jpa.properties.hibernate.generate_statistics=true",
            // The mock model server listens on the loopback address, which the SSRF guard blocks
            // for a tenant-supplied Ollama base URL unless the operator allows it.
            "app.security.outbound.allowed-private-hosts=127.0.0.1",
            // The longest TTL allowed, so "warm" means warm however long the test takes: the
            // defaults (3 s) can lapse between two measured turns.
            "app.chat.hotpath.agent-projection-ttl=30s",
            "app.chat.hotpath.config-lookup-ttl=30s"
        })
class HotPathQueryBudgetIT extends AbstractContextIT {

    private static final String SCHEMA = "tenant_it_hotpath";
    private static final String TURN_SCHEMA = "tenant_it_turn";
    private static final Duration LOOKUP_TTL = Duration.ofSeconds(30);

    @Autowired private TenantRepository tenants;
    @Autowired private TenantMigrationService migrations;
    @Autowired private AgentRepository agents;
    @Autowired private AgentToolProvider toolProvider;
    @Autowired private AgentChatService chatService;
    @Autowired private ProviderService providerService;
    @Autowired private UserToolScriptService userScripts;
    @Autowired private com.agenticai.core.agents.core.AgentService agentService;
    @Autowired private com.agenticai.core.agents.crew.SpecialistRegistry specialistRegistry;
    @Autowired private com.agenticai.core.agents.crew.AgentSpecializationRepository specializations;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private TransactionTemplate tx;
    @Autowired private TenantLimits tenantLimits;
    @Autowired private FeatureFlagService featureFlags;
    @Autowired private AgentVersionResolutionService versionResolution;

    private static HttpServer modelServer;

    private UUID agentId;

    @BeforeAll
    static void startMockModelServer() throws IOException {
        modelServer =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        modelServer.createContext("/", HotPathQueryBudgetIT::answerLikeAnOllamaServer);
        modelServer.start();
    }

    @AfterAll
    static void stopMockModelServer() {
        modelServer.stop(0);
    }

    /** The three calls the platform makes to a tenant-hosted Ollama: tags, models, completions. */
    private static void answerLikeAnOllamaServer(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String request =
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/chat/completions")) {
            boolean stream = request.replace(" ", "").contains("\"stream\":true");
            if (stream) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    String head =
                            "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"mock:latest\",\"choices\":[";
                    out.write(
                            sse(
                                    head
                                            + "{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}"));
                    for (int i = 0; i < 3; i++) {
                        out.write(sse(head + "{\"index\":0,\"delta\":{\"content\":\"ok \"}}]}"));
                        out.flush();
                    }
                    out.write(
                            sse(head + "{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}"));
                    out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            respond(
                    exchange,
                    "{\"id\":\"c1\",\"object\":\"chat.completion\",\"model\":\"mock:latest\","
                            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                            + "\"content\":\"ok ok ok\"},\"finish_reason\":\"stop\"}],"
                            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":3,\"total_tokens\":13}}");
            return;
        }
        if (path.endsWith("/models")) {
            respond(
                    exchange,
                    "{\"object\":\"list\",\"data\":[{\"id\":\"mock:latest\",\"object\":\"model\"}]}");
            return;
        }
        respond(
                exchange,
                "{\"models\":[{\"name\":\"mock:latest\",\"model\":\"mock:latest\",\"size\":1}]}");
    }

    private static byte[] sse(String json) {
        return ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void tenantSchema(String schema, TenantPlan plan) {
        if (tenants.findBySchemaName(schema).isEmpty()) {
            Tenant tenant =
                    new Tenant(
                            schema,
                            plan,
                            schema,
                            "agentic-ai",
                            AccountType.B2B,
                            ExperienceMode.SIMPLE);
            tenant.markActive();
            tenants.save(tenant);
            assertThat(migrations.migrateAllActiveTenants()).as("schemas that failed").isEmpty();
        }
    }

    private UUID newAgent(ProviderName provider, String model) {
        return tx.execute(
                status ->
                        agents.save(
                                        new Agent(
                                                "hotpath-agent",
                                                "You are helpful.",
                                                provider,
                                                model,
                                                1024,
                                                0.2,
                                                1.0,
                                                true,
                                                ExperienceMode.SIMPLE))
                                .getId());
    }

    @BeforeEach
    void tenantWithOneAgent() {
        tenantSchema(SCHEMA, TenantPlan.FREE);
        TenantContext.set(TenantContext.of(SCHEMA, "it-user", Set.of()));
        agentId = newAgent(ProviderName.OPENAI, "gpt-4o-mini");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
        HotpathLookups.configure(LOOKUP_TTL);
    }

    /** Both caches start empty, so the "cold" number is the same whichever test ran before. */
    private void forgetEveryCachedConfigAnswer() {
        HotpathCaches.evictTenantConfig(null);
        HotpathCaches.evictPlatformConfig();
    }

    @Test
    void resolvingToolsForOneTurnStaysWithinTheRecordedBudget() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        // Cold: nothing cached yet on this pod for this tenant and agent.
        forgetEveryCachedConfigAnswer();
        stats.clear();
        toolProvider.resolveToolsFor(
                agentId, "s-cold", "u-cold", "hello there", null, ReasoningDepth.FAST);
        long coldConnections = stats.getConnectCount();
        long coldStatements = stats.getPrepareStatementCount();

        // Warm: the steady state of a busy agent, which is what almost every turn looks like.
        stats.clear();
        toolProvider.resolveToolsFor(
                agentId, "s-1", "u-1", "hello there", null, ReasoningDepth.FAST);
        long connections = stats.getConnectCount();
        long statements = stats.getPrepareStatementCount();

        // The lines to read when this test is run on purpose.
        System.out.println(
                "HOTPATH-BUDGET resolveToolsFor: cold connections="
                        + coldConnections
                        + " statements="
                        + coldStatements);
        System.out.println(
                "HOTPATH-BUDGET resolveToolsFor: warm connections="
                        + connections
                        + " statements="
                        + statements);

        assertThat(connections)
                .as("budget: raise it only with a reason, and say what the new query buys")
                .isLessThanOrEqualTo(BUDGET_CONNECTIONS);
        assertThat(statements).isLessThanOrEqualTo(BUDGET_STATEMENTS);
        assertThat(coldConnections)
                .as("a cold turn pays for the lookups once")
                .isGreaterThan(connections);
    }

    /**
     * The whole turn, from the pre-flight checks to the first token the client would see, against a
     * mock model that answers at once. Counts every connection checkout and statement in between,
     * on every thread (Hibernate's statistics are global; the test is the only user).
     */
    @Test
    void aWholeTurnToTheFirstTokenStaysWithinTheRecordedBudget() throws Exception {
        tenantSchema(TURN_SCHEMA, TenantPlan.UNLIMITED);
        TenantContext.set(TenantContext.of(TURN_SCHEMA, "it-user", Set.of()));
        String baseUrl =
                "http://"
                        + modelServer.getAddress().getAddress().getHostAddress()
                        + ":"
                        + modelServer.getAddress().getPort();
        providerService.activate(
                new ProviderActivationRequest(
                        ProviderName.OLLAMA, new OllamaCredential(baseUrl, null).encode()));
        UUID turnAgent = newAgent(ProviderName.OLLAMA, "mock:latest");
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        forgetEveryCachedConfigAnswer();
        long[] cold = measureOneTurn(stats, turnAgent, "turn-cold");
        long[] warm = measureOneTurn(stats, turnAgent, "turn-warm");
        long[] warmAgain = measureOneTurn(stats, turnAgent, "turn-warm-2");

        System.out.println(
                "HOTPATH-BUDGET whole turn: cold connections="
                        + cold[0]
                        + " statements="
                        + cold[1]);
        System.out.println(
                "HOTPATH-BUDGET whole turn: warm connections="
                        + warm[0]
                        + " statements="
                        + warm[1]);
        System.out.println(
                "HOTPATH-BUDGET whole turn: warm-2 connections="
                        + warmAgain[0]
                        + " statements="
                        + warmAgain[1]);

        assertThat(warm[0])
                .as("budget: raise it only with a reason, and say what the new query buys")
                .isLessThanOrEqualTo(BUDGET_TURN_CONNECTIONS);
        assertThat(warm[1]).as(java.util.Arrays.toString(stats.getQueries())).isLessThanOrEqualTo(BUDGET_TURN_STATEMENTS);
        assertThat(warmAgain[0]).isLessThanOrEqualTo(BUDGET_TURN_CONNECTIONS);
        assertThat(warmAgain[1]).as(java.util.Arrays.toString(stats.getQueries())).isLessThanOrEqualTo(BUDGET_TURN_STATEMENTS);
        assertThat(cold[0]).as("a cold turn pays for the lookups once").isGreaterThan(warm[0]);
    }

    /** {connections, statements} counted from the call to the first emitted token. */
    private long[] measureOneTurn(Statistics stats, UUID turnAgent, String session)
            throws InterruptedException {
        AtomicReference<long[]> atFirstToken = new AtomicReference<>();
        stats.clear();
        chatService
                .chat(turnAgent, session, "it-user", "hello there", null, null)
                .doOnNext(
                        token ->
                                atFirstToken.compareAndSet(
                                        null,
                                        new long[] {
                                            stats.getConnectCount(),
                                            stats.getPrepareStatementCount()
                                        }))
                .blockLast(Duration.ofSeconds(60));
        assertThat(atFirstToken.get()).as("the turn produced a first token").isNotNull();
        // Let the post-answer work (history, audit, call log) finish before the next measurement.
        Thread.sleep(2000);
        return atFirstToken.get();
    }

    /**
     * An edit on this pod must be visible to the very next read: a Hibernate listener evicts the
     * cached agent snapshot at flush and again after commit. Goes through the real listener wiring,
     * not a mock.
     */
    @Test
    void anAgentEditIsVisibleToTheNextReadOnThisPod() {
        assertThat(agentService.isKnowledgeBaseEnabled(agentId)).isTrue();
        assertThat(agentService.isKnowledgeBaseEnabled(agentId))
                .isTrue(); // second read is a cache hit

        tx.executeWithoutResult(
                status -> {
                    Agent a = agents.findById(agentId).orElseThrow();
                    a.setKnowledgeBaseEnabled(false);
                    agents.save(a);
                });

        assertThat(agentService.isKnowledgeBaseEnabled(agentId)).isFalse();
    }

    /**
     * A tenant config answer is cached, and a write to the entity it is read from on this pod is
     * visible to the very next read: the Hibernate listener evicts at flush and after commit. Real
     * wiring, not a mock.
     */
    @Test
    void aConfigWriteIsVisibleToTheNextRead() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        assertThat(specialistRegistry.listDistinctCapabilityTags()).isEmpty();

        stats.clear();
        specialistRegistry.listDistinctCapabilityTags();
        assertThat(stats.getConnectCount())
                .as("the second read is a cache hit: no connection")
                .isZero();

        tx.executeWithoutResult(
                status ->
                        specializations.save(
                                new com.agenticai.core.agents.crew.AgentSpecialization(
                                        agentId, "billing", "answers billing questions")));

        assertThat(specialistRegistry.listDistinctCapabilityTags()).containsExactly("billing");
    }

    private List<String> offeredToolNames() {
        return toolProvider
                .resolveToolsFor(
                        agentId,
                        "s-names",
                        "u-names",
                        "run it_double and it_triple to double and triple x",
                        null,
                        ReasoningDepth.FAST)
                .stream()
                .map(t -> t.getToolDefinition().name())
                .toList();
    }

    /**
     * The snapshots of the tenant's own tools (here a user script) follow a write on this pod at
     * once: creating one makes the next resolution offer it, disabling it makes the next one stop,
     * with the cache on. Goes through the real listener wiring, not a mock.
     */
    @Test
    void aToolWriteIsVisibleToTheNextResolution() {
        assertThat(offeredToolNames()).doesNotContain("it_double");
        assertThat(offeredToolNames()).doesNotContain("it_double"); // now a cache hit

        UserToolScript script =
                userScripts.create("it_double", "Double", "Doubles x", "#x * 2", null);
        try {
            assertThat(offeredToolNames()).contains("it_double");

            userScripts.setEnabled(script.getId(), false);
            assertThat(offeredToolNames()).doesNotContain("it_double");
        } finally {
            userScripts.delete(script.getId());
        }
    }

    /**
     * {@code app.chat.hotpath.config-lookup-ttl=0s} must restore the old behaviour exactly: the
     * same tools are offered, and every config read goes to the database again on every turn, so a
     * warm turn costs what a cold one does.
     */
    @Test
    void aLookupTtlOfZeroOffersTheSameToolsAndReadsTheDatabaseEveryTime() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        UserToolScript script =
                userScripts.create("it_triple", "Triple", "Triples x", "#x * 3", null);
        try {
            forgetEveryCachedConfigAnswer();
            offeredToolNames(); // fills the agent snapshot and the config lookups
            stats.clear();
            List<String> cached = offeredToolNames();
            long cachedConnections = stats.getConnectCount();

            HotpathLookups.configure(Duration.ZERO);
            offeredToolNames(); // the agent snapshot stays on; only config lookups are off
            stats.clear();
            List<String> uncached = offeredToolNames();
            long uncachedConnections = stats.getConnectCount();

            System.out.println(
                    "HOTPATH-BUDGET resolveToolsFor: config lookups off connections="
                            + uncachedConnections
                            + " (on: "
                            + cachedConnections
                            + ")");
            assertThat(uncached).containsExactlyInAnyOrderElementsOf(cached);
            assertThat(cached).contains("it_triple");
            assertThat(uncachedConnections)
                    .as("with the lookup cache off every config read is a transaction again")
                    .isGreaterThan(cachedConnections + 5);
        } finally {
            HotpathLookups.configure(LOOKUP_TTL);
            userScripts.delete(script.getId());
        }
    }

    /**
     * Recorded on 2026-10-13 against Postgres 16 in a throwaway container, a default agent with no
     * integrations, tool resolution only: 52 connections and 55 statements before the agent
     * snapshot cache, 23 and 26 after it, 8 and 11 with the config lookup cache, and 0 and 0 once
     * the last eight transactions (Google status, the hosted image pool, skills, user scripts,
     * custom code tools, OpenAPI tools) were behind it too. A warm resolution reads nothing; one
     * connection of slack is all that is allowed. Raise it only with a reason.
     */
    private static final long BUDGET_CONNECTIONS = 1;

    private static final long BUDGET_STATEMENTS = 1;

    /**
     * What one plan lookup costs. A turn used to make up to a dozen of them (preflight, inbound
     * guardrails, the gateway's budget and rate-limit checks per client, one per RL dimension); it
     * now makes one and hands the plan down (counts asserted in {@code ChatTurnLookupCountTest} and
     * the per-component tests). Each remaining lookup is one connection checkout, which is what
     * {@code app.chat.hotpath.plan-cache-ttl} could save per turn.
     */
    @Test
    void aPlanLookupCostsOneConnection() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        stats.clear();
        TenantPlan plan = tenantLimits.plan(SCHEMA);
        long connections = stats.getConnectCount();
        long statements = stats.getPrepareStatementCount();

        System.out.println(
                "HOTPATH-BUDGET tenantLimits.plan: connections="
                        + connections
                        + " statements="
                        + statements
                        + " plan="
                        + plan);

        assertThat(connections).as("one plan lookup is one connection").isEqualTo(1);
    }

    /**
     * What one turn pays to evaluate the tenant's flags, and what passing the evaluation on saves.
     * Version pin, effective-agent overlay and experiment variants each used to evaluate them; the
     * turn now evaluates once and hands the map on, so pinning with the map costs less than pinning
     * without it by exactly one evaluation.
     */
    @Test
    void passingTheTurnsFlagsOnSavesTheVersionPinItsOwnEvaluation() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        Agent live = agents.findById(agentId).orElseThrow();

        stats.clear();
        featureFlags.evaluateActiveFlags(SCHEMA);
        long evaluationConnections = stats.getConnectCount();

        // Warm the version lookup so both measured calls are steady state.
        versionResolution.pinAndApply(agentId, live, Map.of());
        stats.clear();
        versionResolution.pinAndApply(agentId, live, Map.of());
        long passedOn = stats.getConnectCount();
        stats.clear();
        versionResolution.pinAndApply(agentId, live, null);
        long evaluatedItself = stats.getConnectCount();

        System.out.println(
                "HOTPATH-BUDGET flags: one evaluation connections="
                        + evaluationConnections
                        + "; version pin with the map handed down="
                        + passedOn
                        + ", evaluating itself="
                        + evaluatedItself
                        + " (a turn used to evaluate three times)");

        assertThat(evaluationConnections).isPositive();
        assertThat(evaluatedItself - passedOn)
                .as("handing the map on saves exactly one evaluation")
                .isEqualTo(evaluationConnections);
    }

    /**
     * The whole turn to the first token (pre-flight, plan, guardrails, memory recall, tool
     * resolution, prompt assembly, the model call), warm, against the mock model, recorded on
     * 2026-10-13: 15 connections and 14 statements (44 and 46 cold). Two of slack. The statements
     * that remain are listed in docs/architecture/HOT-PATH-PERFORMANCE.md; lower these as they are
     * removed. Raised to 17 on 2026-10-20: the warm turn now issues 17 measured statements -
     * the two slack slots plus one. The newcomers are the RL turn recorder's per-turn
     * bookkeeping (rl_episode open-episode lookup, rl_trajectory_step step count) and the
     * agent_job/webhook checks that join prompt assembly; they buy episode continuity for
     * reward attribution and job-aware turns. rl_trajectory_step could become a cached
     * counter later; until then the queries are part of the recorded budget.
     * Statements raised to 18 on 2026-10-23: a short message (at most {@code
     * app.chat.follow-up.short-message-chars}, 40 by default, which "hello there" is) is a possible
     * follow-up, so the turn reads the session's tool-call log once
     * ({@code FollowUpTools#stickyTools}: {@code tool_call_log} by agent, session and window) to keep
     * the tools the conversation already used on offer and to leave a tool task on its own model. It
     * rides on a connection the turn already holds (connections stay 17). A message longer than the
     * limit pays nothing.
     */
    private static final long BUDGET_TURN_CONNECTIONS = 17;

    private static final long BUDGET_TURN_STATEMENTS = 18;
}
