package com.agenticai.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * B6 regression: with the broker unreachable {@code KafkaProducer.send} blocks, and the relay used to do
 * that on the shared {@code @Scheduled} thread, starving every other scheduled job (a due workflow Wait
 * node did not wake for ~6 minutes). The scheduled entry point must now return immediately and never
 * park the scheduler thread, however long the send blocks.
 */
class WorkflowEventOutboxRelayStarvationTest {

    private final CountDownLatch releaseSend = new CountDownLatch(1);
    private final AtomicInteger sendCalls = new AtomicInteger();
    private WorkflowEventOutboxRelay relay;
    private ExecutorService scheduler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        TenantSchemaIterator iterator = mock(TenantSchemaIterator.class);
        doAnswer(inv -> {
            inv.<Consumer<String>>getArgument(0).accept("tenant_a");
            return null;
        }).when(iterator).forEachActiveTenantSchema(any());

        WorkflowEventOutbox row = new WorkflowEventOutbox("t", "run-1", "{}");
        ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
        WorkflowEventOutboxRepository repo = mock(WorkflowEventOutboxRepository.class);
        when(repo.findByPublishedAtIsNullAndRetryCountLessThan(WorkflowEventOutboxRelay.MAX_RETRIES))
            .thenReturn(List.of(row));

        // A send() that blocks like a producer waiting on metadata from an unreachable broker.
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            sendCalls.incrementAndGet();
            releaseSend.await(30, TimeUnit.SECONDS);
            return CompletableFuture.failedFuture(new IllegalStateException("broker unreachable"));
        });

        PgAdvisoryJobLock lock = mock(PgAdvisoryJobLock.class);
        doAnswer(inv -> {
            inv.getArgument(1, Runnable.class).run();
            return true;
        }).when(lock).tryRunExclusive(anyString(), any(Runnable.class));

        // Production constructor: real dedicated relay executor.
        relay = new WorkflowEventOutboxRelay(
            iterator, repo, kafka, mock(PlatformIssueService.class), lock, new SimpleMeterRegistry());
        scheduler = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        releaseSend.countDown();
        relay.shutdownRelayExecutor();
        scheduler.shutdownNow();
    }

    @Test
    void aBlockedKafkaSendNeverOccupiesTheSharedSchedulerThread() throws Exception {
        // The "shared scheduler": ONE thread that runs the relay tick and then another job.
        Future<?> tick = scheduler.submit(relay::relayUnpublishedEvents);
        tick.get(2, TimeUnit.SECONDS); // old code: blocks here for as long as send() blocks

        CompletableFuture<String> otherJob = new CompletableFuture<>();
        scheduler.submit(() -> otherJob.complete("ran"));
        assertEquals("ran", otherJob.get(2, TimeUnit.SECONDS));
    }

    @Test
    void overlappingTicksDoNotStackUpWhileASendIsStillBlocked() throws Exception {
        relay.relayUnpublishedEvents();
        // wait until the worker is actually inside send()
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (sendCalls.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(sendCalls.get() >= 1, "worker never reached send()");

        relay.relayUnpublishedEvents();
        relay.relayUnpublishedEvents();
        Thread.sleep(150);
        assertEquals(1, sendCalls.get(), "a second tick started while the first was still blocked");
    }
}
