package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.job.dto.AgentJobStartRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.sharing.PermissionLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Exact list offsets, the per-feature submit gate (research run cap on direct jobs), the research
 * history slice and "a saved job whose dispatch fails is still a successful submit".
 */
class AgentJobSubmitGateAndPagingTest {

    private static final String TENANT = "t_gate";
    private static final String USER = "alice-sub";

    private AgentJobRepository repository;
    private AgentService agentService;
    private AgentJobExecutor executor;
    private AgentJobService service;
    private UUID agentId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        agentId = UUID.randomUUID();
        repository = mock(AgentJobRepository.class);
        when(repository.save(any(AgentJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.findByAgentIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        agentService = mock(AgentService.class);
        executor = mock(AgentJobExecutor.class);
        service =
                new AgentJobService(
                        repository,
                        mock(AgentRepository.class),
                        agentService,
                        executor,
                        new AgentJobProperties(),
                        new SimpleMeterRegistry(),
                        new ObjectMapper(),
                        15);
        TenantContext.set(TenantContext.of(TENANT, USER, Set.of("ROLE_user")));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @SuppressWarnings("unchecked")
    private void installGate(AgentJobSubmitGate gate) {
        ObjectProvider<AgentJobSubmitGate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gate);
        service.setSubmitGate(provider);
    }

    private static AgentJobStartRequest request() {
        return new AgentJobStartRequest("do the thing", null, null, null);
    }

    // ------------------------------------------------------------- offsets

    @Test
    void anUnalignedOffsetIsHonouredExactlyInsteadOfRoundedDown() {
        service.listForAgent(agentId, Set.of(), 20, 30);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByAgentIdOrderByCreatedAtDesc(eq(agentId), pageable.capture());
        assertEquals(30L, pageable.getValue().getOffset());
        assertEquals(20, pageable.getValue().getPageSize());
    }

    @Test
    void anAlignedOffsetKeepsTheOriginalPageRequest() {
        service.listForAgent(agentId, Set.of(), 20, 40);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByAgentIdOrderByCreatedAtDesc(eq(agentId), pageable.capture());
        assertEquals(PageRequest.of(2, 20), pageable.getValue());
    }

    @Test
    void negativeOffsetsAndOversizedLimitsAreClamped() {
        service.listForAgent(agentId, Set.of(), 5000, -7);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByAgentIdOrderByCreatedAtDesc(eq(agentId), pageable.capture());
        assertEquals(0L, pageable.getValue().getOffset());
        assertEquals(200, pageable.getValue().getPageSize());
    }

    // ---------------------------------------------------------- submit gate

    @Test
    void aGatedAgentsDirectJobReservesTheFeatureMeterOnceAndKeepsIt() {
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        installGate(
                (agent, tenant, subject) -> {
                    assertEquals(agentId, agent);
                    assertEquals(TENANT, tenant);
                    assertEquals(USER, subject);
                    reserved.incrementAndGet();
                    return released::incrementAndGet;
                });

        assertNotNull(service.submit(agentId, request()));
        assertEquals(1, reserved.get());
        assertEquals(0, released.get());
    }

    @Test
    void theFacadesAlreadyReservedRunIsNotCountedTwice() {
        AtomicInteger reserved = new AtomicInteger();
        installGate(
                (agent, tenant, subject) -> {
                    reserved.incrementAndGet();
                    return () -> {};
                });

        service.submit(agentId, request(), true);
        assertEquals(0, reserved.get());
    }

    @Test
    void anUngatedAgentIsUntouched() {
        installGate((agent, tenant, subject) -> null);
        assertNotNull(service.submit(agentId, request()));
        verify(repository).save(any(AgentJob.class));
    }

    @Test
    void aRefusedReservationWritesNoJob() {
        installGate(
                (agent, tenant, subject) -> {
                    throw new IllegalStateException("run cap used");
                });
        assertThrows(IllegalStateException.class, () -> service.submit(agentId, request()));
        verify(repository, never()).save(any(AgentJob.class));
    }

    @Test
    void aFailedSaveGivesTheReservationBack() {
        AtomicInteger released = new AtomicInteger();
        installGate((agent, tenant, subject) -> released::incrementAndGet);
        when(repository.save(any(AgentJob.class))).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> service.submit(agentId, request()));
        assertEquals(1, released.get());
    }

    @Test
    void aSavedJobWhoseDispatchBlowsUpIsStillASuccessfulSubmitAndKeepsItsReservation() {
        AtomicInteger released = new AtomicInteger();
        installGate((agent, tenant, subject) -> released::incrementAndGet);
        when(executor.tryDispatch(any(UUID.class), anyString()))
                .thenThrow(new IllegalStateException("pool gone"));

        assertNotNull(service.submit(agentId, request()));
        assertEquals(0, released.get(), "the durable row will still run, so the run stays counted");
    }

    // ------------------------------------------------------ research history

    @Test
    void historySliceReportsHasMoreFromTheExtraRow() {
        List<AgentJob> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(
                    AgentJob.queued(
                            UUID.randomUUID(),
                            TENANT,
                            agentId,
                            "Research question: " + i,
                            USER,
                            USER,
                            Set.of(),
                            AgentJobOrigin.API,
                            null,
                            60,
                            1,
                            1,
                            null));
        }
        when(repository.findByAgentIdInAndGoalStartingWithOrderByCreatedAtDesc(
                        any(), anyString(), any()))
                .thenReturn(rows);

        AgentJobService.SummarySlice slice =
                service.listForAgentsWithGoalPrefix(List.of(agentId), "Research question", 3, 3);
        assertEquals(3, slice.items().size());
        assertTrue(slice.hasMore());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository)
                .findByAgentIdInAndGoalStartingWithOrderByCreatedAtDesc(
                        eq(List.of(agentId)), eq("Research question"), pageable.capture());
        assertEquals(3L, pageable.getValue().getOffset());
        assertEquals(4, pageable.getValue().getPageSize(), "limit + 1 rows are fetched");

        when(repository.findByAgentIdInAndGoalStartingWithOrderByCreatedAtDesc(
                        any(), anyString(), any()))
                .thenReturn(rows.subList(0, 3));
        assertFalse(
                service.listForAgentsWithGoalPrefix(List.of(agentId), "Research question", 3, 3)
                        .hasMore());
    }

    @Test
    void historySliceSkipsAgentsTheCallerCannotView() {
        UUID hidden = UUID.randomUUID();
        doThrow(new IllegalStateException("no access"))
                .when(agentService)
                .requireAccess(hidden, PermissionLevel.VIEWER);
        when(repository.findByAgentIdInAndGoalStartingWithOrderByCreatedAtDesc(
                        any(), anyString(), any()))
                .thenReturn(List.of());

        service.listForAgentsWithGoalPrefix(List.of(hidden, agentId), "Research question", 10, 0);
        verify(repository)
                .findByAgentIdInAndGoalStartingWithOrderByCreatedAtDesc(
                        eq(List.of(agentId)), eq("Research question"), any());

        // Nothing visible at all: no query, empty page.
        AgentJobService.SummarySlice none =
                service.listForAgentsWithGoalPrefix(List.of(hidden), "Research question", 10, 0);
        assertTrue(none.items().isEmpty());
        assertFalse(none.hasMore());
        verify(repository, org.mockito.Mockito.times(1))
                .findByAgentIdInAndGoalStartingWithOrderByCreatedAtDesc(any(), anyString(), any());
    }
}
