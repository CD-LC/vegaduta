package com.agenticai.core.archrules;

import static com.tngtech.archunit.lang.SimpleConditionEvent.violated;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.archrules.fixtures.RawHttpClientFixtures;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Outbound HTTP is built in ONE place, {@code com.agenticai.core.security.net} ({@code
 * SafeHttpClients}, whose connections resolve through {@code SsrfGuard}). A client built anywhere
 * else has the JVM's default resolver and proxy behaviour, so a tenant-, LLM- or internet-supplied
 * URL reaches loopback, RFC 1918 and cloud-metadata addresses and a DNS rebinding answer reaches a
 * socket.
 *
 * <p>The forms this rule recognises are the raw ways to obtain a client: {@code
 * java.net.http.HttpClient.newHttpClient/newBuilder}, {@code new RestTemplate(..)}, {@code
 * WebClient.create/builder}, OkHttp's {@code OkHttpClient}, and Apache's {@code HttpClients.*} /
 * {@code HttpClientBuilder.create}.
 *
 * <p>Like the rules in {@link StructureRulesTest} this is a {@link FreezingArchRule}: the sites
 * that existed when the rule was introduced are baselined in {@code
 * src/test/resources/archunit_store}, a NEW raw client fails the build, and a site moved onto
 * {@code SafeHttpClients} drops out of the baseline (it may only shrink). Maintenance steps are in
 * {@link ModuleBoundaryRulesTest}.
 *
 * <p>Many baselined sites call a fixed, operator-configured, trusted host (a SaaS API, the sidecar,
 * Keycloak) and need private-network reachability or the JDK client's features; those are
 * tolerated, not endorsed. Anything that fetches a URL a tenant or a model chose must use {@code
 * SafeHttpClients}.
 */
class RawHttpClientBanRulesTest {

    private static final String ROOT = "com.agenticai.core";
    static final String FACTORY_PACKAGE = "com.agenticai.core.security.net";

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages(ROOT);
    }

    /**
     * Releases the imported class graphs. Surefire runs all of this module's tests in one JVM, so
     * the statics of a finished test class would otherwise stay on the heap for the rest of the
     * run, and the other ArchUnit tests import the classpath again.
     */
    @AfterAll
    static void releaseImports() {
        classes = null;
    }

    /** What raw form a call is, or null when it is allowed. */
    static String rawForm(JavaCall<?> call) {
        String owner = call.getTargetOwner().getName();
        String method = call.getName();
        boolean ctor = method.equals("<init>");
        switch (owner) {
            case "java.net.http.HttpClient":
                return method.equals("newHttpClient") || method.equals("newBuilder")
                        ? "HttpClient." + method
                        : null;
            case "org.springframework.web.client.RestTemplate":
                return ctor ? "new RestTemplate" : null;
            case "org.springframework.web.reactive.function.client.WebClient":
                return method.equals("create") || method.equals("builder")
                        ? "WebClient." + method
                        : null;
            case "okhttp3.OkHttpClient":
            case "okhttp3.OkHttpClient$Builder":
                return ctor ? "new OkHttpClient" : null;
            case "org.apache.hc.client5.http.impl.classic.HttpClients":
            case "org.apache.http.impl.client.HttpClients":
                return "HttpClients." + method;
            case "org.apache.hc.client5.http.impl.classic.HttpClientBuilder":
            case "org.apache.http.impl.client.HttpClientBuilder":
                return method.equals("create") ? "HttpClientBuilder.create" : null;
            default:
                return null;
        }
    }

    /** Distinct raw forms a class builds, sorted. Empty for the factory package. */
    static Set<String> rawFormsIn(JavaClass c) {
        Set<String> forms = new TreeSet<>();
        if (c.getPackageName().equals(FACTORY_PACKAGE)) {
            return forms;
        }
        List<JavaCall<?>> calls = new ArrayList<>();
        calls.addAll(c.getMethodCallsFromSelf());
        calls.addAll(c.getConstructorCallsFromSelf());
        for (JavaCall<?> call : calls) {
            String form = rawForm(call);
            if (form != null) {
                forms.add(form);
            }
        }
        return forms;
    }

    /**
     * Messages name the class and the form only: a line number would make every edit a "new"
     * violation.
     */
    private static ArchCondition<JavaClass> notBuildARawHttpClient() {
        return new ArchCondition<JavaClass>("not build a raw outbound HTTP client") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                for (String form : rawFormsIn(c)) {
                    events.add(
                            violated(
                                    c,
                                    c.getName()
                                            + " builds a raw outbound HTTP client with "
                                            + form
                                            + " instead of SafeHttpClients"));
                }
            }
        };
    }

    static final ArchRule NO_RAW_HTTP_CLIENTS =
            classes()
                    .that()
                    .resideInAPackage(ROOT + "..")
                    .and()
                    .resideOutsideOfPackage(FACTORY_PACKAGE)
                    .should(notBuildARawHttpClient())
                    .as(
                            "outbound HTTP clients must come from SafeHttpClients, not a raw"
                                    + " HttpClient, RestTemplate, WebClient, OkHttp or Apache client")
                    .because(
                            "SafeHttpClients resolves every connection through SsrfGuard, so a"
                                    + " tenant-, model- or internet-supplied URL cannot reach loopback,"
                                    + " private or metadata addresses, and DNS rebinding is closed");

    @Test
    void outboundHttpClientsComeFromSafeHttpClients() {
        FreezingArchRule.freeze(NO_RAW_HTTP_CLIENTS).check(classes);
    }

    /** The matcher must flag what it claims to, or a refactor of it could blind the rule. */
    @Test
    void theRuleRecognisesEachRawFormOnAKnownFixture() {
        JavaClasses fixture = new ClassFileImporter().importClasses(RawHttpClientFixtures.class);
        Set<String> forms = rawFormsIn(fixture.get(RawHttpClientFixtures.class));

        assertThat(forms)
                .contains(
                        "HttpClient.newHttpClient",
                        "HttpClient.newBuilder",
                        "new RestTemplate",
                        "HttpClients.createDefault",
                        "HttpClientBuilder.create");
        assertThat(forms).as("an injected or factory-built client is allowed").hasSize(5);
    }

    @Test
    void theFactoryPackageItselfIsExempt() {
        assertThat(classes.containPackage(FACTORY_PACKAGE)).isTrue();
        classes.stream()
                .filter(c -> c.getPackageName().equals(FACTORY_PACKAGE))
                .forEach(c -> assertThat(rawFormsIn(c)).isEmpty());
    }
}
