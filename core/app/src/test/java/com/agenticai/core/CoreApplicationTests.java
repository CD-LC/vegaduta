package com.agenticai.core;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Stage 1 smoke test: confirms the Spring context loads cleanly.
 *
 * <p>{@code spring.profiles.active=insecure} replaces application.yml's baked-in {@code
 * security,knowledge,workflow,graph-memory} default specifically for this test - a
 * {@code @TestPropertySource} property has higher precedence than application.yml, so this reliably
 * wins regardless of that default. Without it, this context load would require a reachable Keycloak
 * instance (security), Redpanda (workflow), and Apache AGE (graph-memory) just to boot -
 * infrastructure a bare CI "does it compile and does the context start" smoke test shouldn't need.
 * {@code insecure} (rather than the pre-2026-07-29 bare empty value) is now REQUIRED, not just
 * conventional: the security-audit fix inverted the filter-chain gating so {@code
 * NoSecurityConfig}'s permissive chain only registers on an explicit {@code @Profile("insecure")}
 * opt-in, and {@code SecurityConfig} ({@code @Profile("!insecure")}) - which needs
 * application-security.yml's Keycloak properties - is the default for every other profile
 * combination, including "none". With profiles merely cleared, the context would now fail fast on
 * SecurityConfig's unresolved issuer-uri placeholder (by design - that IS the fail-closed
 * behavior); opting into {@code insecure} is the one sanctioned way for a Keycloak-less smoke test
 * to boot. Postgres (with pgvector and Apache AGE compiled into the image per
 * deploy/postgres/Dockerfile) is still required, since Flyway migrations run unconditionally at
 * startup regardless of which Spring profiles are active.
 *
 * <p><b>Zero manual setup.</b> Three things are handled automatically so a plain {@code mvn clean
 * install} needs no env exports:
 *
 * <ul>
 *   <li>The encryption key {@code EncryptionService} demands is injected here as {@code
 *       app.encryption.key} - a TEST-ONLY throwaway value (see the comment on the property itself
 *       for the rules around it). It never affects prod (prod sets the real {@code
 *       APP_ENCRYPTION_KEY} env, and this property is test-scoped).
 *   <li>The datasource password: application.yml deliberately has NO default for {@code
 *       POSTGRES_PASSWORD} anymore (2026-07-29 audit - a real deployment must fail to boot on a
 *       missing DB credential rather than silently trying "changeme"). This test restores the
 *       local-dev fallback TEST-SCOPED via {@code spring.datasource.password} below, so the
 *       zero-manual-setup local build keeps working; a real {@code POSTGRES_PASSWORD} env var (CI
 *       sets one - see .github/workflows/ci.yml's backend job) still wins through the placeholder.
 *   <li>The test is {@link EnabledIf gated on a usable Postgres}: the same {@code DB_HOST}/{@code
 *       DB_PORT}/{@code POSTGRES_DB}/{@code POSTGRES_USER}/{@code POSTGRES_PASSWORD} (defaults
 *       {@code localhost:5432/agentic_ai}, {@code agentic}/{@code changeme}) the context itself
 *       will use must accept a JDBC login. It runs automatically once such a database is up (e.g.
 *       {@code docker compose -f deploy/docker-compose.yml up -d postgres}) and SKIPS - rather than
 *       hard-failing the build - when none is usable. A bare TCP probe is NOT enough: a developer
 *       machine routinely has some other Postgres on 5432 (the local staging stack, whose password
 *       comes from deploy/.env and is not the test fallback) - the port opens, the gate used to say
 *       "run", and the context then errored on authentication. CI brings Postgres up first with
 *       matching credentials (see {@code .github/workflows/ci.yml}), so it always runs there.
 * </ul>
 *
 * The gate changes only WHEN the smoke test runs, never what it asserts.
 */
@SpringBootTest
@TestPropertySource(
        properties = {
            // The deployed profile set. 'insecure' alone no longer boots: always-on
            // beans (PlatformTenantDeletionOrchestrationService, FaceEnrollmentService)
            // need security/knowledge-profiled beans. Keycloak is not contacted at
            // startup (issuer resolved lazily). Same set as it/ApplicationContextIT.
            "spring.profiles.active=security,knowledge,graph-memory",
            // application-security.yml carries NO localhost default for the issuer any more
            // (AGENTS.md rule 4), and PublicWebUrlStartupValidator refuses a blank
            // app.web.public-url under `security`; both are test-only non-routable values.
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://keycloak.invalid/realms/test",
            "app.web.public-url=http://app.invalid",
            "app.storage.type=local",
            "app.storage.local.path=${java.io.tmpdir}/vegaduta-smoke-storage",
            "management.tracing.sampling.probability=0.0",
            "management.otlp.tracing.export.enabled=false",
            // TEST-ONLY throwaway AES key, committed to the repo so `mvn clean install`
            // boots the context with no manual env export. Because it lives in git
            // history forever, it is PERMANENTLY BURNED as a real key: it must NEVER
            // match deploy/.env's APP_ENCRYPTION_KEY or any other deployed
            // environment's key (verified non-matching, 2026-07-29 audit). If they
            // ever converged, every provider API key / channel secret in that
            // deployment would be encrypted under a public, repo-committed key -
            // i.e. effectively plaintext to anyone with repo access. If you rotate
            // this test value, generate a fresh one (openssl rand -base64 32); never
            // copy a deployed key here and never copy this value into a deployment.
            "app.encryption.key=hqfzJpKYl+OWYpyllCtUBEB0rS/iZUAu231ls90mBb0=",
            // Test-scoped restoration of the local-dev DB password fallback that
            // application.yml no longer carries (fail-closed on missing
            // POSTGRES_PASSWORD - 2026-07-29 audit). The placeholder keeps a real
            // POSTGRES_PASSWORD env var authoritative when one is set (CI does).
            "spring.datasource.password=${POSTGRES_PASSWORD:changeme}"
        })
@EnabledIf("postgresReachable")
class CoreApplicationTests {

    @Test
    void contextLoads() {
        // If the application context fails to start, this test fails.
    }

    /**
     * True when the Postgres the context is about to boot against accepts a login with the same URL
     * and credentials application.yml resolves (plus this class's test-scoped password fallback). A
     * fast TCP check runs first so a machine with nothing on the port never waits on the driver;
     * short connect/login timeouts keep the JDBC attempt from stalling the build. Any failure
     * (nothing listening, DNS, wrong password, missing database) simply disables this test.
     */
    static boolean postgresReachable() {
        String host = env("DB_HOST", "localhost");
        int port;
        try {
            port = Integer.parseInt(env("DB_PORT", "5432"));
        } catch (NumberFormatException e) {
            port = 5432;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
        } catch (Exception e) {
            return false;
        }
        String url =
                "jdbc:postgresql://"
                        + host
                        + ":"
                        + port
                        + "/"
                        + env("POSTGRES_DB", "agentic_ai")
                        + "?connectTimeout=3&loginTimeout=5&socketTimeout=5";
        try (Connection ignored =
                DriverManager.getConnection(
                        url,
                        env("POSTGRES_USER", "agentic"),
                        env("POSTGRES_PASSWORD", "changeme"))) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String env(String name, String fallback) {
        // Same semantics as the ${NAME:fallback} placeholders the context resolves.
        return System.getenv().getOrDefault(name, fallback);
    }
}
