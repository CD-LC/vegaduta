package com.agenticai.core.omnichannel.session;

import com.agenticai.core.security.concurrent.ContextPropagatingExecutor;
import com.agenticai.core.security.concurrent.PlatformExecutorConfiguration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Runs the reply leg of an inbound channel message (the agent turn plus the approval-gated send)
 * off the webhook request thread.
 *
 * <p>Before this, Slack, Telegram, Discord, Teams and Messenger/Instagram answered their webhook
 * only after {@code agentChatService.chat(...).block()} returned: every inbound message held a
 * Tomcat thread for the whole LLM latency, Slack retried after ~3 s (its dedup key stopped the
 * double reply but not the thread burn), and a burst of messages could exhaust the request pool for
 * every other caller. The WhatsApp and Twilio paths had already moved to this shape; this is the
 * shared piece so the others do the same thing the same way.
 *
 * <p>The caller's {@link com.agenticai.core.security.TenantContext} and MDC are captured on the
 * request thread (which the webhook's tenant resolution already populated) and re-applied inside
 * the task. The executor is the bounded platform virtual-thread executor; a failure is logged and
 * never propagates (the webhook has already been acknowledged).
 */
@Component
public class AsyncInboundReply {

    private static final Logger log = LoggerFactory.getLogger(AsyncInboundReply.class);

    private final Executor executor;

    public AsyncInboundReply(
            @Qualifier(PlatformExecutorConfiguration.PLATFORM_EXECUTOR) Executor executor) {
        this.executor = executor;
    }

    /**
     * @param channel human label for the log line ("Slack", "Teams", ...)
     * @param sessionId the channel session the reply belongs to
     * @param replyLeg the LLM round trip and send; runs with the caller's tenant context
     */
    public void run(String channel, UUID sessionId, Runnable replyLeg) {
        CompletableFuture.runAsync(
                ContextPropagatingExecutor.propagating(
                        () -> {
                            try {
                                replyLeg.run();
                            } catch (RuntimeException e) {
                                log.warn(
                                        "Async {} reply processing failed for session {}: {}",
                                        channel,
                                        sessionId,
                                        e.getMessage(),
                                        e);
                            }
                        }),
                executor);
    }
}
