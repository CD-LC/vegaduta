package com.agenticai.core.agents.learning.episodes;

import com.agenticai.core.agents.learning.episodes.EpisodeComposer.Episode;
import com.agenticai.core.agents.learning.episodes.EpisodeSignalSource.Signals;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.EpisodicMemoryProperties;
import com.agenticai.core.agents.memory.MemoryProvenance;
import com.agenticai.core.agents.memory.MemoryWriteGate;
import com.agenticai.core.agents.memory.TurnTaintView;
import com.agenticai.core.agents.memory.spi.MemoryKind;
import com.agenticai.core.agents.memory.spi.MemoryProposalReceipt;
import com.agenticai.core.agents.memory.spi.MemoryProposalSink;
import com.agenticai.core.agents.memory.spi.MemoryProvenanceRecord;
import com.agenticai.core.agents.memory.spi.MemoryTrustLevel;
import com.agenticai.core.agents.memory.spi.MemoryWriteProposal;
import com.agenticai.core.agents.skills.EpisodeRecordingGateway;
import com.agenticai.core.agents.skills.SkillLearningGateway.TurnOutcome;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.knowledge.MemoryWriteScreen;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.settings.SettingContext;
import com.agenticai.core.settings.SettingsResolver;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The episodic write path (ADR-0028 WP4b): after a turn, files one compact "what happened" episode as a PENDING or
 * QUARANTINED proposal through {@link MemoryProposalSink}, and nothing else. It cannot create an ACTIVE or TRUSTED row.
 *
 * <ul>
 *   <li>Off unless {@code app.memory.episodic.enabled=true} (the bean does not exist otherwise) AND the tenant/agent
 *       setting {@code memory.episodic.enabled} allows it; {@code memory.write.enabled} off skips it too and counts the
 *       skip ({@code memory.episode.skipped}).
 *   <li>The outcome comes from verified signals only ({@link EpisodeComposer}); the text is screened with {@link
 *       MemoryWriteScreen}; zero model calls.
 *   <li>A tainted or unreadable turn is classified on the finishing thread by {@link MemoryWriteGate} (fail closed) and
 *       the proposal carries QUARANTINED provenance.
 *   <li>Everything after a cheap admission check runs on a small bounded worker with the tenant re-applied (AGENTS.md
 *       rule 12); a full queue drops the episode, and no failure reaches the turn.
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "app.memory.episodic.enabled", havingValue = "true")
public class EpisodeRecorder implements EpisodeRecordingGateway.Observer {

    private static final Logger log = LoggerFactory.getLogger(EpisodeRecorder.class);

    static final String SETTING_KEY = "memory.episodic.enabled";
    static final String WRITER = "episode_recorder";
    static final String METRIC = "memory.episode.skipped";

    private final EpisodicMemoryProperties props;
    private final ToolCallLogService toolCalls;
    private final ObjectProvider<MemoryProposalSink> sink;
    private final ObjectProvider<TurnTaintView> taint;
    private final ObjectProvider<AgentMemoryService> memory;
    private final ObjectProvider<SettingsResolver> settings;
    private final ObjectProvider<EpisodeSignalSource> signalSource;
    private final ObjectProvider<MeterRegistry> meters;
    private final EpisodeLimiter limiter;
    private final ExecutorService pool;

    public EpisodeRecorder(
            EpisodicMemoryProperties props,
            ToolCallLogService toolCalls,
            ObjectProvider<MemoryProposalSink> sink,
            ObjectProvider<TurnTaintView> taint,
            ObjectProvider<AgentMemoryService> memory,
            ObjectProvider<SettingsResolver> settings,
            ObjectProvider<EpisodeSignalSource> signalSource,
            ObjectProvider<MeterRegistry> meters) {
        this.props = props;
        this.toolCalls = toolCalls;
        this.sink = sink;
        this.taint = taint;
        this.memory = memory;
        this.settings = settings;
        this.signalSource = signalSource;
        this.meters = meters;
        this.limiter = new EpisodeLimiter(
                props.getMaxPerAgentPerHour(),
                props.getMaxPerUserPerHour(),
                props.getMaxPerAgentPerDay(),
                System::currentTimeMillis);
        this.pool = TenantAwareExecutors.newThreadPool(
                1,
                1,
                30,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(props.getQueueSize()),
                TenantAwareExecutors.numberedThreadFactory("episode-recorder-", true),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PostConstruct
    void install() {
        EpisodeRecordingGateway.install(this);
    }

    @PreDestroy
    void shutdown() {
        EpisodeRecordingGateway.install(null);
        pool.shutdown();
    }

    @Override
    public boolean enabled() {
        return props.isEnabled();
    }

    /** Runs on the thread that finished the turn: cheap checks and a hand-off, nothing else. Never throws. */
    @Override
    public void onTurn(TurnOutcome o) {
        try {
            if (o != null && o.scope() != null && !o.scope().isEmpty()) {
                o.scope().run(() -> admit(o));
            }
        } catch (RuntimeException e) {
            skipped("failure");
            log.debug("Episode admission failed: {}", e.getMessage());
        }
    }

    private void admit(TurnOutcome o) {
        if (o == null || o.agentId() == null || o.sessionId() == null || o.cancelled() || o.scope().isEmpty()) {
            return;
        }
        if (!props.isEnabled() || !agentAllows(o)) {
            skipped("flag_off");
            return;
        }
        if (writesOff(o.agentId())) {
            skipped("write_disabled");
            return;
        }
        // Taint is read here, on the finishing thread, where the turn's record is still reachable; the worker never
        // asks. No view, an unreadable view or a tainted turn all come back QUARANTINED.
        MemoryProvenance classified = MemoryWriteGate.classify(taint);
        String user = o.scope().context().map(TenantContext::getSubject).orElse(null);
        EpisodeLimiter.Verdict verdict = limiter.tryAcquire(o.agentId(), user);
        if (verdict != EpisodeLimiter.Verdict.OK) {
            skipped("cap_" + verdict.name().toLowerCase(java.util.Locale.ROOT));
            return;
        }
        try {
            pool.execute(o.scope().wrapRunnable(() -> record(o, classified, user)));
        } catch (RejectedExecutionException e) {
            skipped("queue_full");
        }
    }

    private boolean agentAllows(TurnOutcome o) {
        SettingsResolver resolver = settings.getIfAvailable();
        if (resolver == null) {
            return true; // platform flag already true
        }
        try {
            return o.scope().get(() -> resolver.getBool(SETTING_KEY, SettingContext.forAgent(o.agentId())));
        } catch (RuntimeException e) {
            return false; // unreadable level counts as off
        }
    }

    private boolean writesOff(UUID agentId) {
        try {
            AgentMemoryService service = memory.getIfAvailable();
            return service != null && service.writesSwitchedOff(agentId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The worker body. Never throws. */
    void record(TurnOutcome o, MemoryProvenance classified, String user) {
        try {
            MemoryProposalSink target = sink.getIfAvailable();
            if (target == null) {
                skipped("no_sink");
                return;
            }
            List<ToolCallLog> calls = toolCalls.findSince(o.agentId(), o.sessionId(), o.startedAt()).stream()
                    .filter(c -> c.getStartedAt() != null && !c.getStartedAt().isBefore(o.startedAt()))
                    .toList();
            Signals signals = signalsOf(o);
            Episode episode = EpisodeComposer.compose(calls, signals, !o.completed(), props.getMaxChars());
            Optional<String> refusal = MemoryWriteScreen.refusal(episode.text());
            if (refusal.isPresent()) {
                skipped("screen_refused");
                return;
            }
            MemoryProvenanceRecord provenance = new MemoryProvenanceRecord(
                    classified.quarantined() ? MemoryTrustLevel.QUARANTINED : MemoryTrustLevel.TRUSTED,
                    classified.sourceTaint(),
                    WRITER);
            Optional<MemoryProposalReceipt> receipt = target.propose(new MemoryWriteProposal(
                    o.agentId(), user, MemoryKind.EPISODIC, episode.text(), provenance, null, false));
            if (receipt.isEmpty()) {
                skipped("sink_refused");
            } else {
                count("memory.episode.proposed", receipt.get().status().name());
            }
        } catch (RuntimeException e) {
            skipped("failure");
            log.warn("Episode not recorded for agent {}: {}", o.agentId(), e.getMessage());
        }
    }

    private Signals signalsOf(TurnOutcome o) {
        try {
            EpisodeSignalSource source = signalSource.getIfAvailable();
            Signals s = source == null ? null : source.signalsFor(o.agentId(), o.sessionId(), o.startedAt());
            return s == null ? Signals.NONE : s;
        } catch (RuntimeException e) {
            return Signals.NONE;
        }
    }

    private void skipped(String reason) {
        count(METRIC, reason);
    }

    private void count(String name, String tag) {
        try {
            MeterRegistry registry = meters.getIfAvailable();
            if (registry != null) {
                registry.counter(name, "reason", tag).increment();
            }
        } catch (RuntimeException ignored) {
            // metrics must never matter
        }
    }
}
