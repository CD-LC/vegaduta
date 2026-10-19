package com.agenticai.core.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/** Wiring of the OAuth option: off by default, validated when on, and a bad config fails startup. */
class McpOAuthConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(McpOAuthConfig.class)
        .withPropertyValues("spring.profiles.active=mcpserver");

    @Test
    void offByDefaultRegistersNothing() {
        runner.withInitializer(c -> c.getEnvironment().addActiveProfile("mcpserver")).run(ctx -> {
            assertThat(ctx).doesNotHaveBean(McpOAuthAuthenticator.class);
            assertThat(ctx).doesNotHaveBean(FilterRegistrationBean.class);
        });
    }

    @Test
    void validConfigRegistersTheAuthenticatorAndTheMetadataEndpoints() {
        runner.withInitializer(c -> c.getEnvironment().addActiveProfile("mcpserver"))
            .withPropertyValues(
                "app.mcpserver.oauth.enabled=true",
                "app.mcpserver.oauth.resource=https://mcp.example.com/mcp",
                "app.mcpserver.oauth.issuer=https://auth.example.com/realms/vega",
                "app.mcpserver.oauth.jwk-set-uri=https://auth.example.com/realms/vega/protocol/openid-connect/certs")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(McpOAuthAuthenticator.class);
                @SuppressWarnings("unchecked")
                FilterRegistrationBean<McpProtectedResourceMetadataFilter> reg =
                    ctx.getBean("mcpProtectedResourceMetadataRegistration", FilterRegistrationBean.class);
                assertThat(reg.getUrlPatterns()).contains("/.well-known/oauth-protected-resource/*");
                assertThat(reg.getFilter().authorizationServers()).containsExactly("https://auth.example.com/realms/vega");
            });
    }

    @Test
    void plainHttpOrMissingValuesFailStartupInsteadOfLeavingAHalfSecuredEndpoint() {
        runner.withInitializer(c -> c.getEnvironment().addActiveProfile("mcpserver"))
            .withPropertyValues("app.mcpserver.oauth.enabled=true",
                "app.mcpserver.oauth.resource=http://mcp.example.com/mcp",
                "app.mcpserver.oauth.issuer=https://auth.example.com",
                "app.mcpserver.oauth.jwk-set-uri=https://auth.example.com/certs")
            .run(ctx -> assertThat(ctx).hasFailed());
        runner.withInitializer(c -> c.getEnvironment().addActiveProfile("mcpserver"))
            .withPropertyValues("app.mcpserver.oauth.enabled=true")
            .run(ctx -> assertThat(ctx).hasFailed());
    }
}
