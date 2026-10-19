package com.agenticai.core.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Direct feedback ("Store sufficient context for future analysis, including:
 * ... Request correlation ID ... Ensure these logs are accessible for future
 * review and troubleshooting.") - stamps every request with a correlation id
 * (reused from the caller's own {@code X-Request-Id} header if it sent one,
 * otherwise a fresh random one) into SLF4J's {@link MDC} for the lifetime of
 * that request, and echoes it back as a response header so a client-side bug
 * report can be matched to server-side log lines/{@code llm_call_log} rows
 * by that one id. Every log line emitted anywhere in this app during a
 * request now carries this id automatically once {@code
 * logging.pattern.console} includes {@code %X{requestId}} (see
 * {@code application.yml}) - no call site has to pass it explicitly.
 *
 * <p>Registered as a plain {@code @Component} (unlike {@link
 * TenantContextFilter}, which is built manually in {@code SecurityConfig} so
 * it can be positioned relative to Spring Security's own filters) because
 * this filter has no such ordering dependency - it just needs to run before
 * everything else so the id is already in MDC when any other filter,
 * including Spring Security's chain, logs anything. {@link
 * Ordered#HIGHEST_PRECEDENCE} on a Spring Boot auto-registered {@code
 * Filter} bean guarantees that without needing an explicit {@code
 * FilterRegistrationBean}.
 *
 * <p>{@link LlmMetricsAdvisor} (core/chat) reads {@link #MDC_KEY} directly
 * to persist this same id onto the {@code llm_call_log} row for a failed
 * call, capturing it synchronously on the request thread (same "capture
 * now, re-apply later on whatever thread the async callback fires on"
 * pattern that class's own javadoc already documents for {@code
 * TenantContext} - MDC is thread-local too, so the value would otherwise be
 * empty by the time a streaming response's {@code doOnError} callback runs
 * on a different Reactor thread).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        String requestId = (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming;
        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
