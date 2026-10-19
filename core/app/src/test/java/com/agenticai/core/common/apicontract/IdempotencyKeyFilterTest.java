package com.agenticai.core.common.apicontract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.common.ErrorResponseFactory;
import com.agenticai.core.security.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The Idempotency-Key contract over an in-memory stand-in for Redis. */
class IdempotencyKeyFilterTest {

    private final Map<String, String> store = new HashMap<>();
    private final AtomicInteger handlerRuns = new AtomicInteger();
    private IdempotencyKeyFilter filter;
    private int handlerStatus = 201;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(i -> store.get(i.<String>getArgument(0)));
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(i -> store.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
        org.mockito.Mockito.doAnswer(i -> store.put(i.getArgument(0), i.getArgument(1)))
                .when(ops)
                .set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(i -> store.remove(i.<String>getArgument(0)) != null);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("redis", redis);
        filter =
                new IdempotencyKeyFilter(
                        beans.getBeanProvider(StringRedisTemplate.class),
                        new ErrorResponseFactory(true, null),
                        24,
                        300);
        TenantContext.set(TenantContext.of("t1", "alice", Set.of()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private MockHttpServletResponse post(String key, String body) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/agents");
        req.setContentType("application/json");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (key != null) {
            req.addHeader(IdempotencyKeyFilter.HEADER, key);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(
                req,
                res,
                (rq, rs) -> {
                    handlerRuns.incrementAndGet();
                    String seen = new String(rq.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    ((jakarta.servlet.http.HttpServletResponse) rs).setStatus(handlerStatus);
                    rs.setContentType("application/json");
                    rs.getWriter().write("{\"echo\":" + seen + ",\"run\":" + handlerRuns.get() + "}");
                });
        return res;
    }

    @Test
    void retryWithSameKeyAndBodyReplaysTheFirstResponse() throws Exception {
        MockHttpServletResponse first = post("k-1", "{\"name\":\"a\"}");
        MockHttpServletResponse second = post("k-1", "{\"name\":\"a\"}");
        assertThat(handlerRuns).hasValue(1);
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(first.getContentAsString()).isEqualTo("{\"echo\":{\"name\":\"a\"},\"run\":1}");
        assertThat(second.getStatus()).isEqualTo(201);
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString());
        assertThat(second.getHeader(IdempotencyKeyFilter.REPLAYED)).isEqualTo("true");
        assertThat(first.getHeader(IdempotencyKeyFilter.REPLAYED)).isNull();
    }

    @Test
    void sameKeyWithDifferentBodyIs422() throws Exception {
        post("k-2", "{\"name\":\"a\"}");
        MockHttpServletResponse second = post("k-2", "{\"name\":\"b\"}");
        assertThat(second.getStatus()).isEqualTo(422);
        assertThat(second.getContentAsString()).contains("idempotency-key-reused");
        assertThat(handlerRuns).hasValue(1);
    }

    @Test
    void keyStillInFlightIs409WithRetryAfter() throws Exception {
        MockHttpServletResponse first = post("k-3", "{}");
        assertThat(first.getStatus()).isEqualTo(201);
        // Simulate a concurrent request: mark the stored record as in flight again.
        String storeKey =
                store.keySet().stream().filter(k -> k.startsWith("agentic:idem:")).findFirst().orElseThrow();
        store.put(storeKey, "in-flight:abc");
        MockHttpServletResponse second = post("k-3", "{}");
        assertThat(second.getStatus()).isEqualTo(409);
        assertThat(second.getHeader("Retry-After")).isEqualTo("1");
        assertThat(second.getContentType()).startsWith("application/problem+json");
    }

    @Test
    void serverErrorsAreNotStoredSoTheClientCanRetry() throws Exception {
        handlerStatus = 503;
        post("k-4", "{}");
        handlerStatus = 201;
        MockHttpServletResponse retry = post("k-4", "{}");
        assertThat(retry.getStatus()).isEqualTo(201);
        assertThat(handlerRuns).hasValue(2);
    }

    @Test
    void invalidKeyIs400AndNoKeyIsUntouched() throws Exception {
        assertThat(post("has space", "{}").getStatus()).isEqualTo(400);
        assertThat(post(null, "{}").getStatus()).isEqualTo(201);
        assertThat(post(null, "{}").getStatus()).isEqualTo(201);
        assertThat(handlerRuns).hasValue(2);
    }

    @Test
    void routesWithTheirOwnSemanticsAndReadsAreNotCovered() {
        assertThat(IdempotencyKeyFilter.covers("POST", "/api/agents")).isTrue();
        assertThat(IdempotencyKeyFilter.covers("PATCH", "/api/agents/{agentId}/prompt")).isTrue();
        assertThat(IdempotencyKeyFilter.covers("GET", "/api/agents")).isFalse();
        assertThat(IdempotencyKeyFilter.covers("PUT", "/api/workflows/{workflowId}")).isFalse();
        assertThat(IdempotencyKeyFilter.covers("POST", "/api/workflows/{workflowId}/run")).isFalse();
        assertThat(IdempotencyKeyFilter.covers("POST", "/api/agents/{agentId}/quality/datasets/{datasetId}/runs"))
                .isFalse();
        assertThat(IdempotencyKeyFilter.covers("POST", "/webhook/slack")).isFalse();
    }
}
