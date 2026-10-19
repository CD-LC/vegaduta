package com.agenticai.core.eventbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaAdmin;

class WorkflowKafkaTopicBackgroundInitTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<KafkaAdmin> providerOf(KafkaAdmin admin) {
        ObjectProvider<KafkaAdmin> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(admin);
        return provider;
    }

    @Test
    void createsTopicsOnTheFirstAttemptWhenTheBrokerIsUp() {
        KafkaAdmin admin = mock(KafkaAdmin.class);
        when(admin.initialize()).thenReturn(true);
        List<Long> sleeps = new ArrayList<>();

        new WorkflowKafkaTopicBackgroundInit(providerOf(admin), 3, 30).createTopics(sleeps::add);

        verify(admin, times(1)).initialize();
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retriesWithThePausePerAttemptUntilTheBrokerAnswers() {
        KafkaAdmin admin = mock(KafkaAdmin.class);
        when(admin.initialize()).thenReturn(false, false, true);
        List<Long> sleeps = new ArrayList<>();

        new WorkflowKafkaTopicBackgroundInit(providerOf(admin), 3, 30).createTopics(sleeps::add);

        verify(admin, times(3)).initialize();
        assertThat(sleeps).containsExactly(30_000L, 30_000L);
    }

    @Test
    void givesUpAfterTheConfiguredAttemptsAndNeverThrows() {
        KafkaAdmin admin = mock(KafkaAdmin.class);
        when(admin.initialize()).thenReturn(false);
        List<Long> sleeps = new ArrayList<>();

        // Reaching the assertion without an exception is the contract: a down broker must not surface.
        new WorkflowKafkaTopicBackgroundInit(providerOf(admin), 2, 5).createTopics(sleeps::add);

        verify(admin, times(2)).initialize();
        assertThat(sleeps).containsExactly(5_000L);
    }

    @Test
    void attemptsAreClampedToAtLeastOne() {
        KafkaAdmin admin = mock(KafkaAdmin.class);
        when(admin.initialize()).thenReturn(false);

        new WorkflowKafkaTopicBackgroundInit(providerOf(admin), 0, 5).createTopics(ms -> { });

        verify(admin, times(1)).initialize();
    }

    @Test
    void doesNothingWhenThereIsNoKafkaAdminBean() {
        List<Long> sleeps = new ArrayList<>();

        // No exception and no pause: with nothing to create there is nothing to retry.
        new WorkflowKafkaTopicBackgroundInit(providerOf(null), 3, 30).createTopics(sleeps::add);

        assertThat(sleeps).isEmpty();
    }

    @Test
    void stopsRetryingWhenTheThreadIsInterruptedDuringThePause() {
        KafkaAdmin admin = mock(KafkaAdmin.class);
        when(admin.initialize()).thenReturn(false);

        try {
            new WorkflowKafkaTopicBackgroundInit(providerOf(admin), 3, 30)
                .createTopics(ms -> Thread.currentThread().interrupt());

            verify(admin, times(1)).initialize();
        } finally {
            Thread.interrupted(); // clear the flag so the test runner thread is not left interrupted
        }
    }
}
