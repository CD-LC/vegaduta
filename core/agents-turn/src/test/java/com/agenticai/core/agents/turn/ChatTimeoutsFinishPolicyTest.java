package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * {@code app.chat.finish.*} reaches the turn through {@link ChatTimeouts}: the configured bean when
 * there is one, the shipped defaults otherwise (a slim context, a hand-built fixture), so a deployment
 * that never mentions the group still gets the finish-reason handling.
 */
class ChatTimeoutsFinishPolicyTest {

    private static final Duration D = Duration.ofSeconds(1);

    @Test
    void theConfiguredPropertiesBeanIsUsedWhenThereIsOne() {
        ChatFinishProperties configured = new ChatFinishProperties();
        configured.setMaxContinuations(5);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("finish", configured);

        ChatTimeouts timeouts = new ChatTimeouts(D, D, D, 3, beans.getBeanProvider(ChatFinishProperties.class));

        assertThat(timeouts.finish()).isSameAs(configured);
        assertThat(timeouts.maxContinuations()).isEqualTo(3);
    }

    @Test
    void withNoBeanTheShippedDefaultsApply() {
        ChatTimeouts timeouts =
                new ChatTimeouts(
                        D, D, D, 3, new StaticListableBeanFactory().getBeanProvider(ChatFinishProperties.class));

        assertThat(timeouts.finish().isEnabled()).isTrue();
        assertThat(timeouts.finish().getMaxContinuations()).isEqualTo(2);
        assertThat(new ChatTimeouts(D, D, D).finish().getEmptyReplyMessage())
                .isEqualTo(ChatFinishProperties.defaultEmptyReplyMessage());
    }
}
