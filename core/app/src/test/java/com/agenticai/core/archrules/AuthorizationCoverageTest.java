package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.SecurityConfig;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.access.intercept.RequestMatcherDelegatingAuthorizationManager;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcherEntry;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * JAVA-006 (short-term guard): every MUTATING endpoint (POST/PUT/PATCH/DELETE, or a mapping with no
 * verb restriction) must be covered either by a specific {@link SecurityConfig} rule (any matcher
 * other than the final {@code anyRequest().authenticated()}, or a whole dedicated filter chain) or
 * by a method/class-level {@code @PreAuthorize}. Falling through to {@code anyRequest()} means "any
 * authenticated member of the tenant" - the exact gap behind the MCP connect/delete, edit/delete
 * and code-intel incidents recorded in SecurityConfig's comments.
 *
 * <p>How: the REAL SecurityConfig filter chains are built in a minimal web context (no controllers,
 * no database), and each controller mapping found on the classpath is turned into a sample request
 * (path variables -> "x") and run through the chain's authorization matchers in order.
 *
 * <p>Endpoints deliberately left on {@code anyRequest().authenticated()} live in {@code
 * security/authorization-coverage-allowlist.txt} (test resources), each with the reason it was
 * reviewed as acceptable: {@code HANDLER} (the controller or service checks access), {@code MEMBER}
 * (meant for any member: the caller's own data, or a metered member feature) or {@code FIX} (a
 * weakness the review found in the handler's own check, still to be fixed). A new uncovered
 * endpoint fails this test until it gets a SecurityConfig rule or {@code @PreAuthorize} (preferred)
 * or a reviewed allowlist line; a line without a category and evidence fails; a stale line (now
 * covered or removed) fails so the list cannot rot; and the number of {@code FIX} lines may only go
 * down. Controllers compiled from test sources are not production routes and are skipped.
 */
class AuthorizationCoverageTest {

    private static final String ALLOWLIST = "security/authorization-coverage-allowlist.txt";

    /**
     * Known weaknesses recorded as {@code FIX} lines (authorization review, 2026-10). Lower this as
     * they are fixed; it must never rise.
     */
    private static final int MAX_FIX_ENTRIES = 2;

    /** {@code VERB /path # CATEGORY: evidence}. */
    private static final Pattern ENTRY =
            Pattern.compile("(POST|PUT|PATCH|DELETE) (/\\S+) +# (HANDLER|MEMBER|FIX): (\\S.*)");

    private static final Set<RequestMethod> MUTATING =
            Set.of(
                    RequestMethod.POST,
                    RequestMethod.PUT,
                    RequestMethod.PATCH,
                    RequestMethod.DELETE);

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

    @Test
    void everyMutatingEndpointHasAnExplicitAuthorizationRule() throws IOException {
        List<Endpoint> endpoints = mutatingEndpoints();
        assertThat(endpoints)
                .as("controller scan found nothing - classpath problem?")
                .hasSizeGreaterThan(100);

        Set<String> uncovered = new TreeSet<>();
        for (Endpoint endpoint : endpoints) {
            if (!endpoint.preAuthorized()
                    && !hasSpecificRule(endpoint.verb(), endpoint.samplePath())) {
                uncovered.add(endpoint.key());
            }
        }

        Set<String> allowlisted = readAllowlist().keySet();
        Set<String> newGaps = new TreeSet<>(uncovered);
        newGaps.removeAll(allowlisted);
        Set<String> stale = new TreeSet<>(allowlisted);
        stale.removeAll(uncovered);

        assertThat(newGaps)
                .as(
                        "Mutating endpoints with no specific SecurityConfig rule and no"
                            + " @PreAuthorize - they are open to ANY authenticated tenant member."
                            + " Add a rule/annotation (preferred) or, after review, a line 'VERB"
                            + " /path  # HANDLER|MEMBER|FIX: evidence' in "
                                + ALLOWLIST)
                .isEmpty();
        assertThat(stale)
                .as(
                        "Allowlist entries that are now covered or no longer exist - delete them"
                                + " from "
                                + ALLOWLIST)
                .isEmpty();
    }

    @Test
    void everyAllowlistLineRecordsItsReview() throws IOException {
        List<String> malformed = new ArrayList<>();
        for (String line : allowlistLines()) {
            if (!ENTRY.matcher(line).matches()) {
                malformed.add(line);
            }
        }
        assertThat(malformed)
                .as(
                        "Each line in %s must read 'VERB /path  # HANDLER|MEMBER|FIX: evidence'",
                        ALLOWLIST)
                .isEmpty();
    }

    @Test
    void knownWeaknessesOnlyGoDown() throws IOException {
        long fixes = readAllowlist().values().stream().filter("FIX"::equals).count();
        assertThat(fixes)
                .as(
                        "FIX lines in %s may only go down: fix the handler, then recategorise or"
                                + " remove its line and lower MAX_FIX_ENTRIES",
                        ALLOWLIST)
                .isLessThanOrEqualTo(MAX_FIX_ENTRIES);
    }

    /** Sanity check of the matcher walk itself against rules known to exist in SecurityConfig. */
    @Test
    void theMatcherWalkSeesKnownRules() {
        assertThat(hasSpecificRule(RequestMethod.POST, "/api/briefs/x/apply")).isTrue();
        assertThat(hasSpecificRule(RequestMethod.GET, "/api/platform/x")).isTrue();
        assertThat(hasSpecificRule(RequestMethod.POST, "/mcp/x"))
                .as("whole dedicated chain")
                .isTrue();
        assertThat(hasSpecificRule(RequestMethod.POST, "/api/zz-no-such-surface/x")).isFalse();
    }

    // ------------------------------------------------------------------

    private record Endpoint(
            RequestMethod verb, String pattern, String samplePath, boolean preAuthorized) {
        String key() {
            return verb + " " + pattern;
        }
    }

    private static boolean hasSpecificRule(RequestMethod verb, String path) {
        MockHttpServletRequest request =
                new MockHttpServletRequest(securityContext.getServletContext(), verb.name(), path);
        request.setServletPath(path);
        for (SecurityFilterChain chain : chains) {
            if (!chain.matches(request)) {
                continue;
            }
            List<RequestMatcherEntry<?>> mappings = authorizationMappings(chain);
            if (mappings.size() == 1
                    && mappings.get(0).getRequestMatcher() instanceof AnyRequestMatcher) {
                // A dedicated chain (webhook/MCP) whose only rule is anyRequest():
                // the chain itself is the explicit decision for this surface.
                return !isMainChain(chain);
            }
            for (RequestMatcherEntry<?> entry : mappings) {
                RequestMatcher matcher = entry.getRequestMatcher();
                if (matcher.matches(request)) {
                    return !(matcher instanceof AnyRequestMatcher);
                }
            }
            return false;
        }
        return false;
    }

    private static boolean isMainChain(SecurityFilterChain chain) {
        return authorizationMappings(chain).size() > 1;
    }

    @SuppressWarnings("unchecked")
    private static List<RequestMatcherEntry<?>> authorizationMappings(SecurityFilterChain chain) {
        AuthorizationFilter filter =
                chain.getFilters().stream()
                        .filter(AuthorizationFilter.class::isInstance)
                        .map(AuthorizationFilter.class::cast)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "chain without AuthorizationFilter"));
        RequestMatcherDelegatingAuthorizationManager manager =
                (RequestMatcherDelegatingAuthorizationManager) filter.getAuthorizationManager();
        Field mappings =
                ReflectionUtils.findField(
                        RequestMatcherDelegatingAuthorizationManager.class, "mappings");
        ReflectionUtils.makeAccessible(mappings);
        return (List<RequestMatcherEntry<?>>) ReflectionUtils.getField(mappings, manager);
    }

    private static List<Endpoint> mutatingEndpoints() {
        InfoExtractor extractor = new InfoExtractor();
        // Every @Controller on the classpath, INCLUDING those behind @Profile or
        // @ConditionalOnProperty: the stock scanner evaluates conditions against its own
        // (empty) environment and would silently drop every knowledge, face, commerce,
        // billing and platform controller - exactly the routes that most need checking.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.core.type.classreading.MetadataReader reader) {
                        return reader.getAnnotationMetadata()
                                        .hasMetaAnnotation(Controller.class.getName())
                                || reader.getAnnotationMetadata()
                                        .hasAnnotation(Controller.class.getName());
                    }
                };
        List<Endpoint> endpoints = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.agenticai")) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException | LinkageError e) {
                throw new IllegalStateException("cannot load " + candidate.getBeanClassName(), e);
            }
            if (compiledFromTestSources(type)) {
                // A test's own stub controller (e.g. ErrorResponseContractTest) is not a route.
                continue;
            }
            boolean classPreAuthorized =
                    AnnotatedElementUtils.hasAnnotation(type, PreAuthorize.class);
            for (Method method :
                    ReflectionUtils.getUniqueDeclaredMethods(
                            type, ReflectionUtils.USER_DECLARED_METHODS)) {
                RequestMappingInfo info = extractor.mappingFor(method, type);
                if (info == null) {
                    continue;
                }
                Set<RequestMethod> verbs = info.getMethodsCondition().getMethods();
                Set<RequestMethod> checked =
                        verbs.isEmpty()
                                ? MUTATING
                                : verbs.stream()
                                        .filter(MUTATING::contains)
                                        .collect(Collectors.toSet());
                boolean preAuthorized =
                        classPreAuthorized
                                || AnnotatedElementUtils.hasAnnotation(method, PreAuthorize.class);
                for (String pattern : info.getPatternValues()) {
                    for (RequestMethod verb : checked) {
                        endpoints.add(
                                new Endpoint(verb, pattern, samplePath(pattern), preAuthorized));
                    }
                }
            }
        }
        return endpoints;
    }

    static String samplePath(String pattern) {
        String path =
                pattern.replaceAll("\\{\\*[^}]*}", "x")
                        .replaceAll("\\{[^}]*}", "x")
                        .replace("**", "x")
                        .replace("*", "x");
        return path.startsWith("/") ? path : "/" + path;
    }

    /** Exposes RequestMappingHandlerMapping's own class+method annotation merge. */
    private static final class InfoExtractor extends RequestMappingHandlerMapping {
        InfoExtractor() {
            GenericWebApplicationContext empty =
                    new GenericWebApplicationContext(new MockServletContext());
            empty.refresh();
            setApplicationContext(empty);
            // Resolve ${property:default} in mapping paths exactly as the running app does (a
            // configurable
            // path such as McpOAuthController's CIMD document); without it the raw placeholder is
            // parsed as a pattern.
            setEmbeddedValueResolver(
                    new org.springframework.beans.factory.config.EmbeddedValueResolver(
                            empty.getBeanFactory()));
            afterPropertiesSet();
        }

        RequestMappingInfo mappingFor(Method method, Class<?> type) {
            return getMappingForMethod(method, type);
        }
    }

    private static boolean compiledFromTestSources(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        return source != null
                && source.getLocation() != null
                && source.getLocation().getPath().contains("/test-classes/");
    }

    /** Allowlist lines without blank or comment-only lines. */
    private static List<String> allowlistLines() throws IOException {
        List<String> lines = new ArrayList<>();
        try (InputStream in =
                AuthorizationCoverageTest.class.getClassLoader().getResourceAsStream(ALLOWLIST)) {
            if (in == null) {
                return lines;
            }
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
        }
        return lines;
    }

    /** Endpoint key ({@code VERB /path}) to its review category ({@code null} if malformed). */
    private static Map<String, String> readAllowlist() throws IOException {
        Map<String, String> entries = new TreeMap<>();
        for (String line : allowlistLines()) {
            Matcher m = ENTRY.matcher(line);
            if (m.matches()) {
                entries.put(m.group(1) + " " + m.group(2), m.group(3));
            } else {
                entries.put(line.replaceFirst("#.*$", "").trim(), null);
            }
        }
        return entries;
    }
}
