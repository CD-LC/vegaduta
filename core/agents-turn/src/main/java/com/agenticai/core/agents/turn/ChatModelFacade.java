package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.turn.quality.LlmQualityJudgeService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.chat.ProviderErrorTranslator;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Everything {@link AgentChatService} needs to reach an LLM: credential resolution, the gateway,
 * provider-error translation, the free-tier client, the quality judge, the complexity classifier
 * and the (optional) hosted pool. Constructor-injected as one unit so the service's own constructor
 * stays small; holds no logic.
 */
@Component
record ChatModelFacade(
        ProviderCredentialResolver credentialResolver,
        LlmGatewayService llmGatewayService,
        ProviderErrorTranslator providerErrorTranslator,
        FreeTierChatClientService freeTierChatClientService,
        LlmQualityJudgeService llmQualityJudgeService,
        QueryComplexityClassifier queryComplexityClassifier,
        ObjectProvider<PlatformHostedLlmProviderService> platformHostedLlmProviderService) {}
