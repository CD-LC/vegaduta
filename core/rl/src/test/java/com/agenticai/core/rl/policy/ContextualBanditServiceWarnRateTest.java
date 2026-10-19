package com.agenticai.core.rl.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agenticai.core.rl.domain.AgentPolicy;
import com.agenticai.core.rl.domain.PolicyDimension;
import com.agenticai.core.rl.repo.AgentPolicyRepository;
import com.agenticai.core.rl.repo.PolicyArmRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** SE-19: the "no selectable arms" warning is rate limited to once per agent per hour. */
class ContextualBanditServiceWarnRateTest {

    @Test
    void warnsOncePerAgentPerHour() {
        AgentPolicyRepository policies = mock(AgentPolicyRepository.class);
        PolicyArmRepository arms = mock(PolicyArmRepository.class);
        UUID agentId = UUID.randomUUID();
        AgentPolicy enabled = new AgentPolicy(agentId, PolicyDimension.MODEL_ROUTE, true, new BigDecimal("0.1"));
        when(policies.findByAgentIdAndDimension(agentId, PolicyDimension.MODEL_ROUTE)).thenReturn(Optional.of(enabled));
        when(arms.findByPolicyIdAndEnabledTrue(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        ContextualBanditService service = new ContextualBanditService(policies, arms);
        AtomicLong clock = new AtomicLong(1_000L);
        service.setNanoClockForTest(clock::get);

        Logger logger = (Logger) LoggerFactory.getLogger(ContextualBanditService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int i = 0; i < 50; i++) {
                service.selectArm(agentId, PolicyDimension.MODEL_ROUTE, null);
            }
            assertThat(warnings(appender)).isEqualTo(1);

            clock.addAndGet(TimeUnit.MINUTES.toNanos(59));
            service.selectArm(agentId, PolicyDimension.MODEL_ROUTE, null);
            assertThat(warnings(appender)).isEqualTo(1);

            clock.addAndGet(TimeUnit.MINUTES.toNanos(2));
            service.selectArm(agentId, PolicyDimension.MODEL_ROUTE, null);
            assertThat(warnings(appender)).isEqualTo(2);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static long warnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
    }
}
