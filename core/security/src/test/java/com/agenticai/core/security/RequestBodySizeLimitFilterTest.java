package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestBodySizeLimitFilterTest {

    private final RequestBodySizeLimitFilter filter =
        new RequestBodySizeLimitFilter(1000, 100, List.of("/api/signup", "/api/devices/claim"));

    private static MockHttpServletRequest post(String path, byte[] body) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        req.setContentType("application/json");
        req.setContent(body);
        return req;
    }

    @Test
    void refusesDeclaredOversizeSignupBodyWith413BeforeTheChainRuns() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<ServletRequest> reached = new AtomicReference<>();
        filter.doFilter(post("/api/signup", new byte[101]), res, (rq, rs) -> reached.set(rq));
        assertEquals(413, res.getStatus());
        assertNull(reached.get());
    }

    @Test
    void appliesGlobalCapToOtherPaths() throws Exception {
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(post("/api/agents/x/chat", new byte[500]), ok, (rq, rs) -> { });
        assertEquals(200, ok.getStatus());

        MockHttpServletResponse tooBig = new MockHttpServletResponse();
        filter.doFilter(post("/api/agents/x/chat", new byte[1001]), tooBig, (rq, rs) -> { });
        assertEquals(413, tooBig.getStatus());
    }

    @Test
    void chunkedBodyWithoutContentLengthFailsTheReadOnceOverTheCap() throws Exception {
        // Transfer-Encoding: chunked = no declared length, so only the streaming count can catch it.
        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/api/signup") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        chunked.setContentType("application/json");
        chunked.setContent(new byte[500]);
        filter.doFilter(chunked, new MockHttpServletResponse(), (rq, rs) ->
            assertThrows(RequestBodySizeLimitFilter.BodyTooLargeException.class, () -> rq.getInputStream().readAllBytes()));
    }

    @Test
    void multipartIsLeftToSpringMultipartLimits() throws Exception {
        MockHttpServletRequest req = post("/api/knowledge/upload", new byte[5000]);
        req.setContentType("multipart/form-data; boundary=x");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicReference<ServletRequest> reached = new AtomicReference<>();
        filter.doFilter(req, res, (rq, rs) -> reached.set(rq));
        assertEquals(req, reached.get());
    }

    @Test
    void smallPathMatchIsPrefixAwareNotSubstring() {
        assertEquals(100, filter.limitFor("/api/signup"));
        assertEquals(100, filter.limitFor("/api/devices/claim/x"));
        assertEquals(1000, filter.limitFor("/api/signupfoo"));
    }
}
