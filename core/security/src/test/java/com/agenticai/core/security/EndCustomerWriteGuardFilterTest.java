package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class EndCustomerWriteGuardFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static boolean passes(String method, String path, String... authorities)
            throws Exception {
        if (authorities.length > 0) {
            TestingAuthenticationToken user =
                    new TestingAuthenticationToken("u", "n/a", authorities);
            SecurityContextHolder.getContext().setAuthentication(user);
        }
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockFilterChain chain = new MockFilterChain();
        try {
            new EndCustomerWriteGuardFilter()
                    .doFilter(request, new MockHttpServletResponse(), chain);
        } catch (AccessDeniedException denied) {
            assertThat(chain.getRequest()).as("denied request must not reach the chain").isNull();
            return false;
        }
        return chain.getRequest() != null;
    }

    @ParameterizedTest(name = "end-customer {0} {1} is denied")
    @CsvSource({
        "POST, /api/agents",
        "PATCH, /api/agents/a1",
        "DELETE, /api/agents/a1",
        "POST, /api/workflows",
        "POST, /api/agents/a1/jobs",
        "POST, /api/quick-task",
        "POST, /api/approvals/r1/approve",
        "POST, /api/videogen/generate",
        "POST, /api/voice/agents/a1/speak",
        "PUT, /api/channels/email/default-agent"
    })
    void endCustomerConsoleWritesAreDenied(String method, String path) throws Exception {
        assertThat(passes(method, path, "ROLE_end-customer")).isFalse();
    }

    @ParameterizedTest(name = "end-customer {0} {1} is allowed")
    @CsvSource({
        "POST, /api/agents/a1/chat",
        "POST, /api/agents/a1/chat/attachments",
        "POST, /api/agents/a1/chat/sessions/s1/cancel",
        "POST, /api/agents/a1/agui/run",
        "POST, /api/agents/a1/turns/feedback",
        "POST, /api/voice/agents/a1/token",
        "POST, /api/voice/agents/a1/turn",
        "POST, /api/account/avatar",
        "PUT, /api/account/phone",
        "POST, /api/notifications/read-all",
        "POST, /api/notifications/n1/read",
        "DELETE, /api/channels/google/personal/connection"
    })
    void endCustomerSelfServiceIsAllowed(String method, String path) throws Exception {
        assertThat(passes(method, path, "ROLE_end-customer")).isTrue();
    }

    @ParameterizedTest(name = "{0} {1} passes through")
    @CsvSource({
        "GET, /api/agents", // reads are not this filter's concern
        "POST, /mcp/x", // only /api/** writes
        "POST, /v1/chat/completions"
    })
    void readsAndNonApiPathsPassThrough(String method, String path) throws Exception {
        assertThat(passes(method, path, "ROLE_end-customer")).isTrue();
    }

    @ParameterizedTest(name = "end-customer plus {0} keeps console writes")
    @CsvSource({
        "ROLE_tenant-admin",
        "ROLE_agent-builder",
        "ROLE_delegated-user",
        "ROLE_platform-admin"
    })
    void anotherConsoleRoleLiftsTheGuard(String role) throws Exception {
        assertThat(passes("POST", "/api/agents", "ROLE_end-customer", role)).isTrue();
    }

    @ParameterizedTest(name = "{0} is not affected")
    @CsvSource({"ROLE_agent-builder", "ROLE_delegated-user"})
    void otherRolesAreNotAffected(String role) throws Exception {
        assertThat(passes("POST", "/api/agents", role)).isTrue();
    }

    @Test
    void unauthenticatedRequestsAreLeftToTheRestOfTheChain() throws Exception {
        assertThat(passes("POST", "/api/agents")).isTrue();
    }

    @Test
    void deniedRequestThrowsAccessDeniedForTheStandard403() {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("u", "n/a", "ROLE_end-customer"));
        assertThatThrownBy(
                        () ->
                                new EndCustomerWriteGuardFilter()
                                        .doFilter(
                                                new MockHttpServletRequest("POST", "/api/agents"),
                                                new MockHttpServletResponse(),
                                                new MockFilterChain()))
                .isInstanceOf(AccessDeniedException.class);
    }
}
