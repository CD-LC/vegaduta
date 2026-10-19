package com.agenticai.core.chat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService.HostedProviderSelection;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Shared fixture for the {@link FimCompletionService} suites: mocked pool resolver and client factory, a
 * {@link RecordingEntitlements} meter, and the two service seams - {@link CountingService} (canned
 * {@code invokeModel} outcome) and {@link WorkerService} (the real pool and deadline, stubbed only at
 * {@code callOnWorker}). See {@link FimCompletionServiceTest} for the contract the suites lock in.
 */
abstract class FimCompletionServiceFixture {

    PlatformHostedLlmProviderService hostedProviders;
    HostedLlmChatClientFactory chatClientFactory;
    RecordingEntitlements entitlements;
    final List<WorkerService> startedServices = new ArrayList<>();

    @BeforeEach
    void setUp() {
        hostedProviders = mock(PlatformHostedLlmProviderService.class);
        chatClientFactory = mock(HostedLlmChatClientFactory.class);
        entitlements = new RecordingEntitlements();
    }

    @AfterEach
    void tearDown() {
        // Release anything still parked on a worker, then stop the pools -
        // these are daemon threads, but a test that leaves one blocked would
        // still hold a lease open into the next one.
        startedServices.forEach(WorkerService::releaseAndShutdown);
        startedServices.clear();
        TenantContext.clear();
    }

    static final int DEFAULT_MAX_TOKENS = 48;
    static final int CEILING_MAX_TOKENS = 160;

    /** One enabled pool row, which is what {@link WorkerService}'s tests resolve against. */
    void poolWithOneRow() {
        PlatformHostedLlmProvider provider = new PlatformHostedLlmProvider(
            "test row", "https://api.example.test", "test-model", "ciphertext", true, 0, null, null);
        when(hostedProviders.resolveEnabledChain())
            .thenReturn(List.of(new HostedProviderSelection(provider, "decrypted-key")));
    }

    /** The minimal real response shape {@code toReply} reads text and usage out of. */
    static ChatResponse responseWith(String text, int promptTokens, int completionTokens) {
        return new ChatResponse(
            List.of(new Generation(new AssistantMessage(text))),
            ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens))
                .build());
    }

    CountingService service(FimCompletionEntitlements adapter, FimCompletionService.ModelReply reply) {
        return service(adapter, FimCompletionService.ModelCall.of(reply));
    }

    CountingService service(FimCompletionEntitlements adapter, FimCompletionService.ModelCall call) {
        return new CountingService(providerOf(adapter), hostedProviders, chatClientFactory, call);
    }

    WorkerService worker(FimCompletionEntitlements adapter, long deadlineMillis, int maxConcurrent, WorkerCall call) {
        return worker(adapter, deadlineMillis, maxConcurrent, 60_000L, call);
    }

    WorkerService worker(
        FimCompletionEntitlements adapter, long deadlineMillis, int maxConcurrent, long poolCacheMillis, WorkerCall call
    ) {
        poolWithOneRow();
        WorkerService service = new WorkerService(
            providerOf(adapter), hostedProviders, chatClientFactory, deadlineMillis, maxConcurrent, poolCacheMillis, call);
        startedServices.add(service);
        return service;
    }

    @SuppressWarnings("unchecked")
    static ObjectProvider<FimCompletionEntitlements> providerOf(FimCompletionEntitlements adapter) {
        ObjectProvider<FimCompletionEntitlements> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(adapter);
        return provider;
    }

    /** Subclass seam - counts invocations and returns a canned outcome (or throws) instead of calling a model. */
    static class CountingService extends FimCompletionService {
        private final ModelCall call;
        RuntimeException failWith;
        int modelInvocations;

        CountingService(
            ObjectProvider<FimCompletionEntitlements> entitlements,
            PlatformHostedLlmProviderService hostedProviders,
            HostedLlmChatClientFactory chatClientFactory,
            ModelCall call
        ) {
            super(entitlements, hostedProviders, chatClientFactory, new LlmPricingCatalog(),
                DEFAULT_MAX_TOKENS, CEILING_MAX_TOKENS, 1500L, 4, 2000, 60_000L);
            this.call = call;
        }

        @Override
        ModelCall invokeModel(String prefix, String suffix, String language, int maxTokens, FimCompletionEntitlements.Lease lease) {
            modelInvocations++;
            if (failWith != null) {
                throw failWith;
            }
            return call;
        }
    }

    /** What a {@link WorkerService} does in place of the provider call, on the pool thread. */
    @FunctionalInterface
    interface WorkerCall {
        ChatResponse call(WorkerService worker) throws Exception;
    }

    /**
     * Stands in for the provider at the LAST seam before the network, so
     * everything above it - the real executor, the real deadline, the real
     * tenant-context re-application, the real pool-row resolution - is the
     * code under test rather than a stub.
     */
    static final class WorkerService extends FimCompletionService {
        private final WorkerCall call;
        private final CountDownLatch releaseLatch = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        volatile String workerThreadName;
        volatile String workerTenantId;

        WorkerService(
            ObjectProvider<FimCompletionEntitlements> entitlements,
            PlatformHostedLlmProviderService hostedProviders,
            HostedLlmChatClientFactory chatClientFactory,
            long deadlineMillis,
            int maxConcurrent,
            long poolCacheMillis,
            WorkerCall call
        ) {
            super(entitlements, hostedProviders, chatClientFactory, new LlmPricingCatalog(),
                DEFAULT_MAX_TOKENS, CEILING_MAX_TOKENS, deadlineMillis, maxConcurrent, 2000, poolCacheMillis);
            this.call = call;
        }

        @Override
        ChatResponse callOnWorker(org.springframework.ai.chat.client.ChatClient chatClient, FimPrompt.Built prompt) {
            workerThreadName = Thread.currentThread().getName();
            workerTenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
            started.countDown();
            try {
                return call.call(this);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        /** Parks the worker exactly the way a slow provider would. */
        void awaitRelease() throws InterruptedException {
            if (!releaseLatch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test never released the worker");
            }
        }

        void release() {
            releaseLatch.countDown();
        }

        void releaseAndShutdown() {
            release();
            shutdown();
        }
    }

    /** Records what the service asked of the meters, and can be told to reject. */
    static final class RecordingEntitlements implements FimCompletionEntitlements {
        /** Synchronized because a rejected request and a running one reserve from different threads. */
        final List<Integer> reservedMaxTokens = Collections.synchronizedList(new ArrayList<>());
        /** Counts down on whichever terminal call happens, including one made from a pool thread. */
        final CountDownLatch terminal = new CountDownLatch(1);
        RuntimeException rejectWith;
        volatile int settled;
        volatile int abandoned;
        volatile String settledModel;
        volatile Long settledPromptTokens;
        volatile Long settledCompletionTokens;

        @Override
        public Lease reserve(int maxTokens) {
            if (rejectWith != null) {
                throw rejectWith;
            }
            reservedMaxTokens.add(maxTokens);
            return new Lease() {
                @Override
                public void settle(String model, Long promptTokens, Long completionTokens) {
                    settledModel = model;
                    settledPromptTokens = promptTokens;
                    settledCompletionTokens = completionTokens;
                    settled++;
                    terminal.countDown();
                }

                @Override
                public void abandon() {
                    abandoned++;
                    terminal.countDown();
                }
            };
        }
    }
}
