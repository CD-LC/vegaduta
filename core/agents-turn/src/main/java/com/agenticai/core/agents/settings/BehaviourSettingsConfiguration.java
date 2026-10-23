package com.agenticai.core.agents.settings;

import com.agenticai.core.agents.auxiliary.AuxiliaryModelProperties;
import com.agenticai.core.agents.memory.MemoryLimitsProperties;
import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import com.agenticai.core.agents.turn.ChatCacheProperties;
import com.agenticai.core.agents.turn.ChatFinishProperties;
import com.agenticai.core.agents.turn.ContinuationProperties;
import com.agenticai.core.agents.turn.FollowUpProperties;
import com.agenticai.core.agents.turn.quality.RoutingSimpleProperties;
import com.agenticai.core.approval.ApprovalExpiryProperties;
import com.agenticai.core.chat.LlmRetryProperties;
import com.agenticai.core.chat.ToolLoopProperties;
import com.agenticai.core.guardrails.GuardrailTuningProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link BehaviourSettingsCatalog} with the property beans the runtime reads (an absent bean falls back to
 * that class's code defaults, the same values the runtime would use).
 */
@Configuration(proxyBeanMethods = false)
class BehaviourSettingsConfiguration {

    @Bean
    BehaviourSettingsCatalog behaviourSettingsCatalog(
            ObjectProvider<ApprovalContinuationProperties> approvalContinuation,
            ObjectProvider<ApprovalExpiryProperties> approvalExpiry,
            ObjectProvider<ChatFinishProperties> finish,
            ObjectProvider<LlmRetryProperties> retry,
            ObjectProvider<ChatCacheProperties> cache,
            ObjectProvider<FollowUpProperties> followUp,
            ObjectProvider<ContinuationProperties> continuation,
            ObjectProvider<ToolLoopProperties> toolLoop,
            ObjectProvider<RoutingSimpleProperties> routing,
            ObjectProvider<MemoryLimitsProperties> memory,
            ObjectProvider<GuardrailTuningProperties> guardrails,
            ObjectProvider<AuxiliaryModelProperties> auxiliary) {
        return new BehaviourSettingsCatalog(
                approvalContinuation.getIfAvailable(ApprovalContinuationProperties::new),
                approvalExpiry.getIfAvailable(ApprovalExpiryProperties::new),
                finish.getIfAvailable(ChatFinishProperties::new),
                retry.getIfAvailable(LlmRetryProperties::new),
                cache.getIfAvailable(ChatCacheProperties::new),
                followUp.getIfAvailable(FollowUpProperties::new),
                continuation.getIfAvailable(ContinuationProperties::new),
                toolLoop.getIfAvailable(ToolLoopProperties::new),
                routing.getIfAvailable(RoutingSimpleProperties::new),
                memory.getIfAvailable(MemoryLimitsProperties::new),
                guardrails.getIfAvailable(GuardrailTuningProperties::new),
                auxiliary.getIfAvailable(AuxiliaryModelProperties::new));
    }

    @Bean
    ProductControlsCatalog productControlsCatalog(
            ObjectProvider<com.agenticai.core.agents.turn.cache.SemanticCacheProperties> semanticCache,
            ObjectProvider<com.agenticai.core.agents.turn.ChatLimitsProperties> limits,
            ObjectProvider<FollowUpProperties> followUp,
            ObjectProvider<MemoryLimitsProperties> memory,
            ObjectProvider<com.agenticai.core.agents.turn.ToolLedgerProperties> ledger) {
        return new ProductControlsCatalog(
                semanticCache.getIfAvailable(com.agenticai.core.agents.turn.cache.SemanticCacheProperties::new),
                limits.getIfAvailable(com.agenticai.core.agents.turn.ChatLimitsProperties::new),
                followUp.getIfAvailable(FollowUpProperties::new),
                memory.getIfAvailable(MemoryLimitsProperties::new),
                ledger.getIfAvailable(com.agenticai.core.agents.turn.ToolLedgerProperties::new));
    }
}
