package com.agenticai.core.eventbus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.IssueSeverity;
import com.agenticai.core.audit.IssueSource;
import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.tenancy.PgAdvisoryJobLock;
import com.agenticai.core.tenancy.TenantSchemaIterator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * {@link WorkflowEventOutboxRelay} is the background half of the
 * transactional-outbox pattern the user's brief explicitly calls out - the
 * mechanism every workflow event's at-least-once delivery guarantee actually
 * depends on. Had zero coverage before this suite despite the class's own
 * javadoc documenting exactly the two failure modes worth locking down: (1)
 * a row must be marked published strictly AFTER Kafka acks it, never before
 * (the fire-and-forget bug an earlier draft had), and (2) a permanently
 * broken row must dead-letter after {@link WorkflowEventOutboxRelay#MAX_RETRIES}
 * attempts and raise exactly one {@link com.agenticai.core.audit.PlatformIssue},
 * not retry forever.
 *
 * <p>{@link TenantSchemaIterator} is mocked with a {@code doAnswer} that
 * immediately invokes the given action with one fixed schema name - the real
 * class's own behavior (see its javadoc) minus the actual {@code
 * TenantContext} set/clear and multi-tenant iteration, which is that class's
 * own already-separate concern, not this relay's.
 */
class WorkflowEventOutboxRelayTest {

    private static final String SCHEMA = "tenant_a";

    private TenantSchemaIterator schemaIterator;
    private WorkflowEventOutboxRepository outboxRepository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private PlatformIssueService platformIssueService;
    private PgAdvisoryJobLock jobLock;
    private SimpleMeterRegistry meterRegistry;
    private WorkflowEventOutboxRelay relay;

    @BeforeEach
    void setUp() {
        schemaIterator = mock(TenantSchemaIterator.class);
        outboxRepository = mock(WorkflowEventOutboxRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        platformIssueService = mock(PlatformIssueService.class);
        // Advisory job lock mocked as "always won" by default - each test
        // exercises the relay's own behavior, not the lock's (that class has
        // its own suite, PgAdvisoryJobLockTest). The skip path is covered by
        // aLostJobLockSkipsTheTickEntirely below.
        jobLock = mock(PgAdvisoryJobLock.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return true;
        }).when(jobLock).tryRunExclusive(anyString(), any(Runnable.class));
        // Infra audit (2026-07-27) - real SimpleMeterRegistry, same reasoning
        // as LlmGatewayRateLimiterServiceTest's identical comment: this relay
        // now registers two Gauges and a Counter in its constructor.
        meterRegistry = new SimpleMeterRegistry();
        // B6: ticks run on the relay's own executor in production; direct (same-thread) here so these
        // tests stay synchronous. The async/starvation behaviour has its own suite below.
        relay = new WorkflowEventOutboxRelay(
            schemaIterator, outboxRepository, kafkaTemplate, platformIssueService, jobLock, meterRegistry,
            Runnable::run);

        doAnswer(invocation -> {
            Consumer<String> action = invocation.getArgument(0);
            action.accept(SCHEMA);
            return null;
        }).when(schemaIterator).forEachActiveTenantSchema(any());
    }

    private WorkflowEventOutbox freshRow() {
        // A real row is only ever seen by the relay after
        // WorkflowEventPublisher has already persisted it, at which point
        // JPA's @GeneratedValue has assigned an id - unlike this plain `new`,
        // which leaves id null. Backfilled via ReflectionTestUtils (same
        // pattern TenantUpgradeServiceTest/TenantEmergencyControlServiceTest
        // already use for the same reason) so handleFailure's row.getId()
        // usage - real in production - doesn't NPE here on a fixture that
        // was never actually inserted.
        WorkflowEventOutbox row = new WorkflowEventOutbox("channel.whatsapp.message.received", "run-1", "{}");
        org.springframework.test.util.ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
        return row;
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, String>> successfulSend() {
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        try {
            when(future.get(anyLong(), any())).thenReturn(mock(SendResult.class));
        } catch (Exception impossible) {
            throw new AssertionError(impossible);
        }
        return future;
    }

    // --- success path ------------------------------------------------------

    @Test
    void aSuccessfulSendMarksTheRowPublishedOnlyAfterTheBrokerAcks() throws Exception {
        WorkflowEventOutbox row = freshRow();
        when(outboxRepository.findByPublishedAtIsNullAndRetryCountLessThan(WorkflowEventOutboxRelay.MAX_RETRIES))
            .thenReturn(List.of(row));
        CompletableFuture<SendResult<String, String>> sendResult = successfulSend();
        when(kafkaTemplate.send(eq(WorkflowEventOutboxRelay.TOPIC), anyString(), anyString()))
            .thenReturn(sendResult);

        relay.relayUnpublishedEvents();

        org.junit.jupiter.api.Assertions.assertTrue(row.getPublishedAt() != null);
        verify(outboxRepository).save(row);
        verify(platformIssueService, never()).record(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void theKafkaMessageKeyIsSchemaColonRunId() throws Exception {
        WorkflowEventOutbox row = freshRow();
        when(outboxRepository.findByPublishedAtIsNullAndRetryCountLessThan(WorkflowEventOutboxRelay.MAX_RETRIES))
            .thenReturn(List.of(row));
        CompletableFuture<SendResult<String, String>> sendResult = successfulSend();
        when(kafkaTemplate.send(eq(WorkflowEventOutboxRelay.TOPIC), anyString(), anyString()))
            .thenReturn(sendResult);

        relay.relayUnpublishedEvents();

        verify(kafkaTemplate).send(WorkflowEventOutboxRelay.TOPIC, SCHEMA + ":" + row.getRunId(), row.getPayloadJson());
    }

    // --- failure below the retry cap: retried, not dead-lettered -----------

    @Test
    void aFailedSendBelowTheRetryCapIsRecordedButNotDeadLettered() {
        WorkflowEventOutbox row = freshRow();
        when(outboxRepository.findByPublishedAtIsNullAndRetryCountLessThan(WorkflowEventOutboxRelay.MAX_RETRIES))
            .thenReturn(List.of(row));
        when(kafkaTemplate.send(eq(WorkflowEventOutboxRelay.TOPIC), anyString(), anyString()))
            .thenThrow(new org.apache.kafka.common.errors.TimeoutException("broker unreachable"));

        relay.relayUnpublishedEvents();

        assertEquals(1, row.getRetryCount());
        org.junit.jupiter.api.Assertions.assertTrue(row.getPublishedAt() == null);
        verify(outboxRepository).save(row);
        verify(platformIssueService, never()).record(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void aTimeoutWaitingForTheBrokerAckIsTreatedAsAFailureNotASuccess() throws Exception {
        // The blocking .get(timeout) call itself timing out - not the send()
        // call throwing directly - is the exact scenario the class's javadoc
        // documents choosing a blocking ack over fire-and-forget to catch.
        WorkflowEventOutbox row = freshRow();
        when(outboxRepository.findByPublishedAtIsNullAndRetryCountLessThan(WorkflowEventOutboxRelay.MAX_RETRIES))
            .thenReturn(List.of(row));
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        when(future.get(anyLong(), any())).thenThrow(new TimeoutException("ack timed out"));
        when(kafkaTemplate.send(eq(WorkflowEventOutboxRelay.TOPIC), anyString(), anyString())).thenReturn(future);

        relay.relayUnpublishedEvents();

        assertEquals(1, row.getRetryCount());
        org.junit.jupiter.api.Assertions.assertTrue(row.getPublishedAt() == null);
    }

    // --- failure at the retry cap: dead-lettered ----------------------------

    @Test
    void reachingMaxRetriesDeadLettersTheRowAndRaisesExactlyOnePlatformIssue() {
        WorkflowEventOutbox row = freshRow();
        for (int i = 0; i < WorkflowEventOutboxRelay.MAX_RETRIES - 1; i++) {
            row.recordFailure("prior failure " + i);
        }
        assertEquals(WorkflowEventOutboxRelay.MAX_RETRIES - 1, row.getRetryCount());
        when(outboxRepository.findByPublishedAtIsNullAndRetryCountLessThan(WorkflowEventOutboxRelay.MAX_RETRIES))
            .thenReturn(List.of(row));
        when(kafkaTemplate.send(eq(WorkflowEventOutboxRelay.TOPIC), anyString(), anyString()))
            .thenThrow(new RuntimeException("topic ACL denied"));

        relay.relayUnpublishedEvents();

        assertEquals(WorkflowEventOutboxRelay.MAX_RETRIES, row.getRetryCount());
        verify(platformIssueService, times(1)).record(
            eq(IssueSource.EVENT_OUTBOX), anyString(), eq(IssueSeverity.ERROR), anyString(), anyString());
    }

    @Test
    void aRowBelowTheCapNeverRaisesAPlatformIssue() {
        WorkflowEventOutbox row = freshRow();
        when(outboxRepository.findByPublishedAtIsNullAndRetryCountLessThan(WorkflowEventOutboxRelay.MAX_RETRIES))
            .thenReturn(List.of(row));
        when(kafkaTemplate.send(eq(WorkflowEventOutboxRelay.TOPIC), anyString(), anyString()))
            .thenThrow(new RuntimeException("transient"));

        relay.relayUnpublishedEvents();

        verify(platformIssueService, never()).record(any(), any(), any(), anyString(), anyString());
    }

    @Test
    void aFailureRecordingThePlatformIssueItselfDoesNotPropagateOrStopTheRelayLoop() {
        // Mirrors PlatformIssueService's own "never let an observability
        // write become a second failure" contract, from the caller's side -
        // a bug in issue-recording must never re-throw out of relayUnpublishedEvents.
        WorkflowEventOutbox row = freshRow();
        for (int i = 0; i < WorkflowEventOutboxRelay.MAX_RETRIES - 1; i++) {
            row.recordFailure("prior failure " + i);
        }
        when(outboxRepository.findByPublishedAtIsNullAndRetryCountLessThan(WorkflowEventOutboxRelay.MAX_RETRIES))
            .thenReturn(List.of(row));
        when(kafkaTemplate.send(eq(WorkflowEventOutboxRelay.TOPIC), anyString(), anyString()))
            .thenThrow(new RuntimeException("permanently broken"));
        when(platformIssueService.record(any(), any(), any(), anyString(), anyString()))
            .thenThrow(new RuntimeException("issues table unavailable too"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> relay.relayUnpublishedEvents());
    }

    // --- per-row isolation: one bad row never blocks the rest --------------

    @Test
    void oneRowFailingDoesNotStopTheOthersInTheSameTenantsBatch() {
        WorkflowEventOutbox badRow = freshRow();
        WorkflowEventOutbox goodRow = new WorkflowEventOutbox("channel.slack.message.received", "run-2", "{}");
        when(outboxRepository.findByPublishedAtIsNullAndRetryCountLessThan(WorkflowEventOutboxRelay.MAX_RETRIES))
            .thenReturn(List.of(badRow, goodRow));
        when(kafkaTemplate.send(eq(WorkflowEventOutboxRelay.TOPIC), eq(SCHEMA + ":run-1"), anyString()))
            .thenThrow(new RuntimeException("boom"));
        CompletableFuture<SendResult<String, String>> run2SendResult = successfulSend();
        when(kafkaTemplate.send(eq(WorkflowEventOutboxRelay.TOPIC), eq(SCHEMA + ":run-2"), anyString()))
            .thenReturn(run2SendResult);

        relay.relayUnpublishedEvents();

        org.junit.jupiter.api.Assertions.assertTrue(badRow.getPublishedAt() == null);
        org.junit.jupiter.api.Assertions.assertTrue(goodRow.getPublishedAt() != null);
    }

    @Test
    void noUnpublishedRowsIsANoOp() {
        when(outboxRepository.findByPublishedAtIsNullAndRetryCountLessThan(WorkflowEventOutboxRelay.MAX_RETRIES))
            .thenReturn(List.of());

        relay.relayUnpublishedEvents();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        verify(outboxRepository, never()).save(any());
    }

    // --- multi-replica: a lost advisory lock skips the whole tick ----------

    @Test
    void aLostJobLockSkipsTheTickEntirelyAndCountsTheSkip() {
        // Another replica holds the lock: no polling, no publishing - the
        // whole point of the lock is that at-least-once never silently
        // becomes at-least-twice with a second core replica. doReturn (not
        // when().thenReturn()) because when() would invoke setUp's doAnswer
        // stub with a null Runnable while stubbing.
        org.mockito.Mockito.doReturn(false).when(jobLock).tryRunExclusive(anyString(), any(Runnable.class));

        relay.relayUnpublishedEvents();

        verify(schemaIterator, never()).forEachActiveTenantSchema(any());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        assertEquals(1.0, meterRegistry.counter("job_lock_skipped", "job", "workflow-event-outbox-relay").count());
    }
}
