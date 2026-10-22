package com.agenticai.core.agents.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Agent Lab lives only in Redis; with Redis down every entry point answers 503, not an unmapped
 * exception.
 */
class RedisAgentLabRunStoreOutageTest {

    private RedisAgentLabRunStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        RedisConnectionFailureException down =
                new RedisConnectionFailureException("Unable to connect to Redis");
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenThrow(down);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(down);
        when(redis.hasKey(anyString())).thenThrow(down);
        store = new RedisAgentLabRunStore(redis, new ObjectMapper());
    }

    private static void assertServiceUnavailable(Throwable e) {
        assertThat(e).isInstanceOf(ResponseStatusException.class);
        assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void pollingARunIsAServiceUnavailable() {
        assertThatThrownBy(() -> store.find("tenant", "run"))
                .satisfies(RedisAgentLabRunStoreOutageTest::assertServiceUnavailable);
    }

    @Test
    void startingARunIsAServiceUnavailable() {
        assertThatThrownBy(() -> store.acquireLock("tenant", "user", "run"))
                .satisfies(RedisAgentLabRunStoreOutageTest::assertServiceUnavailable);
    }

    @Test
    void livenessAndCancelChecksAreAServiceUnavailable() {
        assertThatThrownBy(() -> store.isAlive("tenant", "run"))
                .satisfies(RedisAgentLabRunStoreOutageTest::assertServiceUnavailable);
        assertThatThrownBy(() -> store.isCancelRequested("tenant", "run"))
                .satisfies(RedisAgentLabRunStoreOutageTest::assertServiceUnavailable);
    }
}
