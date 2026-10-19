package com.agenticai.core.chat;

import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;

/**
 * Marker for advisors that {@link AnthropicAgentChatClientFactory} should
 * register as a default advisor on EVERY {@link
 * org.springframework.ai.chat.client.ChatClient} it builds, alongside the
 * always-present {@code LlmMetricsAdvisor} - so a cross-cutting advisor in
 * another module (e.g. {@code core/rl}'s {@code RlTrajectoryAdvisor}) can opt
 * into the single chat-client chokepoint without {@code chat} having to depend
 * on that module.
 *
 * <p>This is the seam that keeps the dependency direction correct: {@code rl}
 * depends on {@code chat} (to implement this interface and reuse {@code
 * LlmMetricsAdvisor}'s context-param convention), and the factory injects
 * {@code List<DefaultAgentAdvisor>} - so {@code chat} never references {@code
 * rl}, no cycle. Any bean implementing this interface is picked up
 * automatically; a context with none simply gets an empty list and the
 * factory's behavior is unchanged.
 *
 * <p>Requires both {@link CallAdvisor} and {@link StreamAdvisor} because this
 * app uses both {@code ChatClient...call()} (CAREFUL/RIGOROUS/PLANNED depths)
 * and {@code ChatClient...stream()} (the default FAST depth) - an advisor that
 * implemented only one would silently miss every call site on the other path,
 * the exact gap {@code LlmMetricsAdvisor}'s javadoc documents (#599).
 */
public interface DefaultAgentAdvisor extends CallAdvisor, StreamAdvisor {
}
