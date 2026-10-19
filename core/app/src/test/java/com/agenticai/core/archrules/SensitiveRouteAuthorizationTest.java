package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.EndCustomerWriteGuardFilter;
import com.agenticai.core.security.SecurityConfig;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Decisions, not just coverage: for each sensitive route, the REAL SecurityConfig chain must deny a
 * member who lacks the required authority (even one holding every other builder permission, or the
 * widget end-customer role) and grant one who holds it. {@link AuthorizationCoverageTest} only
 * proves a route has SOME specific rule; this proves it is the right one. Add a row when a route is
 * gated.
 */
class SensitiveRouteAuthorizationTest {

    /**
     * Everything agent-builder holds by default plus the channel and guardrail permissions: a
     * powerful member. Each case removes the authority under test, so "denied" proves that exact
     * authority is required.
     */
    private static final String[] POWERFUL_MEMBER = {
        "ROLE_agent-builder",
        "PERM_MANAGE_AGENTS",
        "PERM_MANAGE_WORKFLOWS_ADMIN",
        "PERM_MANAGE_INTEGRATIONS",
        "PERM_MANAGE_TOOLS",
        "PERM_MANAGE_CHANNELS",
        "PERM_MANAGE_GUARDRAILS"
    };

    private static final String[] END_CUSTOMER = {"ROLE_end-customer"};
    private static final String[] DELEGATED_USER = {"ROLE_delegated-user"};

    private static AnnotationConfigWebApplicationContext securityContext;
    private static List<SecurityFilterChain> chains;

    @EnableWebMvc
    @Configuration(proxyBeanMethods = false)
    static class MinimalWeb {}

    @BeforeAll
    static void buildSecurityChains() {
        securityContext = new AnnotationConfigWebApplicationContext();
        securityContext.setServletContext(new MockServletContext());
        securityContext
                .getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "authz-test",
                                Map.of(
                                        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                                                "http://keycloak.invalid/realms/test",
                                        "app.keycloak.jwk-set-uri",
                                                "http://keycloak.invalid/realms/test/protocol/openid-connect/certs")));
        securityContext.register(MinimalWeb.class, SecurityConfig.class);
        securityContext.refresh();
        chains =
                new ArrayList<>(securityContext.getBeansOfType(SecurityFilterChain.class).values());
    }

    @AfterAll
    static void close() {
        if (securityContext != null) {
            securityContext.close();
        }
    }

    /** method, sample path (path variables as "x"), the authority that must be required. */
    static Stream<Arguments> gatedRoutes() {
        return Stream.of(
                Arguments.of("POST", "/api/payments/mandates", "PERM_MANAGE_BILLING"),
                Arguments.of("POST", "/api/payments/mandates/x/revoke", "PERM_MANAGE_BILLING"),
                Arguments.of("POST", "/api/devices/x/commands", "PERM_MANAGE_CHANNELS"),
                Arguments.of("DELETE", "/api/devices/x", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/studio-apps/publish", "PERM_MANAGE_AGENTS"),
                Arguments.of("DELETE", "/api/studio-apps/x", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/studio-apps/x/disable", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/studio-apps/x/enable", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/studio-apps/x/domains", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/studio-apps/x/domains/y/verify", "PERM_MANAGE_AGENTS"),
                Arguments.of("DELETE", "/api/studio-apps/x/domains/y", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/studio-apps/x/users/y/disable", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/studio-apps/x/users/y/enable", "PERM_MANAGE_AGENTS"),
                Arguments.of("DELETE", "/api/studio-apps/x/users/y", "PERM_MANAGE_AGENTS"),
                Arguments.of(
                        "POST", "/api/agents/x/improvements/y/accept", "PERM_MANAGE_GUARDRAILS"),
                Arguments.of("PUT", "/api/channels/email/default-agent", "PERM_MANAGE_CHANNELS"),
                Arguments.of("PUT", "/api/channels/telegram/default-agent", "PERM_MANAGE_CHANNELS"),
                Arguments.of("PUT", "/api/channels/signal/default-agent", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/signal/credential", "PERM_MANAGE_CHANNELS"),
                Arguments.of("GET", "/api/channels/signal/credential", "PERM_MANAGE_CHANNELS"),
                Arguments.of("DELETE", "/api/channels/signal/credential", "PERM_MANAGE_CHANNELS"),
                Arguments.of("PUT", "/api/channels/signal/credential/policy", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/whatsapp-qr/session", "PERM_MANAGE_CHANNELS"),
                Arguments.of("GET", "/api/channels/whatsapp-qr/session", "PERM_MANAGE_CHANNELS"),
                Arguments.of("DELETE", "/api/channels/whatsapp-qr/session", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/whatsapp-qr/send", "PERM_MANAGE_CHANNELS"),
                Arguments.of(
                        "PUT", "/api/channels/whatsapp-qr/default-agent", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/matrix/credential", "PERM_MANAGE_CHANNELS"),
                Arguments.of("PUT", "/api/channels/matrix/policy", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/matrix/send", "PERM_MANAGE_CHANNELS"),
                Arguments.of("PUT", "/api/channels/matrix/default-agent", "PERM_MANAGE_CHANNELS"),
                Arguments.of("PUT", "/api/channels/voice/numbers/x", "PERM_MANAGE_CHANNELS"),
                Arguments.of("DELETE", "/api/channels/voice/numbers/x", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/pairing/x/unpair", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/devices/pairing-code", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/devices/register", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/tools/scripts", "PERM_MANAGE_TOOLS"),
                Arguments.of("POST", "/api/tools/scripts/test", "PERM_MANAGE_TOOLS"),
                Arguments.of("PUT", "/api/tools/scripts/x", "PERM_MANAGE_TOOLS"),
                Arguments.of("PATCH", "/api/tools/scripts/x/enabled", "PERM_MANAGE_TOOLS"),
                Arguments.of("DELETE", "/api/tools/scripts/x", "PERM_MANAGE_TOOLS"),
                Arguments.of("POST", "/api/workflows", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/workflows/x/duplicate", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/workflows/x/generate-graph", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/channels/discord/send", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/email/send", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/slack/send", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/signal/send", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/social/instagram/send", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/social/messenger/send", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/teams/send", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/imessage/send", "PERM_MANAGE_CHANNELS"),
                Arguments.of("GET", "/api/channels/imessage/credential", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/imessage/credential", "PERM_MANAGE_CHANNELS"),
                Arguments.of("DELETE", "/api/channels/imessage/credential", "PERM_MANAGE_CHANNELS"),
                Arguments.of(
                        "PUT", "/api/channels/imessage/credential/settings", "PERM_MANAGE_CHANNELS"),
                Arguments.of("PUT", "/api/channels/imessage/default-agent", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/telegram/send", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/whatsapp/send", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/channels/x/send", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/handoff/sessions/x/escalate", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/handoff/sessions/x/release", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/handoff/sessions/x/reply", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/handoff/sessions/x/take-over", "PERM_MANAGE_CHANNELS"),
                Arguments.of("POST", "/api/moderation/review/x/approve", "PERM_MANAGE_GUARDRAILS"),
                Arguments.of("POST", "/api/moderation/review/x/block", "PERM_MANAGE_GUARDRAILS"),
                Arguments.of("POST", "/api/moderation/review/x/escalate", "PERM_MANAGE_GUARDRAILS"),
                Arguments.of("POST", "/api/agents/x/duplicate", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/templates/x/import", "PERM_MANAGE_AGENTS"),
                Arguments.of(
                        "POST", "/api/agents/x/quality/simulations/personas", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/skills", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/skills/import", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/skills/registry/install", "PERM_MANAGE_AGENTS"),
                Arguments.of("PUT", "/api/skills/x", "PERM_MANAGE_AGENTS"),
                Arguments.of("DELETE", "/api/skills/x", "PERM_MANAGE_AGENTS"),
                Arguments.of("PATCH", "/api/skills/x/enabled", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/skills/x/improvement/apply", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/skills/x/approve", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/skills/x/reject", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/skills/x/archive", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/skills/x/agents", "PERM_MANAGE_AGENTS"),
                Arguments.of("DELETE", "/api/skills/x/improvement", "PERM_MANAGE_AGENTS"),
                Arguments.of("POST", "/api/agents/x/skills/distill", "PERM_MANAGE_AGENTS"),
                Arguments.of("PATCH", "/api/agents/x/guardrails", "PERM_MANAGE_GUARDRAILS"),
                Arguments.of("POST", "/api/templates", "PERM_MANAGE_TEMPLATES"),
                Arguments.of("POST", "/api/templates/x/submit", "PERM_MANAGE_TEMPLATES"),
                Arguments.of("POST", "/api/elevenlabs/voices", "PERM_MANAGE_CHANNELS"),
                Arguments.of("DELETE", "/api/elevenlabs/voices/x", "PERM_MANAGE_CHANNELS"),
                Arguments.of(
                        "POST",
                        "/api/capabilities/decision/playground",
                        "PERM_MANAGE_INTEGRATIONS"),
                Arguments.of("POST", "/api/jev/playground", "PERM_MANAGE_INTEGRATIONS"),
                Arguments.of(
                        "POST", "/api/providers/local-openai/probe", "PERM_MANAGE_INTEGRATIONS"),
                Arguments.of("PATCH", "/api/agents/x/code-intel-repo-access", "ROLE_agent-builder"),
                Arguments.of(
                        "PATCH",
                        "/api/agents/x/capabilities/code-workspace",
                        "ROLE_agent-builder"));
    }

    /**
     * Agent creation is the delegated-user role's job (it sets agents up for someone else), so
     * these take PERM_MANAGE_AGENTS or that role; everyone else still needs the permission.
     */
    static Stream<Arguments> agentCreates() {
        return Stream.of(
                Arguments.of("POST", "/api/agents"),
                Arguments.of("POST", "/api/agents/generate"),
                Arguments.of("POST", "/api/assist/draft"),
                Arguments.of("POST", "/api/agent-bundles/import"),
                Arguments.of("POST", "/api/agent-bundles/preview"));
    }

    @ParameterizedTest(name = "{0} {1}: builders and delegated users only")
    @MethodSource("agentCreates")
    void agentCreateAllowsBuildersAndDelegatedUsers(String method, String path) {
        String[] withoutAgents =
                Arrays.stream(POWERFUL_MEMBER)
                        .filter(a -> !a.equals("PERM_MANAGE_AGENTS"))
                        .toArray(String[]::new);
        assertThat(granted(method, path, withoutAgents)).as("member without agents").isFalse();
        assertThat(granted(method, path, END_CUSTOMER)).as("end-customer").isFalse();
        assertThat(granted(method, path, DELEGATED_USER)).as("delegated-user").isTrue();
        assertThat(granted(method, path, "PERM_MANAGE_AGENTS")).as("builder").isTrue();
    }

    /** Rules that record a controller's own check keep its platform-admin access. */
    static Stream<Arguments> platformAdminRoutes() {
        return Stream.of(
                Arguments.of("POST", "/api/skills"),
                Arguments.of("PUT", "/api/skills/x"),
                Arguments.of("DELETE", "/api/skills/x"),
                Arguments.of("POST", "/api/handoff/sessions/x/reply"),
                Arguments.of("POST", "/api/moderation/review/x/approve"));
    }

    @ParameterizedTest(name = "{0} {1} keeps platform-admin")
    @MethodSource("platformAdminRoutes")
    void platformAdminKeepsHandlerAccess(String method, String path) {
        assertThat(granted(method, path, "ROLE_platform-admin")).isTrue();
    }

    /** Reads under a newly gated prefix must stay open to members. */
    static Stream<Arguments> openReads() {
        return Stream.of(
                Arguments.of("GET", "/api/studio-apps"),
                Arguments.of("GET", "/api/studio-apps/x"),
                Arguments.of("GET", "/api/studio-apps/x/users"),
                Arguments.of("GET", "/api/studio-apps/x/domains"),
                Arguments.of("GET", "/api/tools/scripts"),
                Arguments.of("GET", "/api/skills"),
                Arguments.of("GET", "/api/workflows"),
                Arguments.of("GET", "/api/agents/x/guardrails"),
                Arguments.of("GET", "/api/elevenlabs/voices"));
    }

    @ParameterizedTest(name = "{0} {1} stays open to members")
    @MethodSource("openReads")
    void readStaysOpen(String method, String path) {
        assertThat(granted(method, path, "ROLE_agent-builder")).isTrue();
    }

    @ParameterizedTest(name = "{0} {1} requires {2}")
    @MethodSource("gatedRoutes")
    void routeRequiresItsAuthority(String method, String path, String required) {
        String[] withoutRequired =
                Arrays.stream(POWERFUL_MEMBER)
                        .filter(a -> !a.equals(required))
                        .toArray(String[]::new);
        assertThat(granted(method, path, withoutRequired))
                .as("powerful member without %s", required)
                .isFalse();
        assertThat(granted(method, path, END_CUSTOMER)).as("end-customer").isFalse();
        assertThat(granted(method, path, DELEGATED_USER)).as("delegated-user").isFalse();
        assertThat(granted(method, path, required)).as("holder of %s", required).isTrue();
    }

    /**
     * The end-customer guard is a filter, so the matcher decisions above cannot see it: check it is
     * wired into the main chain right before the AuthorizationFilter (after exception translation,
     * so a denial is the chain's standard 403).
     */
    @Test
    void endCustomerGuardRunsJustBeforeAuthorizationInTheMainChain() {
        MockHttpServletRequest api =
                new MockHttpServletRequest(
                        securityContext.getServletContext(), "POST", "/api/agents");
        api.setServletPath("/api/agents");
        SecurityFilterChain main =
                chains.stream()
                        .filter(chain -> chain.matches(api))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no chain for /api/agents"));
        List<Class<?>> filters =
                main.getFilters().stream().<Class<?>>map(Object::getClass).toList();
        int guard = filters.indexOf(EndCustomerWriteGuardFilter.class);
        assertThat(guard).as("EndCustomerWriteGuardFilter in the main chain").isNotNegative();
        assertThat(filters.get(guard + 1)).isEqualTo(AuthorizationFilter.class);
    }

    private static boolean granted(String method, String path, String... authorities) {
        MockHttpServletRequest request =
                new MockHttpServletRequest(securityContext.getServletContext(), method, path);
        request.setServletPath(path);
        Authentication user = new TestingAuthenticationToken("user", "n/a", authorities);
        for (SecurityFilterChain chain : chains) {
            if (chain.matches(request)) {
                return managerOf(chain).authorize(() -> user, request).isGranted();
            }
        }
        throw new AssertionError("no security chain matches " + method + " " + path);
    }

    @SuppressWarnings("unchecked")
    private static AuthorizationManager<HttpServletRequest> managerOf(SecurityFilterChain chain) {
        AuthorizationFilter filter =
                chain.getFilters().stream()
                        .filter(AuthorizationFilter.class::isInstance)
                        .map(AuthorizationFilter.class::cast)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "chain without AuthorizationFilter"));
        return (AuthorizationManager<HttpServletRequest>) filter.getAuthorizationManager();
    }
}
