package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.SecurityConfig;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager;
import org.springframework.security.web.util.matcher.RequestMatcherEntry;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Golden snapshot of the main security chain's authorization rules, in order: each rule's matcher
 * (patterns and method) and its decision for a fixed set of principals. Any change to rule order, a
 * pattern, a method or an authority changes this snapshot, so a refactor of SecurityConfig (such as
 * moving rules into other classes) must leave it byte-identical.
 *
 * <p>A deliberate rule change updates the snapshot as part of the reviewed change: {@code mvn -pl
 * app test -Dtest=SecurityRuleSnapshotTest -Dauthz.snapshot.write=true}.
 */
class SecurityRuleSnapshotTest {

    private static final String SNAPSHOT = "security/authorization-rule-snapshot.txt";

    private static final List<String> PERMISSIONS =
            List.of(
                    "MANAGE_USERS",
                    "MANAGE_BILLING",
                    "MANAGE_INTEGRATIONS",
                    "MANAGE_TENANT_SETTINGS",
                    "MANAGE_WORKFLOWS_ADMIN",
                    "VIEW_AUDIT_LOG",
                    "MANAGE_APPROVALS",
                    "MANAGE_DATA_GOVERNANCE",
                    "MANAGE_CHANNELS",
                    "MANAGE_AGENTS",
                    "MANAGE_GUARDRAILS",
                    "MANAGE_TOOLS",
                    "MANAGE_TEMPLATES",
                    "VIEW_USAGE",
                    "MANAGE_LEADS");

    @EnableWebMvc
    @Configuration(proxyBeanMethods = false)
    static class MinimalWeb {}

    @Test
    void mainChainRulesMatchTheSnapshot() throws IOException {
        List<String> actual = describeMainChain();
        if (Boolean.getBoolean("authz.snapshot.write")) {
            Path out = Path.of("src/test/resources", SNAPSHOT);
            Files.writeString(out, String.join("\n", actual) + "\n", StandardCharsets.UTF_8);
        }
        List<String> expected;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(SNAPSHOT)) {
            assertThat(in).as(SNAPSHOT + " on the test classpath").isNotNull();
            expected =
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)
                            .lines()
                            .filter(line -> !line.isBlank())
                            .toList();
        }
        assertThat(actual)
                .as(
                        "main-chain authorization rules (order, matcher, decisions) changed. If"
                                + " deliberate, regenerate with -Dauthz.snapshot.write=true")
                .containsExactlyElementsOf(expected);
    }

    private static List<String> describeMainChain() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "authz-test",
                                Map.of(
                                        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                                        "http://keycloak.invalid/realms/test",
                                        "app.keycloak.jwk-set-uri",
                                        "http://keycloak.invalid/realms/test/protocol/openid-connect/certs")));
        context.register(MinimalWeb.class, SecurityConfig.class);
        context.refresh();
        try {
            List<RequestMatcherEntry<AuthorizationManager<RequestAuthorizationContext>>> rules =
                    context.getBeansOfType(SecurityFilterChain.class).values().stream()
                            .map(SecurityRuleSnapshotTest::mappings)
                            .max((a, b) -> Integer.compare(a.size(), b.size()))
                            .orElseThrow();
            List<String> principals = principalNames();
            List<Supplier<Authentication>> users = principals();
            List<String> lines = new ArrayList<>();
            lines.add("# principals: " + String.join(", ", principals));
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
            RequestAuthorizationContext ctx = new RequestAuthorizationContext(request);
            for (RequestMatcherEntry<AuthorizationManager<RequestAuthorizationContext>> rule :
                    rules) {
                StringBuilder decisions = new StringBuilder();
                for (Supplier<Authentication> user : users) {
                    AuthorizationResult result = rule.getEntry().authorize(user, ctx);
                    decisions.append(result != null && result.isGranted() ? 'Y' : '-');
                }
                lines.add(rule.getRequestMatcher() + " => " + decisions);
            }
            return lines;
        } finally {
            context.close();
        }
    }

    private static List<String> principalNames() {
        List<String> names = new ArrayList<>();
        names.add("anonymous");
        names.add("member");
        names.add("end-customer");
        PERMISSIONS.forEach(p -> names.add("PERM_" + p));
        names.add("AGENTS+WORKFLOWS_ADMIN");
        names.add("tenant-admin");
        names.add("platform-admin");
        return names;
    }

    private static List<Supplier<Authentication>> principals() {
        List<Supplier<Authentication>> users = new ArrayList<>();
        users.add(
                () ->
                        new AnonymousAuthenticationToken(
                                "k",
                                "anonymous",
                                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        users.add(user("ROLE_agent-builder"));
        users.add(user("ROLE_end-customer"));
        PERMISSIONS.forEach(p -> users.add(user("PERM_" + p)));
        users.add(user("PERM_MANAGE_AGENTS", "PERM_MANAGE_WORKFLOWS_ADMIN"));
        List<String> admin = new ArrayList<>(List.of("ROLE_tenant-admin"));
        PERMISSIONS.forEach(p -> admin.add("PERM_" + p));
        users.add(user(admin.toArray(String[]::new)));
        users.add(user("ROLE_platform-admin"));
        return users;
    }

    private static Supplier<Authentication> user(String... authorities) {
        return () -> new TestingAuthenticationToken("user", "n/a", authorities);
    }

    @SuppressWarnings("unchecked")
    static List<RequestMatcherEntry<AuthorizationManager<RequestAuthorizationContext>>>
            mappings(SecurityFilterChain chain) {
        AuthorizationFilter filter =
                chain.getFilters().stream()
                        .filter(AuthorizationFilter.class::isInstance)
                        .map(AuthorizationFilter.class::cast)
                        .findFirst()
                        .orElseThrow();
        Field field =
                ReflectionUtils.findField(
                        RequestMatcherDelegatingAuthorizationManager.class, "mappings");
        ReflectionUtils.makeAccessible(field);
        return (List<RequestMatcherEntry<AuthorizationManager<RequestAuthorizationContext>>>)
                ReflectionUtils.getField(field, filter.getAuthorizationManager());
    }
}
