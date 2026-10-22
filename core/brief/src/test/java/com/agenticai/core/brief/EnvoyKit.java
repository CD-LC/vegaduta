package com.agenticai.core.brief;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.brief.BriefFixtures.B;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.workflow.dto.WorkflowEdgeDto;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Wires the real {@link BriefApplyService}, {@link TrustLadderService}, {@link BriefVersionService}
 * and {@link BriefVersionStore} over a {@link FakeEnvoyPorts}, an {@link InMemoryLedger} and
 * map-backed repositories. Only the platform (services, database) is faked; every line of engine
 * logic under test is the production code.
 */
class EnvoyKit {

    static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    final ObjectMapper mapper = new ObjectMapper();
    final FakeEnvoyPorts ports = new FakeEnvoyPorts();
    final InMemoryLedger ledger = new InMemoryLedger();
    final Map<UUID, BriefEntity> briefs = new ConcurrentHashMap<>();
    final List<BriefVersionEntity> versions = new ArrayList<>();
    final List<BriefTrustEventEntity> events = new ArrayList<>();
    final BriefValidator validator = mock(BriefValidator.class);
    final BriefReadinessService readiness = mock(BriefReadinessService.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final InMemoryProofSource[] proof = new InMemoryProofSource[1];
    final BriefRepository briefRepository;
    final BriefTrustEventRepository eventRepository;
    final BriefVersionStore store;
    final BriefApplyService apply;
    final TrustLadderService trust;
    final BriefVersionService versionService;

    @SuppressWarnings("unchecked")
    EnvoyKit() {
        briefRepository = mock(BriefRepository.class);
        when(briefRepository.findById(any(UUID.class)))
                .thenAnswer(i -> Optional.ofNullable(briefs.get(i.getArgument(0, UUID.class))));
        when(briefRepository.save(any(BriefEntity.class)))
                .thenAnswer(
                        i -> {
                            BriefEntity e = i.getArgument(0);
                            briefs.put(e.getId(), e);
                            return e;
                        });
        BriefVersionRepository versionRepository = mock(BriefVersionRepository.class);
        when(versionRepository.save(any(BriefVersionEntity.class)))
                .thenAnswer(
                        i -> {
                            versions.add(i.getArgument(0));
                            return i.getArgument(0);
                        });
        when(versionRepository.findByBriefIdAndVersion(any(UUID.class), anyInt()))
                .thenAnswer(
                        i ->
                                versions.stream()
                                        .filter(
                                                v ->
                                                        v.getBriefId().equals(i.getArgument(0))
                                                                && v.getVersion()
                                                                        == (int) i.getArgument(1))
                                        .findFirst());
        when(versionRepository.findByBriefIdOrderByVersionDesc(any(UUID.class)))
                .thenAnswer(
                        i ->
                                versions.stream()
                                        .filter(v -> v.getBriefId().equals(i.getArgument(0)))
                                        .sorted(
                                                Comparator.comparingInt(
                                                                BriefVersionEntity::getVersion)
                                                        .reversed())
                                        .toList());
        eventRepository = mock(BriefTrustEventRepository.class);
        when(eventRepository.save(any(BriefTrustEventEntity.class)))
                .thenAnswer(
                        i -> {
                            events.add(i.getArgument(0));
                            return i.getArgument(0);
                        });
        when(eventRepository.findByBriefIdOrderByCreatedAtDesc(any(UUID.class)))
                .thenAnswer(
                        i -> {
                            List<BriefTrustEventEntity> copy = new ArrayList<>(events);
                            java.util.Collections.reverse(copy);
                            return copy;
                        });

        when(validator.validate(any(), anyBoolean()))
                .thenReturn(new BriefValidationResult(List.of(), List.of(), List.of()));
        when(readiness.compute(any(), any())).thenReturn(List.of());

        ObjectProvider<ProofSource> proofProvider = mock(ObjectProvider.class);
        when(proofProvider.getIfAvailable()).thenAnswer(i -> proof[0]);

        store = new BriefVersionStore(briefRepository, versionRepository, mapper);
        apply =
                new BriefApplyService(
                        store, ledger, ports, validator, readiness, mapper, audit, proofProvider);
        trust =
                new TrustLadderService(
                        store,
                        ledger,
                        ports,
                        proofProvider,
                        eventRepository,
                        audit,
                        mapper,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        versionService = new BriefVersionService(store, apply, audit, mapper);
    }

    /** Saves {@code brief} as version 1 of a new DRAFT brief at UNDER_WATCH. */
    UUID save(Brief brief) {
        UUID id = UUID.randomUUID();
        BriefEntity entity =
                new BriefEntity(
                        id, brief.title(), brief.goal(), brief.locale(), "UNDER_WATCH", "tester");
        briefs.put(id, entity);
        versions.add(
                new BriefVersionEntity(
                        id,
                        1,
                        store.toJson(BriefVersionStore.withRung(brief, TrustRung.UNDER_WATCH)),
                        null,
                        "created",
                        "tester"));
        return id;
    }

    /** Saves and applies synchronously; asserts nothing. */
    UUID savedAndApplied(Brief brief) {
        UUID id = save(brief);
        apply.applyCurrent(id, false);
        return id;
    }

    /**
     * A valid brief whose workflow is trigger -> agent -> tool -> output, with the output declared
     * irreversible.
     */
    static Brief richBrief() {
        B b = BriefFixtures.valid();
        b.trust = new Brief.Trust("UNDER_WATCH", List.of("w1.n4"), List.of());
        b.workflows =
                new ArrayList<>(
                        List.of(
                                new Brief.WorkflowSpec(
                                        "w1",
                                        "Chase",
                                        richGraph(),
                                        List.of(new Brief.Trigger("MANUAL", null, null)),
                                        "Invoice 42 is late")));
        return b.build();
    }

    static WorkflowGraphDto richGraph() {
        WorkflowNodeDto trigger = BriefFixtures.node("n1", "trigger", Map.of("channel", "WEBHOOK"));
        WorkflowNodeDto agent = BriefFixtures.node("n2", "agent", Map.of("agentId", "a1"));
        WorkflowNodeDto tool =
                BriefFixtures.node("n3", "tool", Map.of("agentId", "a1", "toolName", "lookup"));
        WorkflowNodeDto output = BriefFixtures.node("n4", "output", Map.of("channel", "log"));
        return new WorkflowGraphDto(
                List.of(trigger, agent, tool, output),
                List.of(
                        new WorkflowEdgeDto("e1", "n1", "n2", null, null),
                        new WorkflowEdgeDto("e2", "n2", "n3", null, null),
                        new WorkflowEdgeDto("e3", "n3", "n4", null, null)));
    }

    String entityIdOf(UUID briefId, String ref) {
        return ledger.bindings(briefId).get(ref).entityId();
    }

    BriefEntity entity(UUID id) {
        return briefs.get(id);
    }
}
