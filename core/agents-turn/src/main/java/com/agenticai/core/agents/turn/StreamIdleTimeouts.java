package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.hosted.HostedChainLink;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Points a turn at the serving pool row's own stream idle-timeout (V674); split from the
 * dispatcher.
 */
final class StreamIdleTimeouts {

    private StreamIdleTimeouts() {}

    static void apply(TurnContext context, List<HostedChainLink> chain, ChatClient client) {
        context.modelTraits()
                .streamIdleTimeout(HostedChainLink.streamIdleTimeoutFor(chain, client));
    }
}
