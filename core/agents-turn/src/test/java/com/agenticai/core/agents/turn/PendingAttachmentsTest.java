package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.agents.modality.UnsupportedAttachmentTypeException;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

class PendingAttachmentsTest {

    private final ChatAttachments resolver = mock(ChatAttachments.class);
    private final Agent agent = mock(Agent.class);
    private final UUID agentId = UUID.randomUUID();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final List<ChatAttachmentPayload> payloads =
        List.of(new ChatAttachmentPayload(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] {1}), null));

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        executor.shutdownNow();
    }

    @Test
    void flagOffResolvesEagerlyOnTheCallingThreadInTheSameCall() {
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        when(resolver.resolveAttachments(agent, agentId, "k", "image/png", null)).thenAnswer(i -> {
            ranOn.set(Thread.currentThread());
            return payloads;
        });

        PendingAttachments pending = PendingAttachments.start(false, resolver, executor, agent, agentId, "k", "image/png", null);

        // resolved before start() returned - not deferred
        verify(resolver, times(1)).resolveAttachments(agent, agentId, "k", "image/png", null);
        assertThat(ranOn.get()).isSameAs(caller);
        assertThat(pending.join()).isSameAs(payloads);
    }

    @Test
    void flagOffAndAnEagerFailureSurfacesFromStartLikeToday() {
        when(resolver.resolveAttachments(any(), any(), any(), any(), any()))
            .thenThrow(new UnsupportedAttachmentTypeException("bad"));
        assertThatThrownBy(() -> PendingAttachments.start(false, resolver, executor, agent, agentId, "k", "image/png", null))
            .isInstanceOf(UnsupportedAttachmentTypeException.class);
    }

    @Test
    void flagOnStoredKeyRunsOffThreadWithTheTenantContextAndJoinReturnsIt() throws Exception {
        TenantContext.set(TenantContext.of("acme", "u", Set.of()));
        CountDownLatch inWorker = new CountDownLatch(1);
        AtomicReference<String> tenantSeenByWorker = new AtomicReference<>();
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        when(resolver.resolveAttachments(agent, agentId, "k", "image/png", null)).thenAnswer(i -> {
            workerThread.set(Thread.currentThread());
            tenantSeenByWorker.set(TenantContext.current().map(TenantContext::getTenantId).orElse("MISSING"));
            inWorker.countDown();
            return payloads;
        });

        PendingAttachments pending = PendingAttachments.start(true, resolver, executor, agent, agentId, "k", "image/png", null);

        assertThat(inWorker.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(pending.join()).isSameAs(payloads);
        assertThat(workerThread.get()).isNotSameAs(Thread.currentThread());
        assertThat(tenantSeenByWorker.get()).isEqualTo("acme");
        // the request thread's own context is untouched
        assertThat(TenantContext.current().map(TenantContext::getTenantId)).contains("acme");
    }

    @Test
    void flagOnStorageFailureIsRethrownByJoinAsTheOriginalException() {
        TenantContext.set(TenantContext.of("acme", "u", Set.of()));
        when(resolver.resolveAttachments(any(), any(), any(), any(), any()))
            .thenThrow(new UnsupportedAttachmentTypeException("Could not read the stored attachment: x"));

        PendingAttachments pending = PendingAttachments.start(true, resolver, executor, agent, agentId, "k", "image/png", null);

        assertThatThrownBy(pending::join)
            .isInstanceOf(UnsupportedAttachmentTypeException.class)
            .hasMessageContaining("Could not read the stored attachment");
    }

    @Test
    void flagOnButNoStoredKeyOrInlineAttachmentsStayEagerAndSynchronous() {
        ChatInlineAttachment inline = mock(ChatInlineAttachment.class);
        when(resolver.resolveAttachments(any(), any(), any(), any(), any())).thenReturn(payloads);

        // no key: nothing to overlap
        PendingAttachments.start(true, resolver, executor, agent, agentId, null, null, null);
        // stored key AND inline attachments: inline validation + vision metering must stay synchronous
        PendingAttachments.start(true, resolver, executor, agent, agentId, "k", "image/png", List.of(inline));

        // both resolved before start() returned (mock invoked on this thread, twice)
        verify(resolver, times(2)).resolveAttachments(any(), any(), any(), any(), any());
    }
}
