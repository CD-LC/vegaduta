package com.agenticai.core.dbauth;

import java.util.Arrays;
import java.util.Locale;

/** How core logs in to Postgres ({@code app.datasource.auth}, env {@code DB_AUTH}). */
public enum DbAuthMode {
    /**
     * Static password from {@code spring.datasource.password}. The default; Spring Boot builds the
     * pool.
     */
    PASSWORD("password"),
    /** AWS RDS / Aurora IAM database authentication token (15 minutes). */
    AWS_IAM("aws-iam"),
    /** Microsoft Entra access token for Azure Database for PostgreSQL. */
    AZURE_ENTRA("azure-entra"),
    /** Cloud SQL IAM database authentication: an OAuth2 access token as the password. */
    GCP_IAM("gcp-iam");

    private final String value;

    DbAuthMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean usesToken() {
        return this != PASSWORD;
    }

    /**
     * Blank means {@link #PASSWORD}; an unknown value fails startup rather than silently using a
     * password.
     */
    public static DbAuthMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return PASSWORD;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        for (DbAuthMode m : values()) {
            if (m.value.equals(v)) {
                return m;
            }
        }
        throw new IllegalStateException(
                "Unknown app.datasource.auth (DB_AUTH) value '"
                        + raw
                        + "'. Allowed: "
                        + Arrays.stream(values()).map(DbAuthMode::value).toList());
    }
}
