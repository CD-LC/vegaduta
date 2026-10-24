package com.agenticai.core.agents.turn;

import com.agenticai.core.prompts.PromptDefaultAlias;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Hands the prompt registry the deprecated {@code app.*} properties that seed the platform default of a
 * key (see {@link PromptPropertyAliases}). Registered as {@link PromptDefaultAlias} beans, which the
 * resolver collects; nothing else reads these properties.
 */
@Configuration(proxyBeanMethods = false)
class PromptPropertyAliasConfiguration {

    @Bean
    PromptDefaultAlias continueUnfinishedNudgeAlias(ObjectProvider<ContinuationProperties> continuation) {
        return PromptPropertyAliases.continueUnfinished(continuation.getIfAvailable());
    }

    @Bean
    PromptDefaultAlias replyCutOffNudgeAlias(ObjectProvider<ChatFinishProperties> finish) {
        return PromptPropertyAliases.cutOff(finish.getIfAvailable());
    }

    @Bean
    PromptDefaultAlias retryToolCallNudgeAlias(ObjectProvider<ChatFinishProperties> finish) {
        return PromptPropertyAliases.retryToolCall(finish.getIfAvailable());
    }

    @Bean
    PromptDefaultAlias truncatedNoticeAlias(ObjectProvider<ChatFinishProperties> finish) {
        return PromptPropertyAliases.truncatedNotice(finish.getIfAvailable());
    }

    @Bean
    PromptDefaultAlias refusalNoticeAlias(ObjectProvider<ChatFinishProperties> finish) {
        return PromptPropertyAliases.refusalNotice(finish.getIfAvailable());
    }

    @Bean
    PromptDefaultAlias malformedToolCallNoticeAlias(ObjectProvider<ChatFinishProperties> finish) {
        return PromptPropertyAliases.malformedToolCallNotice(finish.getIfAvailable());
    }

    @Bean
    PromptDefaultAlias emptyReplyMessageAlias(ObjectProvider<ChatFinishProperties> finish) {
        return PromptPropertyAliases.emptyReplyMessage(finish.getIfAvailable());
    }

    @Bean
    PromptDefaultAlias lowConfidenceMarkersAlias(
            @Value("${app.chat.escalation.low-confidence-markers:}") String markersCsv) {
        return PromptPropertyAliases.lowConfidenceMarkers(markersCsv);
    }
}
