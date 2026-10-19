package com.agenticai.core.common.apicontract;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.http.ApiDeprecated;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

class ApiDeprecationInterceptorTest {

    static class Handlers {
        @ApiDeprecated(since = "2026-10-01", sunset = "2027-04-01", replacement = "GET /api/v2/things")
        public String old() {
            return "";
        }

        public String current() {
            return "";
        }
    }

    private final ApiDeprecationInterceptor interceptor =
            new ApiDeprecationInterceptor("https://docs.example/deprecation");

    private MockHttpServletResponse call(String method) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        interceptor.preHandle(
                new MockHttpServletRequest(),
                res,
                new HandlerMethod(new Handlers(), Handlers.class.getMethod(method)));
        return res;
    }

    @Test
    void deprecatedHandlerGetsRfc9745AndRfc8594Headers() throws Exception {
        MockHttpServletResponse res = call("old");
        assertThat(res.getHeader("Deprecation")).isEqualTo("@1790812800");
        assertThat(res.getHeader("Sunset")).isEqualTo("Thu, 01 Apr 2027 00:00:00 GMT");
        assertThat(res.getHeader("Link"))
                .isEqualTo("<https://docs.example/deprecation>; rel=\"deprecation\"; type=\"text/html\"");
    }

    @Test
    void currentHandlerIsUntouched() throws Exception {
        MockHttpServletResponse res = call("current");
        assertThat(res.getHeader("Deprecation")).isNull();
        assertThat(res.getHeader("Link")).isNull();
    }
}
