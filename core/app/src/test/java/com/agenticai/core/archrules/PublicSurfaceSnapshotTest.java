package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.SecurityConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/**
 * The unauthenticated surface, on its own: every route an ANONYMOUS caller is let through to,
 * across ALL security filter chains. A new {@code permitAll()} (or a new chain that permits
 * everything it matches) therefore fails the build until a reviewer has looked at it and updated
 * {@code security/public-surface-snapshot.txt}.
 *
 * <p>{@link SecurityRuleSnapshotTest} already freezes the main chain's whole rule table (order,
 * matchers, decisions for 21 principals). That file is long and mostly about who may do what once
 * signed in; a one-line change that opens a route to everyone is easy to miss in it, and it does
 * not describe the auxiliary chains (Alertmanager webhook, MCP server) whose rule is "permit
 * everything the chain matches". This test is the short list a security review reads.
 *
 * <p>A permitAll route is not necessarily unauthenticated: webhooks verify a signature, the MCP
 * server checks a grant key, device endpoints check a device token, and so on. The snapshot says
 * what is reachable without a Keycloak token; the controller or filter behind each route owns the
 * rest. Adding a line here is the moment to confirm that it does.
 *
 * <p>A deliberate change: {@code mvn -pl app test -Dtest=PublicSurfaceSnapshotTest
 * -Dpublic.surface.write=true}, review the diff, commit it.
 */
class PublicSurfaceSnapshotTest {

    private static final String SNAPSHOT = "security/public-surface-snapshot.txt";

    @Test
    void theUnauthenticatedSurfaceMatchesTheReviewedSnapshot() throws IOException {
        List<String> actual = describePublicSurface();
        if (Boolean.getBoolean("public.surface.write")) {
            Files.writeString(
                    Path.of("src/test/resources", SNAPSHOT),
                    String.join("\n", actual) + "\n",
                    StandardCharsets.UTF_8);
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
                        "the set of routes an anonymous caller can reach changed. Review each added"
                                + " line (what authenticates it, if not a Keycloak token?). If"
                                + " deliberate, regenerate with -Dpublic.surface.write=true")
                .containsExactlyElementsOf(expected);
    }

    @Test
    void theSnapshotIsNotVacuous() {
        List<String> actual = describePublicSurface();

        assertThat(actual).anyMatch(l -> l.contains("/webhook/**"));
        assertThat(actual).anyMatch(l -> l.startsWith("chain "));
        assertThat(actual).anyMatch(l -> l.startsWith("main "));
    }

    private static List<String> describePublicSurface() {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "public-surface-test",
                                Map.of(
                                        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                                        "http://keycloak.invalid/realms/test",
                                        "app.keycloak.jwk-set-uri",
                                        "http://keycloak.invalid/realms/test/protocol/openid-connect/certs")));
        context.register(SecurityRuleSnapshotTest.MinimalWeb.class, SecurityConfig.class);
        context.refresh();
        try {
            Authentication anonymous =
                    new AnonymousAuthenticationToken(
                            "k", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
            RequestAuthorizationContext ctx =
                    new RequestAuthorizationContext(new MockHttpServletRequest("GET", "/"));
            List<String> lines = new ArrayList<>();
            lines.add("# Routes an anonymous caller reaches. One line per chain / rule, in chain order.");
            lines.add("# Review every change: what authenticates the route if it is not a Keycloak token?");
            List<SecurityFilterChain> chains = new ArrayList<>(context.getBeansOfType(SecurityFilterChain.class).values());
            // Order by the number of main-chain rules last: the catch-all main chain comes after the narrow ones.
            chains.sort(
                    (a, b) ->
                            Integer.compare(
                                    SecurityRuleSnapshotTest.mappings(a).size(),
                                    SecurityRuleSnapshotTest.mappings(b).size()));
            List<String> chainLines = new ArrayList<>();
            List<String> mainLines = new ArrayList<>();
            for (SecurityFilterChain chain : chains) {
                var rules = SecurityRuleSnapshotTest.mappings(chain);
                String matcher =
                        chain instanceof DefaultSecurityFilterChain d
                                ? String.valueOf(d.getRequestMatcher())
                                : chain.getClass().getSimpleName();
                boolean isMain = rules.size() > 5;
                if (!isMain) {
                    boolean permitsAll =
                            rules.stream()
                                    .allMatch(
                                            r -> {
                                                AuthorizationResult result =
                                                        r.getEntry().authorize(() -> anonymous, ctx);
                                                return result != null && result.isGranted();
                                            });
                    chainLines.add(
                            "chain " + matcher + " => " + (permitsAll ? "anonymous: everything it matches" : "restricted"));
                    continue;
                }
                for (var rule : rules) {
                    AuthorizationResult result = rule.getEntry().authorize(() -> anonymous, ctx);
                    if (result != null && result.isGranted()) {
                        mainLines.add("main " + rule.getRequestMatcher());
                    }
                }
            }
            java.util.Collections.sort(chainLines); // bean order between equal-size chains is not stable
            lines.addAll(chainLines);
            lines.addAll(mainLines);
            return lines;
        } finally {
            context.close();
        }
    }
}
