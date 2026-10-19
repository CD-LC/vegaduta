package com.agenticai.core;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup WARN when object storage is still on MinIO's well-known default secret outside a
 * dev/local/test profile.
 *
 * <p>{@code app.storage.secret-key} defaults to {@code ${STORAGE_SECRET_KEY:minioadmin}} in
 * application.yml. That default is kept on purpose - a bare local {@code mvn spring-boot:run}
 * against the compose MinIO has to work with zero setup - but it means a deployment that forgets
 * STORAGE_SECRET_KEY boots silently on a credential printed in every MinIO tutorial, and the object
 * store holds chat uploads, KB documents and generated media. Unlike POSTGRES_PASSWORD (no default,
 * fail-closed), a missing storage key is not made fatal here: staging's own deploy/.env.example
 * still ships minioadmin for its private, compose-network-only MinIO, and refusing to boot there
 * would be a regression. A loud, grep-able WARN is the proportionate signal.
 *
 * <p>Lives in app-config (component-scanned from {@code com.agenticai.core}) so the API server,
 * app-worker and app-jobs - all of which read the same application.yml - each warn, not just one of
 * them.
 */
@Component
public class DefaultStorageCredentialsWarning {

    private static final Log log = LogFactory.getLog(DefaultStorageCredentialsWarning.class);

    static final String DEFAULT_MINIO_CREDENTIAL = "minioadmin";

    /** Profiles where the MinIO default is expected and a warning would be noise. */
    static final Set<String> LOCAL_PROFILES = Set.of("dev", "local", "test");

    private final Environment environment;

    public DefaultStorageCredentialsWarning(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfDefaultCredentials() {
        if (!keyPairIsUsed(
                environment.getProperty("app.storage.type", "s3"),
                environment.getProperty("app.storage.credentials", "static"))) {
            return;
        }
        String secretKey = environment.getProperty("app.storage.secret-key");
        String[] activeProfiles = environment.getActiveProfiles();
        if (shouldWarn(secretKey, activeProfiles)) {
            log.warn(
                    "SECURITY: app.storage.secret-key resolves to MinIO's well-known default '"
                            + DEFAULT_MINIO_CREDENTIAL
                            + "' with active profiles "
                            + Arrays.toString(activeProfiles)
                            + ". Anyone who can reach the object store ("
                            + environment.getProperty("app.storage.endpoint")
                            + ") can read and overwrite every stored upload. Set STORAGE_SECRET_KEY"
                            + " (and STORAGE_ACCESS_KEY) to real credentials for this"
                            + " environment.");
        }
    }

    /**
     * Whether {@code app.storage.access-key/secret-key} are read at all. They are only the S3
     * client's credentials: with {@code credentials=default} (AWS SDK chain) they are ignored, and
     * with {@code type=azure-blob} (connection string or {@code DefaultAzureCredential}) or {@code
     * type=local} they are never used, so their MinIO defaults are harmless there.
     */
    static boolean keyPairIsUsed(String storageType, String credentials) {
        return "s3".equalsIgnoreCase(storageType.trim())
                && !"default".equalsIgnoreCase(credentials.trim());
    }

    /**
     * Pure decision, extracted for unit testing: warn when the resolved secret is the MinIO default
     * AND no active profile marks this as a dev/local/test run. Profile names compare
     * case-insensitively.
     */
    static boolean shouldWarn(String secretKey, String[] activeProfiles) {
        if (secretKey == null || !DEFAULT_MINIO_CREDENTIAL.equals(secretKey.trim())) {
            return false;
        }
        if (activeProfiles == null) {
            return true;
        }
        return Arrays.stream(activeProfiles)
                .map(p -> p.trim().toLowerCase(Locale.ROOT))
                .noneMatch(LOCAL_PROFILES::contains);
    }
}
