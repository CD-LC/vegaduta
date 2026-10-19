package com.agenticai.core.agents.loadtest;

import org.springframework.ai.chat.client.ChatClient;

/**
 * Wraps {@link LoadTestChatModel} in a {@link ChatClient} the exact same way
 * every real provider does it elsewhere in this codebase (see e.g. {@code
 * core/chat}'s {@code AnthropicAgentChatClientFactory}: {@code
 * ChatClient.builder(chatModel).build()}) - so {@code AgentChatService}'s
 * downstream turn logic (advisors, tool resolution, streaming consumption,
 * session memory, guardrails) runs completely unmodified for a load-test
 * turn; only the actual model call underneath is faked.
 *
 * <p>Deliberately a plain static factory, not a Spring bean: {@link
 * LoadTestChatModel} is stateless and has no credentials/config to inject,
 * so there is nothing a container needs to manage here, and keeping this out
 * of the bean graph means it can never be autowired anywhere by accident.
 *
 * <p>No default advisors are registered on the built client (unlike the real
 * per-provider factories, which attach {@code LlmMetricsAdvisor}) -
 * attaching that would record synthetic load-test turns into the same
 * cost/latency dashboards real provider calls feed, which is exactly the
 * kind of contamination the load-test seam exists to avoid. Per-call
 * advisors that {@code AgentChatService}'s dispatch functions attach
 * directly on {@code chatClient.prompt()...advisors(...)} (e.g. RL
 * trajectory recording) still apply normally, since those are added at the
 * call site, not here.
 */
public final class LoadTestChatClientFactory {

    private LoadTestChatClientFactory() {
    }

    public static ChatClient create() {
        return ChatClient.builder(new LoadTestChatModel()).build();
    }
}
