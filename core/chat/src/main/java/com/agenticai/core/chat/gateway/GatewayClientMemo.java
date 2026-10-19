package com.agenticai.core.chat.gateway;

import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Optional seam in {@link LlmGatewayService#resolveClient}: lets a bean reuse the already-built {@code
 * ChatClient} for identical (tenant, provider, model, sampling, credential) inputs instead of rebuilding it
 * every turn. Budget and rate-limit assertions and the model-preference lookup still run on EVERY call, before
 * this is consulted; only object construction is memoised. No bean = today's behaviour.
 */
public interface GatewayClientMemo {

    /**
     * @param effectiveModel the model after the tenant's AI-model preference was applied
     * @param builder builds the client (registry create + governance advisors) when nothing reusable is held
     */
    ChatClient get(String tenantId, GatewayCallSpec spec, String effectiveModel, Supplier<ChatClient> builder);
}
