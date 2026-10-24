package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.chat.LlmRetryProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * {@code app.chat.continuation.*} and {@code app.chat.follow-up.*} reach the turn through {@link
 * ChatTimeouts}: the configured beans when there are some, the shipped defaults otherwise.
 */
class ChatTimeoutsConversationPolicyTest {

    private static final Duration D = Duration.ofSeconds(1);

    private static <T> ObjectProvider<T> providerOf(Class<T> type, T bean) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (bean != null) {
            beans.addBean(type.getSimpleName(), bean);
        }
        return beans.getBeanProvider(type);
    }

    private static ChatTimeouts spring(ContinuationProperties continuation, FollowUpProperties followUp) {
        return new ChatTimeouts(
                D,
                D,
                D,
                8,
                providerOf(ChatFinishProperties.class, null),
                providerOf(ChatResilienceProperties.class, null),
                providerOf(LlmRetryProperties.class, null),
                providerOf(com.agenticai.core.agents.tools.ToolReplaySafety.class, null),
                providerOf(ContinuationProperties.class, continuation),
                providerOf(FollowUpProperties.class, followUp));
    }

    @Test
    void theConfiguredPropertiesAreUsedAndTheContinuationPolicyIsBuiltFromThem() {
        ContinuationProperties continuation = new ContinuationProperties();
        continuation.setHeuristicScope(ContinuationProperties.Scope.ALL_MODELS);
        FollowUpProperties followUp = new FollowUpProperties();
        followUp.setShortMessageChars(7);

        ChatTimeouts timeouts = spring(continuation, followUp);

        assertThat(timeouts.continuationProperties()).isSameAs(continuation);
        assertThat(timeouts.followUp()).isSameAs(followUp);
        assertThat(timeouts.continuation().heuristicApplies(false)).isTrue();
    }

    @Test
    void withNoBeansTheShippedDefaultsApply() {
        ChatTimeouts timeouts = spring(null, null);

        assertThat(timeouts.continuation().heuristicApplies(false)).as("a model that is not a weak tool caller").isFalse();
        assertThat(timeouts.continuation().heuristicApplies(true)).isTrue();
        assertThat(timeouts.followUp().getShortMessageChars()).isEqualTo(40);
        assertThat(new ChatTimeouts(D, D, D).continuation().heuristicApplies(true)).isTrue();
    }
}
