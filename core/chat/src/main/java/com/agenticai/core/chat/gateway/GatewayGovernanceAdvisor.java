package com.agenticai.core.chat.gateway;

import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;

/**
 * Marker for advisors attached to EVERY governed {@link
 * org.springframework.ai.chat.client.ChatClient} - via {@code
 * client.mutate().defaultAdvisors(...)} on a factory-built client, so no
 * provider factory needs to know this interface exists. Two attach points,
 * both injecting {@code List<GatewayGovernanceAdvisor>} (every bean in the
 * context):
 * <ul>
 *   <li>{@link LlmGatewayService#resolveClient} - BYOK/tenant-credential
 *       clients (agent chat via {@code ChatClientResolver}/{@code
 *       ChatTurnExecutor}, plus assist, workflow, judge, summarisation...).</li>
 *   <li>{@code FreeTierChatClientService#buildLink} - platform-hosted-pool
 *       clients, i.e. the free-tier agent-chat path, which never goes through
 *       {@code resolveClient}. Added 2026-09-28: before that, governance
 *       silently skipped every hosted-pool turn.</li>
 * </ul>
 * A client is built by exactly one of the two, so nothing is attached twice;
 * implementations additionally mark the request context so a duplicate
 * attachment would still apply them only once. Ordering within the chain is
 * each advisor's own {@code getOrder()} (Spring AI sorts the chain).
 *
 * <p>This is the gateway's opt-out-free enforcement hook: an advisor that
 * only lives in a module's own call-site wiring can be bypassed by any
 * caller that forgets to add it, but anything registered HERE rides every
 * resolved client regardless of which call site asked for it or which
 * provider built it - that is what makes it "governance" rather than a
 * suggestion. It exists because the alternative (every {@code
 * AgentChatClientFactory} injecting a shared list) is exactly the gap {@link
 * com.agenticai.core.chat.DefaultAgentAdvisor} already demonstrates: only
 * the Anthropic factory consumes that list today, so a cross-cutting rule
 * registered there silently misses every other provider.
 *
 * <p>Implementations live in modules that can see the machinery they wrap
 * (e.g. {@code core/agents}, which has the guardrails engine on its
 * classpath); the interface itself lives here so {@code chat} never depends
 * on them - the same dependency-inversion posture {@code DefaultAgentAdvisor}
 * documents. A context with no beans simply attaches nothing.
 *
 * <p>Requires BOTH {@link CallAdvisor} and {@link StreamAdvisor} for the
 * same reason {@code DefaultAgentAdvisor} does: this codebase uses
 * {@code .call()} and {@code .stream()} on the same clients, and an advisor
 * implementing only one would silently miss the other path.
 */
public interface GatewayGovernanceAdvisor extends CallAdvisor, StreamAdvisor {
}
