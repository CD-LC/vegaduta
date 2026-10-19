package com.agenticai.core.providers.localmodels;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The role rule really runs: the controller is proxied by Spring's method security (as in core) and
 * called as different callers. The MockMvc contract tests bypass that proxy, so without this a typo
 * in the {@code @PreAuthorize} expression would pass them all.
 */
class LocalModelsAuthorizationTest {

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean
        LocalModelsService service() {
            LocalModelsService service = mock(LocalModelsService.class);
            when(service.startPull(anyString())).thenReturn("job-1");
            return service;
        }

        @Bean
        LocalModelRateLimiter limiter() {
            return mock(LocalModelRateLimiter.class);
        }

        @Bean
        LocalModelsController controller(LocalModelsService service, LocalModelRateLimiter limiter) {
            return new LocalModelsController(service, limiter);
        }
    }

    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Config.class);
    private final LocalModelsController controller = context.getBean(LocalModelsController.class);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        context.close();
    }

    private static Authentication as(String... authorities) {
        TestingAuthenticationToken token = new TestingAuthenticationToken("user-1", "n/a", authorities);
        token.setAuthenticated(true);
        return token;
    }

    private void signIn(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    void tenantAdminAndPlatformAdminMayCallEveryEndpoint() {
        for (String role : List.of("ROLE_tenant-admin", "ROLE_platform-admin")) {
            Authentication caller = as(role);
            signIn(caller);
            assertDoesNotThrow(() -> controller.recommendation(caller), role);
            assertDoesNotThrow(() -> controller.probe(new LocalModelsController.TagRequest("qwen3.5:4b"), caller), role);
            assertEquals(202, controller.pull(new LocalModelsController.TagRequest("qwen3.5:4b"), caller)
                    .getStatusCode().value(), role);
        }
    }

    @Test
    void everyOtherRoleIsRefusedBeforeAnythingRuns() {
        LocalModelsService service = context.getBean(LocalModelsService.class);
        for (String role : List.of("ROLE_agent-builder", "ROLE_end-customer", "ROLE_delegated-user", "ROLE_user")) {
            Authentication caller = as(role);
            signIn(caller);
            assertThrows(AccessDeniedException.class, () -> controller.recommendation(caller), role);
            assertThrows(AccessDeniedException.class,
                    () -> controller.probe(new LocalModelsController.TagRequest("qwen3.5:4b"), caller), role);
            assertThrows(AccessDeniedException.class,
                    () -> controller.pull(new LocalModelsController.TagRequest("qwen3.5:4b"), caller), role);
            assertThrows(AccessDeniedException.class, () -> controller.pullStatus("job-1", caller), role);
        }
        verify(service, never()).startPull(anyString());
        verify(service, never()).recommendation();
    }

    @Test
    void aCallerWithoutAnyRoleIsRefused() {
        Authentication caller = as();
        signIn(caller);
        assertThrows(AccessDeniedException.class, () -> controller.recommendation(caller));
        // AuthorizationDeniedException is the 6.x subtype; the assertion above already covers it.
        assertThrows(AuthorizationDeniedException.class, () -> controller.pullStatus("x", caller));
    }
}
