package com.agenticai.core.rl.advisor;

import com.agenticai.core.chat.DefaultAgentAdvisor;
import com.agenticai.core.chat.LlmMetricsAdvisor;
import com.agenticai.core.rl.trajectory.TrajectoryService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Spring AI advisor that records ONE assistant {@code rl_trajectory_step} per
 * real LLM call, attached to the live episode for {@code (agentId, sessionId)}.
 * Deliberately modelled on {@code chat/LlmMetricsAdvisor} - the same dual
 * {@link CallAdvisor}/{@link StreamAdvisor} implementation (so both the
 * {@code .call()} path used by CAREFUL/RIGOROUS agents and the {@code
 * .stream()} path used by the default FAST depth are covered), and it reads
 * the agent/session ids from the exact same advisor-context params
 * ({@link LlmMetricsAdvisor#AGENT_ID_PARAM}/{@link LlmMetricsAdvisor#SESSION_ID_PARAM})
 * the orchestrator already threads for LLM metrics - so wiring this advisor in
 * requires no new params at the call site, just the one extra advisor.
 *
 * <p>Like {@code LlmMetricsAdvisor}, the streaming completion callback fires on
 * whatever Reactor/WebClient scheduler thread the response finishes on, not the
 * request thread, so {@code TenantContext} (a plain ThreadLocal backing the
 * schema-per-tenant write) is captured synchronously up front and re-applied
 * around the step write - otherwise every FAST-path trajectory step would
 * silently no-op, the identical bug that class documents.
 *
 * <p><b>Registration.</b> This is a {@link DefaultAgentAdvisor} bean, so
 * {@code AnthropicAgentChatClientFactory} picks it up by type and registers it
 * on every {@code ChatClient} it builds, alongside {@code LlmMetricsAdvisor} -
 * see that marker interface for why this keeps the dependency direction correct
 * ({@code rl} depends on {@code chat}, never the reverse, so no cycle).
 */
@Component
public class RlTrajectoryAdvisor implements DefaultAgentAdvisor {

    private final TrajectoryService trajectoryService;

    public RlTrajectoryAdvisor(TrajectoryService trajectoryService) {
        this.trajectoryService = trajectoryService;
    }

    @Override
    public String getName() {
        return "RlTrajectoryAdvisor";
    }

    @Override
    public int getOrder() {
        // After LlmMetricsAdvisor (order 0); trajectory recording is auxiliary
        // and must never sit between metrics and the model call.
        return 100;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        long startNanos = System.nanoTime();
        Optional<TenantContext> capturedContext = TenantContext.current();
        ChatClientResponse response = callAdvisorChain.nextCall(chatClientRequest);
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        withTenantContext(capturedContext, () ->
            recordStep(chatClientRequest, response == null ? null : response.chatResponse(), latencyMs));
        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        long startNanos = System.nanoTime();
        Optional<TenantContext> capturedContext = TenantContext.current();
        AtomicReference<ChatClientResponse> lastResponse = new AtomicReference<>();
        return streamAdvisorChain.nextStream(chatClientRequest)
            .doOnNext(lastResponse::set)
            .doOnComplete(() -> {
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                ChatClientResponse last = lastResponse.get();
                withTenantContext(capturedContext, () ->
                    recordStep(chatClientRequest, last == null ? null : last.chatResponse(), latencyMs));
            });
    }

    private void recordStep(ChatClientRequest chatClientRequest, ChatResponse chatResponse, long latencyMs) {
        UUID agentId = paramAsUuid(chatClientRequest, LlmMetricsAdvisor.AGENT_ID_PARAM);
        String sessionId = paramAsString(chatClientRequest, LlmMetricsAdvisor.SESSION_ID_PARAM);
        // Attribution params are opt-in (see LlmMetricsAdvisor) - without an
        // agent+session there is no live episode to attach to, so skip rather
        // than open an orphan one.
        if (agentId == null || sessionId == null) {
            return;
        }
        Long totalTokens = null;
        if (chatResponse != null && chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
            var usage = chatResponse.getMetadata().getUsage();
            totalTokens = usage.getTotalTokens() == null ? null : usage.getTotalTokens().longValue();
        }
        try {
            trajectoryService.recordLlmStep(agentId, sessionId, null, totalTokens, latencyMs);
        } catch (RuntimeException e) {
            // Trajectory recording is best-effort telemetry - never let it
            // surface into or fail the actual chat turn.
        }
    }

    private static void withTenantContext(Optional<TenantContext> capturedContext, Runnable work) {
        TenantScope.of(capturedContext).run(work);
    }

    private static UUID paramAsUuid(ChatClientRequest chatClientRequest, String key) {
        Object value = chatClientRequest.context().get(key);
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String paramAsString(ChatClientRequest chatClientRequest, String key) {
        Object value = chatClientRequest.context().get(key);
        return value == null ? null : value.toString();
    }
}
