package com.agenticai.core.mcpserver;

import com.agenticai.core.agents.openai.OpenAiCompatCallerScope;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Lets a {@code vmcp_} grant key authenticate against the OpenAI-compatible {@code /v1/**}
 * surface ({@code core/agents} {@code OpenAiCompatController}), alongside platform JWTs.
 * Only exists when {@code app.openai-compat.enabled=true}, exactly like the controller.
 *
 * <p>The controller lives in core/agents and mcpserver depends on agents, so the dependency runs
 * through the {@link OpenAiCompatCallerScope} seam: this module registers the key filter (the
 * SAME {@link McpApiKeyAuthFilter} the {@code /mcp} and {@code /api/dev} surfaces use, so key
 * lookup, grant re-verification, per-grant rate limit and TenantContext/McpCallerContext
 * population are not duplicated) and tells the controller which agents the grant exposes.
 *
 * <p>Not {@code @Profile("mcpserver")}: the grant service and rate limiter are unconditional beans
 * and this must work whenever the compat surface is enabled. {@code SecurityConfig} admits a
 * {@code vmcp_} bearer on {@code /v1/**} on its prefix alone and does NOT validate it; if this
 * registration were ever absent the controller fails closed (401) because no principal and no
 * grant scope exist.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.openai-compat", name = "enabled", havingValue = "true")
public class OpenAiCompatKeyAuthConfig {

    @Bean
    public FilterRegistrationBean<McpApiKeyAuthFilter> openAiCompatKeyAuthFilterRegistration(
        McpServerAccessGrantService grantService, McpServerRateLimiter rateLimiter
    ) {
        FilterRegistrationBean<McpApiKeyAuthFilter> registration = new FilterRegistrationBean<>(
            new McpApiKeyAuthFilter(grantService, rateLimiter, true, false)
        );
        registration.addUrlPatterns("/v1/*");
        // Same position as the /mcp registration: after Spring Security's chain, which has already
        // authenticated any JWT and admitted (but not validated) a vmcp_ bearer.
        registration.setOrder(100);
        registration.setName("openAiCompatKeyAuthFilter");
        return registration;
    }

    @Bean
    public OpenAiCompatCallerScope openAiCompatCallerScope() {
        return () -> McpCallerContext.current().map(McpCallerContext::exposedAgentIds);
    }
}
