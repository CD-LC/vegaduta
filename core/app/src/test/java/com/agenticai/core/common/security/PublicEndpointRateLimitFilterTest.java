package com.agenticai.core.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** No Redis bean -> exercises the in-process fallback, which must still limit. */
class PublicEndpointRateLimitFilterTest {

    private final PublicEndpointRateLimitFilter filter =
            new PublicEndpointRateLimitFilter(
                    new StaticListableBeanFactory().getBeanProvider(StringRedisTemplate.class),
                    2,
                    2,
                    2,
                    2,
                    2);

    @BeforeEach
    void clearFallback() {
        LocalRateLimitFallback.reset();
    }

    private int send(String method, String path, String ip, String deviceToken) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRemoteAddr(ip);
        if (deviceToken != null) {
            req.addHeader("X-Device-Token", deviceToken);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> {});
        return res.getStatus();
    }

    @Test
    void throttlesCheckoutPerIp() throws Exception {
        assertEquals(200, send("POST", "/api/commerce/feed/t1/checkout", "1.1.1.1", null));
        assertEquals(200, send("POST", "/api/commerce/feed/t2/checkout", "1.1.1.1", null));
        assertEquals(429, send("POST", "/api/commerce/feed/t1/checkout", "1.1.1.1", null));
        assertEquals(200, send("POST", "/api/commerce/feed/t1/checkout", "2.2.2.2", null));
    }

    @Test
    void checkoutRuleIsPostOnlyAndFeedReadsAreUntouched() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertEquals(200, send("GET", "/api/commerce/feed/t1", "3.3.3.3", null));
        }
    }

    @Test
    void devicesAreKeyedByTokenSoANatDoesNotShareABucket() throws Exception {
        for (int i = 0; i < 2; i++) {
            assertEquals(200, send("POST", "/api/devices/poll", "9.9.9.9", "device-a"));
        }
        assertEquals(429, send("POST", "/api/devices/poll", "9.9.9.9", "device-a"));
        assertEquals(200, send("POST", "/api/devices/sms", "9.9.9.9", "device-b"));
    }

    @Test
    void throttlesUnsubscribeAndScimAndIgnoresOtherPaths() throws Exception {
        send("GET", "/api/public/email/unsubscribe", "4.4.4.4", null);
        send("POST", "/api/public/email/unsubscribe", "4.4.4.4", null);
        assertEquals(429, send("GET", "/api/public/email/unsubscribe", "4.4.4.4", null));

        send("GET", "/scim/v2/Users", "5.5.5.5", null);
        send("GET", "/scim/v2/Users", "5.5.5.5", null);
        assertEquals(429, send("GET", "/scim/v2/Users", "5.5.5.5", null));

        for (int i = 0; i < 5; i++) {
            assertNotEquals(429, send("POST", "/api/agents/x/chat", "6.6.6.6", null));
        }
    }

    @Test
    void throttlesPublicStatusPollsPerIpForGetOnly() throws Exception {
        assertEquals(200, send("GET", "/api/public/status", "7.7.7.7", null));
        assertEquals(200, send("GET", "/api/public/status", "7.7.7.7", null));
        assertEquals(429, send("GET", "/api/public/status", "7.7.7.7", null));
        assertEquals(200, send("GET", "/api/public/status", "8.8.8.8", null));
    }
}
