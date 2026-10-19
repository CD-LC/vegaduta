package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.chat.StreamEndExecutor;
import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.rl.api.RlTurnRecorder;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The stream-end collaborators ({@code agent.message.completed}, RL episode close) hand their work
 * to the {@link StreamEndExecutor} once it is wired, run inline before that, and lose nothing on a
 * graceful stop.
 */
class StreamEndHandOffTest {

    private StreamEndExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.stop();
        }
    }

    private StreamEndExecutor async() {
        executor = new StreamEndExecutor(true, 2, 100, Duration.ofSeconds(5));
        executor.start();
        return executor;
    }

    @Test
    void theMessageCompletedEventIsInlineUntilWiredThenOffThread() throws Exception {
        WorkflowEventPublisher publisher = mock(WorkflowEventPublisher.class);
        AtomicReference<Thread> publishedOn = new AtomicReference<>();
        AtomicReference<CountDownLatch> published = new AtomicReference<>(new CountDownLatch(1));
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            publishedOn.set(Thread.currentThread());
                            published.get().countDown();
                            return null;
                        })
                .when(publisher)
                .publish(anyString(), anyString(), any(), anyString());
        AgentMessageEvents events = new AgentMessageEvents();
        events.setPublisher(publisher);
        UUID agent = UUID.randomUUID();

        events.emitAgentMessageCompleted(agent, "s1", "t1", "hello", "live");
        assertEquals(
                Thread.currentThread(), publishedOn.get(), "inline before the executor is wired");

        events.setStreamEnd(async());
        publishedOn.set(null);
        published.set(new CountDownLatch(1));
        events.emitAgentMessageCompleted(agent, "s1", "t1", "hello again", "live");
        assertTrue(published.get().await(5, TimeUnit.SECONDS));
        assertNotEquals(Thread.currentThread(), publishedOn.get());
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRlEpisodeCloseIsDrainedOnStopNotLost() {
        RlTurnRecorder recorder = mock(RlTurnRecorder.class);
        ObjectProvider<RlTurnRecorder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(recorder);
        RlEpisodeRecorder rl = new RlEpisodeRecorder(provider);
        rl.setStreamEnd(async());
        UUID episode = UUID.randomUUID();

        for (int i = 0; i < 20; i++) {
            rl.completeRlEpisode(episode, "answer", Optional.empty());
        }
        executor.stop();

        verify(recorder, org.mockito.Mockito.times(20)).endTurn(eq(episode), eq(true));
    }
}
