package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link BrowserNavigateToolCallback}'s failure contract (never throws, every failure
 * is a relayable {@code {"error": "..."}}) and for the tenant-resolution gate {@link
 * BrowserUseToolSupport#resolveTenantId} adds in front of every {@code browser_*} tool.
 */
class BrowserNavigateToolCallbackTest {

    private BrowserUseSessionManager sessionManager;
    private UUID agentId;

    @BeforeEach
    void setUp() {
        sessionManager = mock(BrowserUseSessionManager.class);
        agentId = UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void returnsAClearErrorWhenNoTenantContextIsSet() {
        BrowserNavigateToolCallback callback =
                new BrowserNavigateToolCallback(sessionManager, agentId, "session-1");

        String result = callback.call("{\"url\": \"https://example.com\"}");

        assertThat(result).contains("error").contains("tenant");
    }

    @Test
    void returnsAClearErrorWhenUrlIsMissing() {
        TenantContext.set(TenantContext.of("tenant-1", "user-1", Set.of()));
        BrowserNavigateToolCallback callback =
                new BrowserNavigateToolCallback(sessionManager, agentId, "session-1");

        String result = callback.call("{}");

        assertThat(result).contains("error").contains("url");
    }

    @Test
    void navigatesAndReturnsUrlAndTitleOnSuccess() {
        TenantContext.set(TenantContext.of("tenant-1", "user-1", Set.of()));
        when(sessionManager.navigate(eq("tenant-1"), any(), anyString(), anyString()))
                .thenReturn(
                        new BrowserUseSessionManager.NavigateResult(
                                "https://example.com/", "Example Domain"));
        BrowserNavigateToolCallback callback =
                new BrowserNavigateToolCallback(sessionManager, agentId, "session-1");

        String result = callback.call("{\"url\": \"https://example.com\"}");

        assertThat(result)
                .contains("Example Domain")
                .contains("https://example.com/")
                .doesNotContain("error");
        verify(sessionManager).navigate("tenant-1", agentId, "session-1", "https://example.com");
    }

    @Test
    void relaysABrowserUseExceptionAsAShortErrorMessage() {
        TenantContext.set(TenantContext.of("tenant-1", "user-1", Set.of()));
        when(sessionManager.navigate(anyString(), any(), anyString(), anyString()))
                .thenThrow(
                        new BrowserUseException(
                                "the URL points inside the platform's own network"));
        BrowserNavigateToolCallback callback =
                new BrowserNavigateToolCallback(sessionManager, agentId, "session-1");

        String result = callback.call("{\"url\": \"http://169.254.169.254/\"}");

        assertThat(result).contains("error").contains("platform's own network");
    }

    @Test
    void neverThrowsOnMalformedJsonInput() {
        TenantContext.set(TenantContext.of("tenant-1", "user-1", Set.of()));
        BrowserNavigateToolCallback callback =
                new BrowserNavigateToolCallback(sessionManager, agentId, "session-1");

        String result = callback.call("{not json");

        assertThat(result).contains("error");
    }
}
