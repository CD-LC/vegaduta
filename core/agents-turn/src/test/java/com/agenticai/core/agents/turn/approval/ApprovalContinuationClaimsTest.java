package com.agenticai.core.agents.turn.approval;

import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ApprovalContinuationClaimsTest {

    private final ApprovalContinuationProperties properties = new ApprovalContinuationProperties();
    private final Set<String> keys = new HashSet<>();
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private ApprovalContinuationClaims claims() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> keys.add(inv.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(inv -> keys.remove(inv.<String>getArgument(0)));
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return new ApprovalContinuationClaims(provider, properties);
    }

    @Test
    void aDecisionIsClaimedExactlyOnce() {
        ApprovalContinuationClaims claims = claims();
        UUID id = UUID.randomUUID();

        assertThat(claims.tryClaim(id)).isTrue();
        assertThat(claims.tryClaim(id)).isFalse();
        assertThat(claims.tryClaim(UUID.randomUUID())).isTrue();
    }

    @Test
    void aReleasedClaimCanBeTakenAgain() {
        ApprovalContinuationClaims claims = claims();
        UUID id = UUID.randomUUID();
        claims.tryClaim(id);

        claims.release(id);

        assertThat(claims.tryClaim(id)).isTrue();
    }

    @Test
    void withoutRedisNothingCanBeClaimed() {
        ApprovalContinuationClaims claims =
                new ApprovalContinuationClaims(
                        new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class), properties);

        assertThat(claims.tryClaim(UUID.randomUUID())).isFalse();
    }

    @Test
    void aRedisErrorMeansNoClaimNotAnException() {
        ApprovalContinuationClaims claims = claims();
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));

        assertThat(claims.tryClaim(UUID.randomUUID())).isFalse();
    }
}
