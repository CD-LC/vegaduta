package com.agenticai.core.agents.turn;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The three turn-level durations {@link AgentChatService} is configured with. */
@Component
final class ChatTimeouts {

    private final Duration streamIdleTimeout;
    private final Duration bufferedTurnTimeout;
    private final Duration bufferedKeepAliveInterval;
    private final int maxContinuations;

    /** Test seam: continuation off. */
    ChatTimeouts(
            Duration streamIdleTimeout,
            Duration bufferedTurnTimeout,
            Duration bufferedKeepAliveInterval) {
        this(streamIdleTimeout, bufferedTurnTimeout, bufferedKeepAliveInterval, 0);
    }

    @Autowired
    ChatTimeouts(
            @Value("${app.llm.http.stream-idle-timeout:90s}") Duration streamIdleTimeout,
            @Value("${app.llm.http.buffered-turn-timeout:240s}") Duration bufferedTurnTimeout,
            @Value("${app.chat.sse.buffered-keepalive-interval:20s}")
                    Duration bufferedKeepAliveInterval,
            @Value("${app.chat.continuation.max-passes:8}") int maxContinuations) {
        this.streamIdleTimeout = streamIdleTimeout;
        this.bufferedTurnTimeout = bufferedTurnTimeout;
        this.bufferedKeepAliveInterval = bufferedKeepAliveInterval;
        this.maxContinuations = maxContinuations;
    }

    int maxContinuations() {
        return maxContinuations;
    }

    Duration streamIdleTimeout() {
        return streamIdleTimeout;
    }

    Duration bufferedTurnTimeout() {
        return bufferedTurnTimeout;
    }

    Duration bufferedKeepAliveInterval() {
        return bufferedKeepAliveInterval;
    }
}
