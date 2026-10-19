package com.agenticai.core.common.apicontract;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.common.ErrorResponseFactory;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** No Redis bean: the in-process fallback counts, as it does during a Redis outage. */
class ApiRateLimitFilterTest {

    @BeforeEach
    void setUp() {
        LocalRateLimitFallback.reset();
        TenantContext.set(TenantContext.of("t1", "integration-1", Set.of()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static ApiRateLimitFilter filter(boolean enabled) {
        return new ApiRateLimitFilter(
                new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class),
                new ErrorResponseFactory(true, null),
                enabled,
                2,
                60);
    }

    private static MockHttpServletResponse get(ApiRateLimitFilter f) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(new MockHttpServletRequest("GET", "/api/agents"), res, (rq, rs) -> {});
        return res;
    }

    @Test
    void sendsIetfHeadersAndAnswers429WithRetryAfterWhenSpent() throws Exception {
        ApiRateLimitFilter f = filter(true);
        MockHttpServletResponse first = get(f);
        assertThat(first.getHeader("RateLimit-Policy")).isEqualTo("\"api\";q=2;w=60");
        assertThat(first.getHeader("RateLimit")).startsWith("\"api\";r=1;t=");
        assertThat(get(f).getHeader("RateLimit")).startsWith("\"api\";r=0;t=");
        MockHttpServletResponse third = get(f);
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getHeader("Retry-After")).isNotNull();
        assertThat(third.getContentAsString()).contains("api-rate-limited");
    }

    @Test
    void offByDefaultAndSkipsUnauthenticatedCalls() throws Exception {
        ApiRateLimitFilter off = filter(false);
        for (int i = 0; i < 5; i++) {
            assertThat(get(off).getHeader("RateLimit")).isNull();
        }
        TenantContext.clear();
        ApiRateLimitFilter on = filter(true);
        for (int i = 0; i < 5; i++) {
            assertThat(get(on).getStatus()).isEqualTo(200);
        }
    }
}
