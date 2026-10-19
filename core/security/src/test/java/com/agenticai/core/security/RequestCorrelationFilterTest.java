package com.agenticai.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * {@link RequestCorrelationFilter} is what stamps every request's log lines
 * and the {@code X-Request-Id} response header with a correlation id - the
 * mechanism {@code LlmMetricsAdvisor} (core/chat) and any client-side bug
 * report rely on to line a failure up with server-side log lines. Had zero
 * test coverage before this suite.
 *
 * <p>Calls {@code doFilterInternal} directly rather than the inherited
 * {@code doFilter}, same approach as {@link TenantContextFilterTest} and for
 * the same reason - both are accessible in-package and going through the
 * real {@code OncePerRequestFilter} dispatch machinery would add mocking
 * noise with no bearing on this class's own logic.
 */
class RequestCorrelationFilterTest {

    private static final String UUID_PATTERN =
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static HttpServletRequest requestMock(String incomingRequestId) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)).thenReturn(incomingRequestId);
        return request;
    }

    @Test
    void noIncomingHeaderGeneratesRandomUuidAndPropagatesItToMdcAndResponseHeader() throws Exception {
        HttpServletRequest request = requestMock(null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcDuringChain.set(MDC.get(RequestCorrelationFilter.MDC_KEY));
            return null;
        }).when(chain).doFilter(any(), any());

        new RequestCorrelationFilter().doFilterInternal(request, response, chain);

        verify(chain, times(1)).doFilter(any(), any());
        String generated = mdcDuringChain.get();
        assertNotNull(generated);
        assertTrue(generated.matches(UUID_PATTERN), "expected a random UUID, got: " + generated);
        verify(response).setHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, generated);

        // The filter's own finally block must clear MDC once the chain
        // returns - a request-scoped value must never leak onto whatever
        // the next request handled on this (pooled) thread logs.
        assertNull(MDC.get(RequestCorrelationFilter.MDC_KEY));
    }

    @Test
    void blankIncomingHeaderIsTreatedAsAbsentAndGeneratesAFreshId() throws Exception {
        HttpServletRequest request = requestMock("   ");
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcDuringChain.set(MDC.get(RequestCorrelationFilter.MDC_KEY));
            return null;
        }).when(chain).doFilter(any(), any());

        new RequestCorrelationFilter().doFilterInternal(request, response, chain);

        String generated = mdcDuringChain.get();
        assertNotNull(generated);
        assertTrue(generated.matches(UUID_PATTERN), "expected a random UUID, got: " + generated);
        assertFalse(generated.isBlank());
        verify(response).setHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, generated);
    }

    @Test
    void incomingHeaderIsReusedVerbatimInsteadOfGeneratingANewId() throws Exception {
        String callerSuppliedId = "caller-supplied-trace-id-123";
        HttpServletRequest request = requestMock(callerSuppliedId);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        AtomicReference<String> mdcDuringChain = new AtomicReference<>();
        doAnswer(invocation -> {
            mdcDuringChain.set(MDC.get(RequestCorrelationFilter.MDC_KEY));
            return null;
        }).when(chain).doFilter(any(), any());

        new RequestCorrelationFilter().doFilterInternal(request, response, chain);

        assertEquals(callerSuppliedId, mdcDuringChain.get());
        verify(response).setHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, callerSuppliedId);
        assertNull(MDC.get(RequestCorrelationFilter.MDC_KEY));
    }

    @Test
    void mdcIsClearedEvenWhenDownstreamChainThrows() throws Exception {
        // The correlation id must never leak onto the next request handled
        // on this (pooled) thread, even when the chain fails - mirrors
        // TenantContextFilter's equivalent "always clear in finally"
        // guarantee for TenantContext.
        HttpServletRequest request = requestMock(null);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        doThrow(new IOException("downstream failure")).when(chain).doFilter(any(), any());

        assertThrows(IOException.class, () ->
            new RequestCorrelationFilter().doFilterInternal(request, response, chain));

        assertNull(MDC.get(RequestCorrelationFilter.MDC_KEY));
    }

    @Test
    void twoRequestsWithoutIncomingHeaderGetDifferentGeneratedIds() throws Exception {
        // Not a hard guarantee UUIDs never collide, but a regression here
        // (e.g. a cached/static id, or reusing the previous request's MDC
        // value) is exactly the kind of bug this filter exists to prevent.
        FilterChain chain = mock(FilterChain.class);
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        RequestCorrelationFilter filter = new RequestCorrelationFilter();

        doAnswer(invocation -> {
            first.set(MDC.get(RequestCorrelationFilter.MDC_KEY));
            return null;
        }).when(chain).doFilter(any(), any());
        filter.doFilterInternal(requestMock(null), mock(HttpServletResponse.class), chain);

        doAnswer(invocation -> {
            second.set(MDC.get(RequestCorrelationFilter.MDC_KEY));
            return null;
        }).when(chain).doFilter(any(), any());
        filter.doFilterInternal(requestMock(null), mock(HttpServletResponse.class), chain);

        assertNotNull(first.get());
        assertNotNull(second.get());
        assertFalse(first.get().equals(second.get()));
    }
}
