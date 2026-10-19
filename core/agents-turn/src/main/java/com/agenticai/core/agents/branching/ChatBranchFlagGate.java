package com.agenticai.core.agents.branching;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Makes the chat-branching routes answer 404 while {@code app.chat.branching.enabled} is off BEFORE Spring
 * binds and validates a request body or path variable. Without this a flag-off POST with a blank message or a
 * missing messageId would answer 400 from bean validation, which tells a caller the route exists.
 * {@link ChatBranchService} still checks the flag itself (defence in depth, and it is the unit-tested seam).
 *
 * <p>The capabilities discovery call lives under another path and is deliberately not gated here.
 */
@Component
public class ChatBranchFlagGate implements HandlerInterceptor, WebMvcConfigurer {

    private final boolean enabled;

    public ChatBranchFlagGate(@Value("${app.chat.branching.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns(
            "/api/agents/*/sessions/*/messages",
            "/api/agents/*/sessions/*/messages/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return true;
    }
}
