package com.agenticai.core.it.memeval;

import com.agenticai.core.agents.memory.RememberFactToolCallback;
import com.agenticai.core.agents.memory.RememberSharedFactToolCallback;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.TurnTaintSnapshot;
import com.agenticai.core.agents.memory.TurnTaintView;
import com.agenticai.core.agents.memorystore.MemoryActor;
import com.agenticai.core.agents.memorystore.MemoryItem;
import com.agenticai.core.agents.memorystore.MemoryItemStatus;
import com.agenticai.core.agents.skills.EpisodeRecordingGateway;
import com.agenticai.core.agents.turn.PlatformRecallBridge;
import com.agenticai.core.agents.turn.TurnMemoryRecall;
import com.agenticai.core.guardrails.taint.TurnTaint;
import com.agenticai.core.it.memeval.ProbeAnswerer.Answer;
import com.agenticai.core.it.memeval.ProbeAnswerer.RecalledLine;
import com.agenticai.core.quality.memeval.CaseEnvironment;
import com.agenticai.core.quality.memeval.MemoryArm;
import com.agenticai.core.quality.memeval.MemoryRun.InjectedItem;
import com.agenticai.core.quality.memeval.MemoryRun.ProbeOutcome;
import com.agenticai.core.quality.memeval.MemoryRun.StoreItem;
import com.agenticai.core.quality.memeval.MemoryRun.TurnCost;
import com.agenticai.core.quality.memeval.MemoryScenario;
import com.agenticai.core.quality.memeval.MemoryScenario.Fact;
import com.agenticai.core.quality.memeval.MemoryScenario.FactKind;
import com.agenticai.core.quality.memeval.MemoryScenario.Probe;
import com.agenticai.core.quality.memeval.MemoryScenario.Session;
import com.agenticai.core.quality.memeval.MemoryScenario.Turn;
import com.agenticai.core.quality.memeval.MemoryScenario.TurnSource;
import com.agenticai.core.quality.memeval.PlatformMemoryEvalSubject;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Flux;

/**
 * The memory eval against the booted application (see {@link PlatformMemoryEvalSubject} for the
 * contract). Per (scenario, arm, repeat): fresh tenant schemas and users, one agent per tenant
 * whose config is identical across arms except for the two memory switches (set through the real
 * settings service), the {@code EvalClock} installed in the memory services, and
 *
 * <ul>
 *   <li><b>ingestion</b> through the real write path: each ledger fact a turn plants is saved by
 *       the real {@code remember_fact} tool callback (write screen, turn-taint gate, {@code
 *       memory.write.enabled}, embedding, per-agent cap), with the turn's channel marking a real
 *       {@code TurnTaint} for tool-output, document and agent-message turns; one episode per
 *       session goes through {@code EpisodeRecordingGateway} and the proposal sink;
 *   <li>a <b>scripted reviewer</b> standing in for the human: after each session it approves the
 *       proposals the platform did not flag (empty taint) and leaves every quarantined one alone;
 *   <li><b>probes</b> through the real recall composition ({@code ChatMemoryRecall}: legacy private
 *       and shared facts, unified recall, trust filter, token budget) on a different thread than
 *       the writer, answered by a pluggable {@link ProbeAnswerer};
 *   <li>the recall trace from the turn's {@code TurnMemoryRecall.Trace} (the object the turn
 *       journal's {@code memory_recall} events are written from; the persisted event rows are not
 *       read), each id resolved to its row in whichever case schema holds it, so a row of another
 *       user or tenant surfaces as such.
 * </ul>
 *
 * Not real: the extractor (the ledger's {@code plants} decide what is saved, with the fact's clean
 * statement as text), the model (see {@link ProbeAnswerer}), the embedder ({@link
 * HashingTextEmbedder}), the human reviewer, and the assumed model time and system prompt of a turn
 * ({@link Assumptions}).
 */
public final class PlatformEvalSubject extends PlatformMemoryEvalSubject {

    /**
     * What a turn costs apart from memory, since there is no model call to measure: tokens of the
     * agent's system prompt and of its reply to an ingestion turn, and model time to first token
     * and in total. Measured pipeline time (recall) is added on top, so the overhead of memory is
     * real and the denominator is an assumption; both are reported in the scorecard's {@code
     * platform} block.
     */
    public record Assumptions(
            int systemPromptTokens, int ingestReplyTokens, double modelMs, double modelTtftMs) {
        public static final Assumptions DEFAULT = new Assumptions(380, 50, 800.0, 400.0);
    }

    private static final MemoryActor REVIEWER = MemoryActor.human("memeval-reviewer");
    static final ObjectMapper JSON = new ObjectMapper();
    static final String UNANSWERED = "unanswered";

    /**
     * {@code -Dmemeval.trace=true} prints one line per probe (question, answer, tools, injected
     * rows) for debugging a run.
     */
    private static final boolean TRACE = Boolean.getBoolean("memeval.trace");

    /**
     * {@code -Dmemeval.replaces=false} (default true): whether the extractor names the earlier fact
     * a correction replaces (the tool's {@code replaces} argument). The extractor of this eval is
     * the ledger, which knows every correction, so on it behaves like a model that always copies
     * the right fact from its recalled memory: the run measures the plumbing (resolution, trust
     * rules, recall exclusion), not the quality of a real arbiter. With {@code false} only the
     * automatic similarity rule can supersede, which is what a model that never uses the argument
     * would get.
     */
    private static final boolean NAME_REPLACED_FACT =
            !"false".equalsIgnoreCase(System.getProperty("memeval.replaces", "true"));

    final PlatformEvalWorld world;
    private final ProbeAnswerer answerer;
    private final Assumptions assumptions;
    final PlatformEvalDiagnostics diagnostics = new PlatformEvalDiagnostics();
    final Map<String, Case> cases = new HashMap<>();
    private final ExecutorService probePool =
            Executors.newFixedThreadPool(
                    2,
                    r -> {
                        Thread t = new Thread(r, "memeval-probe");
                        t.setDaemon(true);
                        return t;
                    });
    private final ExecutorService hopPool =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "memeval-consolidation-hop");
                        t.setDaemon(true);
                        return t;
                    });

    public PlatformEvalSubject(
            PlatformEvalWorld world, ProbeAnswerer answerer, Assumptions assumptions) {
        this.world = world;
        this.answerer = answerer;
        this.assumptions = assumptions;
    }

    public PlatformEvalDiagnostics diagnostics() {
        return diagnostics;
    }

    @Override
    public String name() {
        return "platform-" + answerer.name();
    }

    // ------------------------------------------------------------------ per-case state

    /**
     * What the harness knows about one stored row (the platform knows none of it: it is the ledger
     * mapping).
     */
    record RowInfo(String factId, String key, boolean shared) {}

    static final class Case {
        final MemoryScenario scenario;
        final MemoryArm arm;
        final CaseEnvironment env;
        final Map<String, String> schemaByTenant = new LinkedHashMap<>();
        final Map<String, UUID> agentByTenant = new HashMap<>();
        final Map<UUID, RowInfo> rows = new HashMap<>();
        final Map<UUID, String> agentTenant = new HashMap<>();

        Case(MemoryScenario scenario, MemoryArm arm, CaseEnvironment env) {
            this.scenario = scenario;
            this.arm = arm;
            this.env = env;
        }

        String schema(String logicalTenant) {
            return schemaByTenant.get(logicalTenant);
        }

        UUID agent(String logicalTenant) {
            return agentByTenant.get(logicalTenant);
        }

        String subject(String logicalUser) {
            return env.identity().userId(logicalUser);
        }

        String logicalTenantOfSchema(String schema) {
            for (Map.Entry<String, String> e : schemaByTenant.entrySet()) {
                if (e.getValue().equals(schema)) {
                    return e.getKey();
                }
            }
            return schema;
        }
    }

    Case of(CaseEnvironment env) {
        Case c = cases.get(env.identity().runKey());
        if (c == null) {
            throw new IllegalStateException("no provisioned case for " + env.identity().runKey());
        }
        return c;
    }

    // ------------------------------------------------------------------ provision

    @Override
    protected void provision(MemoryScenario scenario, MemoryArm arm, CaseEnvironment env) {
        Case c = new Case(scenario, arm, env);
        cases.put(env.identity().runKey(), c);
        world.agentMemory.useClock(env.clock());
        world.tenantMemory.useClock(env.clock());
        if (world.consolidation != null) {
            world.consolidation.useClock(env.clock());
        }
        boolean sharing = scenario.sessions().stream().anyMatch(s -> !s.shares().isEmpty());
        for (String logical : logicalTenants(scenario)) {
            String schema = schemaName(env, logical);
            world.createTenant(schema);
            UUID agent = world.newAgent(schema, "memeval-" + logical, sharing);
            if (!env.switches().recall()) {
                world.setTenantBoolean(schema, "memory.recall.enabled", false);
            }
            if (!env.switches().write()) {
                world.setTenantBoolean(schema, "memory.write.enabled", false);
            }
            c.schemaByTenant.put(logical, schema);
            c.agentByTenant.put(logical, agent);
            c.agentTenant.put(agent, logical);
        }
    }

    private static Set<String> logicalTenants(MemoryScenario s) {
        Set<String> out = new LinkedHashSet<>();
        s.sessions().forEach(x -> out.add(x.tenantId()));
        s.probes().forEach(p -> out.add(p.tenantId()));
        s.ledger()
                .forEach(
                        f -> {
                            if (f.ownerTenant() != null) {
                                out.add(f.ownerTenant());
                            }
                        });
        return out;
    }

    /**
     * A schema name the tenant guard accepts ({@code t_} + 16 to 40 lower-case alphanumerics) and
     * unique to the case.
     */
    static String schemaName(CaseEnvironment env, String logicalTenant) {
        return "t_"
                + env.identity().runKey()
                + logicalTenant.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // ------------------------------------------------------------------ replay

    @Override
    protected List<TurnCost> replaySession(CaseEnvironment env, Session session) {
        Case c = of(env);
        String schema = c.schema(session.tenantId());
        UUID agent = c.agent(session.tenantId());
        String subject = c.subject(session.userId());
        TenantContext ctx = TenantContext.of(schema, subject, Set.of());
        List<TurnCost> costs = new ArrayList<>();
        TenantContext.set(ctx);
        try {
            TurnTaint sessionTaint = new TurnTaint();
            for (Turn turn : session.turns()) {
                TurnTaint taint = new TurnTaint();
                if (turn.source() != TurnSource.USER) {
                    String label = channelLabel(turn.source());
                    taint.mark(label);
                    sessionTaint.mark(label);
                }
                Recalled pre = recall(c, agent, subject, turn.text(), true);
                for (String factId : turn.plants()) {
                    Fact fact = c.scenario.factsById().get(factId);
                    if (fact != null) {
                        write(c, session, agent, subject, fact, turn, taint);
                    }
                }
                costs.add(turnCost(turn.text(), pre, assumptions.ingestReplyTokens()));
            }
            applyShares(c, session);
            applyForgets(c, session, agent);
            recordEpisode(c, session, agent, subject, ctx, sessionTaint);
        } finally {
            TenantContext.clear();
        }
        if (env.switches().write() && c.scenario.id().startsWith("s15") && world.sweep != null) {
            consolidationHop();
        }
        return costs;
    }

    private static String channelLabel(TurnSource source) {
        return switch (source) {
            case TOOL_OUTPUT -> "tool:output";
            case DOCUMENT -> "document:retrieved";
            case AGENT_MESSAGE -> "agent:peer";
            case USER -> "user";
        };
    }

    private static ObjectProvider<TurnTaintView> taintProvider(TurnTaint taint) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("turnTaint", TurnTaintSnapshot.of(taint));
        return factory.getBeanProvider(TurnTaintView.class);
    }

    /**
     * One planted fact through the real {@code remember_fact} tool (the turn's thread, the
     * session's user).
     */
    private void write(
            Case c,
            Session session,
            UUID agent,
            String subject,
            Fact fact,
            Turn turn,
            TurnTaint taint) {
        String text = fact.kind() == FactKind.ATTACK ? turn.text() : fact.value();
        String replaces =
                NAME_REPLACED_FACT && fact.kind() != FactKind.ATTACK ? replacedText(c, fact) : null;
        String input =
                replaces == null
                        ? PlatformEvalSubjectOps.json("fact", text)
                        : PlatformEvalSubjectOps.json("fact", text, "replaces", replaces);
        String result =
                new RememberFactToolCallback(
                                world.agentMemory,
                                agent,
                                subject,
                                null,
                                null,
                                null,
                                null,
                                taintProvider(taint))
                        .call(input);
        diagnostics.write(c.arm, result);
        if (fact.kind() == FactKind.ATTACK) {
            diagnostics.attackWrite(c.arm, turn.source().name(), result);
        }
        UUID id = PlatformEvalSubjectOps.memoryId(result);
        if (id != null) {
            c.rows.put(id, new RowInfo(fact.id(), fact.key(), false));
        }
    }

    /**
     * The text of the earlier fact the ledger says {@code fact} corrects, when that fact was
     * planted earlier; else null.
     */
    private static String replacedText(Case c, Fact fact) {
        for (Fact old : c.scenario.ledger()) {
            if (fact.id().equals(old.supersededBy())
                    && old.kind() != FactKind.ATTACK
                    && c.scenario.plantedAtIndex(old.id()) >= 0) {
                return old.value();
            }
        }
        return null;
    }

    /**
     * The real {@code remember_shared_fact} for each fact the session shares, as the fact's owner.
     */
    private void applyShares(Case c, Session session) {
        for (String factId : session.shares()) {
            Fact fact = c.scenario.factsById().get(factId);
            if (fact == null) {
                continue;
            }
            String owner =
                    c.scenario.ownerUser(fact) == null
                            ? session.userId()
                            : c.scenario.ownerUser(fact);
            UUID agent = c.agent(session.tenantId());
            String result =
                    new RememberSharedFactToolCallback(world.tenantMemory, agent, c.subject(owner))
                            .call(PlatformEvalSubjectOps.json("fact", fact.value()));
            diagnostics.write(c.arm, result);
            UUID id = PlatformEvalSubjectOps.memoryId(result);
            if (id != null) {
                c.rows.put(id, new RowInfo(fact.id(), fact.key(), true));
            }
        }
    }

    /**
     * The user's own erase of one stored fact (the call behind the memory panel's delete), for
     * every row the fact became.
     */
    private void applyForgets(Case c, Session session, UUID agent) {
        for (String factId : session.forgets()) {
            for (Map.Entry<UUID, RowInfo> e : new ArrayList<>(c.rows.entrySet())) {
                if (!factId.equals(e.getValue().factId())) {
                    continue;
                }
                String owner = c.scenario.ownerUser(c.scenario.factsById().get(factId));
                String subject = c.subject(owner == null ? session.userId() : owner);
                boolean gone =
                        e.getValue().shared()
                                ? world.tenantMemory.forgetFactForSubject(e.getKey(), subject)
                                : world.agentMemory.forgetForSubject(e.getKey(), subject);
                if (gone) {
                    c.rows.remove(e.getKey());
                }
            }
        }
    }

    /**
     * One episode per session through the real hook, then the scripted reviewer approves what the
     * platform did not flag. With the write switch off the recorder must not file anything; the
     * harness looks briefly for a proposal anyway, so a recorder that ignores the switch shows up
     * in the diagnostics instead of being assumed away.
     */
    private void recordEpisode(
            Case c,
            Session session,
            UUID agent,
            String subject,
            TenantContext ctx,
            TurnTaint taint) {
        boolean writesOn = c.env.switches().write();
        int before = world.review.forSubject(subject, 500).size();
        EpisodeRecordingGateway.observe(
                        Flux.just("ok"),
                        agent,
                        "memeval-" + c.env.identity().runKey() + "-" + session.id(),
                        Instant.now(),
                        Optional.of(ctx),
                        taint)
                .blockLast();
        long deadline = System.nanoTime() + (writesOn ? 2_000_000_000L : 150_000_000L);
        List<MemoryItem> items = world.review.forSubject(subject, 500);
        while (items.size() <= before && System.nanoTime() < deadline) {
            PlatformEvalSubjectOps.sleep(20);
            items = world.review.forSubject(subject, 500);
        }
        if (!writesOn) {
            if (items.size() > before) {
                diagnostics.episodeDespiteWriteSwitchOff(c.arm);
            }
            return;
        }
        for (MemoryItem item : items) {
            if (item.status() != MemoryItemStatus.PENDING) {
                continue;
            }
            if (item.sourceTaint().isEmpty()
                    && world.review.approve(agent, item.id(), REVIEWER).ok()) {
                diagnostics.episodeApproved(c.arm);
            } else {
                diagnostics.proposalHeld(c.arm);
            }
        }
    }

    /**
     * S15: the consolidation sweep on a pooled thread, as the scheduler runs it, while other
     * tenants hold data.
     */
    private void consolidationHop() {
        Future<?> done = hopPool.submit(() -> world.sweep.sweep());
        try {
            done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            diagnostics.note("consolidation sweep failed: " + e.getCause());
        }
    }

    // ------------------------------------------------------------------ probes

    /** What one pre-turn recall produced and cost. */
    private record Recalled(PlatformRecallBridge.Recall recall, double ms, long statements) {
        long blockTokens() {
            return recall.block() == null ? 0 : SessionMemoryService.estimateTokens(recall.block());
        }
    }

    private Recalled recall(Case c, UUID agent, String subject, String message, boolean ingestion) {
        long before = StatementCounter.count();
        long t0 = System.nanoTime();
        PlatformRecallBridge.Recall r =
                PlatformRecallBridge.recall(world.chat, agent, subject, message);
        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        Recalled out = new Recalled(r, ms, StatementCounter.count() - before);
        diagnostics.recall(
                c.arm, out.ms(), out.statements(), out.blockTokens(), r.recalledFacts(), ingestion);
        return out;
    }

    private TurnCost turnCost(String userText, Recalled recalled, int replyTokens) {
        long prompt =
                assumptions.systemPromptTokens()
                        + SessionMemoryService.estimateTokens(userText)
                        + recalled.blockTokens();
        return new TurnCost(
                (int) prompt,
                replyTokens,
                recalled.ms() + assumptions.modelMs(),
                recalled.ms() + assumptions.modelTtftMs());
    }

    @Override
    protected ProbeOutcome askInFreshThread(CaseEnvironment env, Probe probe) {
        Case c = of(env);
        String schema = c.schema(probe.tenantId());
        TenantContext ctx = TenantContext.of(schema, c.subject(probe.userId()), Set.of());
        Future<ProbeOutcome> future =
                probePool.submit(() -> TenantScope.of(ctx).get(() -> ask(c, probe)));
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(
                    "probe " + probe.id() + " of " + c.scenario.id() + " failed", e.getCause());
        }
    }

    private ProbeOutcome ask(Case c, Probe probe) {
        UUID agent = c.agent(probe.tenantId());
        String subject = c.subject(probe.userId());
        Recalled recalled = recall(c, agent, subject, probe.prompt(), false);
        List<InjectedItem> injected = new ArrayList<>();
        List<RecalledLine> lines = new ArrayList<>();
        if (c.arm == MemoryArm.ORACLE) {
            injectOracle(c, probe, injected, lines);
        } else {
            lines.addAll(RecalledLines.parse(recalled.recall().block()));
            TurnMemoryRecall.Trace trace = recalled.recall().trace();
            if (trace != null) {
                for (UUID id : trace.privateFactIds()) {
                    injected.add(resolve(c, probe, "agent_memory", id));
                }
                for (UUID id : trace.sharedFactIds()) {
                    injected.add(resolve(c, probe, "tenant_memory", id));
                }
                for (TurnMemoryRecall.UnifiedItem item : trace.unifiedItems()) {
                    injected.add(resolve(c, probe, "memory_item", item.id()));
                }
            }
        }
        Answer answer =
                answerer.answer(new ProbeAnswerer.Question(probe.prompt(), lines, probe.task()));
        long oracleTokens =
                c.arm == MemoryArm.ORACLE
                        ? SessionMemoryService.estimateTokens(
                                String.join("\n", lines.stream().map(RecalledLine::text).toList()))
                        : 0;
        long prompt =
                assumptions.systemPromptTokens()
                        + SessionMemoryService.estimateTokens(probe.prompt())
                        + recalled.blockTokens()
                        + oracleTokens;
        TurnCost cost =
                new TurnCost(
                        (int) prompt,
                        (int) Math.max(1, SessionMemoryService.estimateTokens(answer.text())),
                        recalled.ms() + assumptions.modelMs(),
                        recalled.ms() + assumptions.modelTtftMs());
        diagnostics.probe(c.arm, injected.size());
        if (TRACE) {
            System.out.println(
                    "MEMEVAL-TRACE "
                            + c.scenario.id()
                            + " "
                            + c.arm
                            + " "
                            + probe.id()
                            + " ["
                            + probe.type()
                            + "] q=\""
                            + probe.prompt()
                            + "\" -> \""
                            + answer.text()
                            + "\" tools="
                            + answer.toolCalls()
                            + " injected="
                            + injected.stream().map(i -> i.factId() + ":" + i.text()).toList()
                            + " block="
                            + (recalled.recall().block() == null
                                    ? null
                                    : recalled.recall().block().replace('\n', '|')));
        }
        return new ProbeOutcome(
                probe.id(), answer.text(), answer.toolCalls(), injected, snapshot(c), cost);
    }

    /**
     * The ceiling arm: the ledger's valid, visible, relevant facts go straight into the prompt (the
     * platform recall is off).
     */
    private final PlatformEvalSubjectOps ops = new PlatformEvalSubjectOps(this);

    @Override
    protected List<StoreItem> readStore(CaseEnvironment env) {
        Case c = of(env);
        List<StoreItem> out = ops.snapshot(c);
        c.schemaByTenant.values().forEach(world::dropTenant);
        cases.remove(env.identity().runKey());
        return out;
    }

    /**
     * Every memory row of every tenant of the case: private facts, shared facts and unified items,
     * in logical ids.
     */
    private void injectOracle(
            Case c, Probe probe, List<InjectedItem> injected, List<RecalledLine> lines) {
        ops.injectOracle(c, probe, injected, lines);
    }

    private InjectedItem resolve(Case c, Probe probe, String preferredTable, UUID id) {
        return ops.resolve(c, probe, preferredTable, id);
    }

    private List<StoreItem> snapshot(Case c) {
        return ops.snapshot(c);
    }
}
