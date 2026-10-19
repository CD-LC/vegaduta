package com.agenticai.core.it;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared Spring context for every context-level IT (one boot per JVM: identical configuration, so
 * the TestContext cache reuses it). See ApplicationContextIT for why these profiles/properties.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.profiles.active=security,knowledge,graph-memory",
            // Keycloak itself is never contacted; see the class javadoc. The issuer has no
            // localhost default any more (AGENTS.md rule 4) and app.web.public-url must be set
            // under `security` (PublicWebUrlStartupValidator): non-routable test values.
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://keycloak.invalid/realms/test",
            "app.web.public-url=http://app.invalid",
            // The committed TEST-ONLY key from CoreApplicationTests (burned, never
            // a deployed key - see the rules documented there).
            "app.encryption.key=hqfzJpKYl+OWYpyllCtUBEB0rS/iZUAu231ls90mBb0=",
            "app.storage.type=local",
            "app.storage.local.path=${java.io.tmpdir}/vegaduta-it-storage",
            "management.tracing.sampling.probability=0.0",
            "management.otlp.tracing.export.enabled=false",
            "management.endpoint.health.show-details=always"
        })
public abstract class AbstractContextIT {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = IntegrationTestContainers.postgres();

    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> REDIS = IntegrationTestContainers.redis();
}
