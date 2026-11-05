package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.learning.consolidation.MemoryConsolidationService;
import com.agenticai.core.agents.learning.consolidation.MemoryConsolidationSweep;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memorystore.MemoryEmbedder;
import com.agenticai.core.agents.memorystore.MemoryItemReviewService;
import com.agenticai.core.agents.memorystore.StoreMemoryProposalSink;
import com.agenticai.core.agents.skills.EpisodeRecordingGateway;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.it.memeval.ExtractiveProbeAnswerer;
import com.agenticai.core.it.memeval.HashingTextEmbedder;
import com.agenticai.core.it.memeval.PlatformEvalSubject;
import com.agenticai.core.it.memeval.PlatformEvalWorld;
import com.agenticai.core.it.memeval.StatementCounter;
import com.agenticai.core.knowledge.embedding.EmbeddingClient;
import com.agenticai.core.quality.memeval.MemoryEvalCli;
import com.agenticai.core.quality.memeval.MemoryScenario;
import com.agenticai.core.quality.memeval.MemoryScenarioLoader;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Context;
import com.agenticai.core.quality.memeval.MemoryScorecardGate.Tier;
import com.agenticai.core.quality.memeval.PoisonScenarioGenerator;
import com.agenticai.core.settings.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The memory scorecard measured on the REAL pipeline instead of the in-memory reference subject
 * (scorecard doc, Addendum E): the whole {@code CoreApplication} on a real Postgres with the real
 * tenant schema, a fresh tenant and user per (case, arm, repeat), writes through the real {@code
 * remember_fact} tool and the episode proposal sink, a scripted reviewer, probes through the real
 * recall composition, and the dataset of {@code core/quality} (8 personas, S1-S15, 40 mistake
 * cases, the 180 generated poisoning scenarios) scored by the unchanged scorers, statistics and
 * gates.
 *
 * <p>Writes {@code target/memory-eval/memory-scorecard-<tier>.json} per tier, and {@code
 * memory-scorecard.json} for the tier named by {@code -Dmemeval.tier} (default {@code ci}; {@code
 * nightly} or {@code both}), in the schema of {@code
 * docs/architecture/phase1/memory-scorecard.schema.json}, plus a {@code platform} block with what
 * the run observed. The IT asserts harness integrity (the B arm persisted nothing, the file is well
 * formed), never the verdict: {@code scripts/ci/memory-scorecard-gate.sh} turns the verdict into an
 * exit code.
 *
 * <p>Needs {@code -Dvegaduta.it.pg.url=...} (see {@link ExternalPostgres}) and Redis like the other
 * unified-memory ITs; skipped otherwise. Knobs: {@code -Dmemeval.tier=ci|nightly|both}, {@code
 * -Dmemeval.repeats=N}, {@code -Dmemeval.limit=N} (first N hand-written plus N/6 poisoning
 * scenarios, for development only; the report says so), {@code -Dmemeval.compliant=false}.
 */
@TestPropertySource(
        properties = {
            "app.memory.unified.enabled=true",
            "app.memory.unified.kinds=episodic,procedural,self-model",
            "app.memory.episodic.enabled=true",
            "app.memory.consolidation.enabled=true"
        })
@Import({PlatformMemoryEvalIT.EvalConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformMemoryEvalIT extends UnifiedMemoryBootBase {

    private static final ObjectMapper JSON =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path OUT_DIR = Path.of("target", "memory-eval");
    private static final long SEED = 20261105L;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        register(registry, "memeval_platform");
    }

    @BeforeAll
    static void needsPostgres() {
        requirePostgres();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class EvalConfiguration {
        @Bean
        @Primary
        MemoryEmbedder memevalMemoryEmbedder() {
            return HashingTextEmbedder::embed;
        }

        @Bean
        EmbeddingClient memevalEmbeddingClient() {
            return new HashingTextEmbedder();
        }

        /** Wraps every DataSource so the eval can count statements per recall. */
        @Bean
        static BeanPostProcessor memevalStatementCounting() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName)
                        throws BeansException {
                    return bean instanceof DataSource ds
                                    && !"false".equals(System.getProperty("memeval.count"))
                            ? StatementCounter.wrap(ds)
                            : bean;
                }
            };
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired SettingsService settings;
    @Autowired AgentMemoryService agentMemory;
    @Autowired TenantMemoryService tenantMemory;
    @Autowired MemoryItemReviewService review;
    @Autowired AgentChatService chat;
    @Autowired MemoryProposalSink sink;
    @Autowired ObjectProvider<MemoryConsolidationService> consolidation;
    @Autowired ObjectProvider<MemoryConsolidationSweep> sweep;

    private PlatformEvalWorld world() {
        PlatformEvalWorld w = new PlatformEvalWorld();
        w.jdbc = jdbc;
        w.tenants = tenants;
        w.migrations = migrations;
        w.agents = agents;
        w.tx = tx;
        w.settings = settings;
        w.agentMemory = agentMemory;
        w.tenantMemory = tenantMemory;
        w.review = review;
        w.chat = chat;
        w.consolidation = consolidation.getIfAvailable();
        w.sweep = sweep.getIfAvailable();
        return w;
    }

    @Test
    @Order(0)
    void theEvalRunsOnTheRealBeansWithTheFakeEmbedder() {
        assertThat(sink).isInstanceOf(StoreMemoryProposalSink.class);
        assertThat(EpisodeRecordingGateway.installed())
                .as("the episode recorder installed its hook")
                .isTrue();
        assertThat(agentMemory.queryEmbedding("gold sla is 2 hours").vector())
                .as("the legacy fact store embeds with the eval embedder, not a sidecar")
                .containsExactly(HashingTextEmbedder.embed("gold sla is 2 hours"));
    }

    @Autowired com.agenticai.core.settings.SettingsResolver resolver;

    /**
     * The arm switches are read exactly as production reads them, with no transaction around the
     * read (defect D1, fixed: the settings store qualifies its tables with the tenant schema). A
     * tenant-scope override written through the real settings service must be visible to the
     * resolver both outside and inside a transaction; see also {@code SettingsTenantReadIT}.
     */
    @Test
    @Order(0)
    void settingsOverrideVisibilityOutsideAndInsideATransaction() {
        PlatformEvalWorld w = world();
        String schema = "t_evsettingsprobe000001";
        w.createTenant(schema);
        java.util.UUID agent = w.newAgent(schema, "settings-probe", false);
        w.setTenantBoolean(schema, "memory.recall.enabled", false);
        com.agenticai.core.security.TenantContext.set(
                com.agenticai.core.security.TenantContext.of(schema, "u", java.util.Set.of()));
        try {
            resolver.evictCurrentTenant();
            Object outside =
                    resolver.get(
                                    "memory.recall.enabled",
                                    com.agenticai.core.settings.SettingContext.forAgent(agent))
                            .value();
            resolver.evictCurrentTenant();
            Object inside =
                    tx.execute(
                            st ->
                                    resolver.get(
                                                    "memory.recall.enabled",
                                                    com.agenticai.core.settings.SettingContext
                                                            .forAgent(agent))
                                            .value());
            System.out.println(
                    "SETTINGS VISIBILITY outside a transaction: "
                            + outside
                            + ", inside: "
                            + inside);
            assertThat(inside)
                    .as("a tenant override is visible inside a transaction")
                    .isEqualTo(false);
            assertThat(outside)
                    .as("a tenant override is visible outside a transaction (the chat turn's read)")
                    .isEqualTo(false);
        } finally {
            com.agenticai.core.security.TenantContext.clear();
            w.dropTenant(schema);
        }
    }

    @Test
    @Order(1)
    void scorecard() throws IOException {
        String which = System.getProperty("memeval.tier", "ci").toLowerCase(Locale.ROOT);
        List<Tier> tiers = new ArrayList<>();
        if (which.equals("ci") || which.equals("both")) {
            tiers.add(Tier.CI);
        }
        if (which.equals("nightly") || which.equals("both")) {
            tiers.add(Tier.NIGHTLY);
        }
        assertThat(tiers).as("-Dmemeval.tier must be ci, nightly or both").isNotEmpty();
        PlatformEvalWorld world = world();
        try {
            for (Tier tier : tiers) {
                runTier(world, tier, which);
            }
        } finally {
            Clock system = Clock.systemUTC();
            agentMemory.useClock(system);
            tenantMemory.useClock(system);
            if (world.consolidation != null) {
                world.consolidation.useClock(system);
            }
        }
    }

    private void runTier(PlatformEvalWorld world, Tier tier, String requested) throws IOException {
        int repeats = Integer.getInteger("memeval.repeats", tier == Tier.CI ? 1 : 3);
        boolean compliant =
                !"false".equalsIgnoreCase(System.getProperty("memeval.compliant", "true"));
        List<MemoryScenario> scenarios = scenarios();
        Integer limit = Integer.getInteger("memeval.limit");
        if (limit != null) {
            List<MemoryScenario> hand = MemoryScenarioLoader.loadManifest();
            List<MemoryScenario> poison = PoisonScenarioGenerator.generate();
            scenarios = new ArrayList<>(hand.subList(0, Math.min(limit, hand.size())));
            scenarios.addAll(poison.subList(0, Math.min(Math.max(1, limit / 6), poison.size())));
        }
        PlatformEvalSubject subject =
                new PlatformEvalSubject(
                        world,
                        new ExtractiveProbeAnswerer(compliant),
                        PlatformEvalSubject.Assumptions.DEFAULT);
        Context ctx = tier == Tier.CI ? Context.ci() : Context.nightly();
        long t0 = System.nanoTime();
        MemoryEvalCli.Result result =
                MemoryEvalCli.run(subject, scenarios, repeats, SEED, ctx, tier == Tier.NIGHTLY);
        Duration took = Duration.ofNanos(System.nanoTime() - t0);

        ObjectNode doc = (ObjectNode) JSON.readTree(MemoryEvalCli.toJson(result, Instant.now()));
        ObjectNode platform = doc.putObject("platform");
        platform.put("subject", subject.name());
        platform.put("tier", tier.name());
        platform.put("repeats", repeats);
        platform.put("scenarios", scenarios.size());
        platform.put("limited", limit != null);
        platform.put("compliantToolAgent", compliant);
        platform.put(
                "embedder",
                "HashingTextEmbedder (384-d signed feature hashing; lexical; replaces the sidecar)");
        platform.put(
                "answerer",
                "ExtractiveProbeAnswerer (threshold "
                        + ExtractiveProbeAnswerer.THRESHOLD
                        + ", no LLM)");
        platform.put(
                "tenantProvisioning",
                "template schema migrated by Flyway once, clones via CREATE TABLE LIKE per case");
        ObjectNode assumed = platform.putObject("assumedPerTurn");
        PlatformEvalSubject.Assumptions a = PlatformEvalSubject.Assumptions.DEFAULT;
        assumed.put("systemPromptTokens", a.systemPromptTokens());
        assumed.put("ingestReplyTokens", a.ingestReplyTokens());
        assumed.put("modelMs", a.modelMs());
        assumed.put("modelTtftMs", a.modelTtftMs());
        platform.put("wallClockSeconds", took.toSeconds());
        platform.set("observed", subject.diagnostics().toJson());

        Files.createDirectories(OUT_DIR);
        String text = JSON.writeValueAsString(doc);
        Files.writeString(
                OUT_DIR.resolve(
                        "memory-scorecard-" + tier.name().toLowerCase(Locale.ROOT) + ".json"),
                text);
        boolean primary = requested.equals("both") ? tier == Tier.NIGHTLY : true;
        if (primary) {
            Files.writeString(OUT_DIR.resolve("memory-scorecard.json"), text);
        }
        System.out.println(
                "PLATFORM MEMORY EVAL "
                        + tier
                        + " verdict "
                        + result.report().verdict().label()
                        + " in "
                        + took.toSeconds()
                        + " s");
        result.report()
                .gates()
                .forEach(
                        g ->
                                System.out.println(
                                        "  " + g.id() + " " + g.status() + " " + g.detail()));

        assertThat(doc.path("gates")).hasSize(11);
        ObjectNode b = (ObjectNode) doc.path("platform").path("observed").path("arms").path("B");
        assertThat(b.path("writesSaved").asInt() + b.path("writesQuarantined").asInt())
                .as("the B arm (memory off) must persist no fact")
                .isZero();
        assertThat(
                        world.jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.schemata WHERE schema_name LIKE 't\\_ev%'",
                                Integer.class))
                .as("every case's tenant schema was dropped")
                .isZero();
    }

    private static List<MemoryScenario> scenarios() {
        return MemoryScenarioLoader.loadAll();
    }
}
