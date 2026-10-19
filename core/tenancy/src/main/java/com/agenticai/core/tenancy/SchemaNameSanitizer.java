package com.agenticai.core.tenancy;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns an arbitrary string (a JWT {@code tenant_id} claim, Stage 4; a
 * signup form's org name, Stage 5) into a value safe to use as a Postgres
 * unquoted schema identifier.
 *
 * <p>Extracted out of {@link TenantSchemaResolver}, which had this exact
 * logic first (see its own javadoc, which named this exact extraction as
 * the natural seam once Stage 5 needed the same convention) - kept as one
 * class specifically so the "how does a raw string become a schema name"
 * rule can never quietly drift between the two call sites. {@link
 * TenantProvisioningService} additionally re-checks the result against
 * {@link com.agenticai.core.tenancy.TenantRepository#findBySchemaName} for
 * collisions before committing to it (this class has no way to know about
 * already-provisioned tenants; it only guarantees syntactic validity).
 */
public final class SchemaNameSanitizer {

    /** Where every table lived before Phase 0 Stage 4 - see TenantSchemaResolver. */
    public static final String DEFAULT_SCHEMA = "public";

    // Postgres unquoted identifiers: lowercase letters, digits, underscores,
    // must not start with a digit. Anything else gets replaced with "_"
    // rather than trusted as already safe - this value ultimately reaches a
    // SET search_path-equivalent JDBC call or a literal `CREATE SCHEMA`
    // statement, so it is sanitized here, at the one shared place every raw
    // tenant identifier passes through on its way to becoming a schema name.
    private static final Pattern INVALID_CHARS = Pattern.compile("[^a-z0-9_]");

    private SchemaNameSanitizer() {
        // utility class
    }

    public static String sanitize(String raw) {
        return sanitize(raw, DEFAULT_SCHEMA);
    }

    /**
     * @param fallback returned as-is (not itself re-sanitized) when
     *     {@code raw} is null/blank - callers pass their own context-
     *     appropriate default (Stage 4's resolver falls back to "public";
     *     Stage 5's provisioning service should never hit this branch at
     *     all, since a blank org name fails request validation first).
     */
    public static String sanitize(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String lowered = raw.toLowerCase(Locale.ROOT);
        String cleaned = INVALID_CHARS.matcher(lowered).replaceAll("_");
        if (cleaned.isBlank()) {
            return fallback;
        }
        if (Character.isDigit(cleaned.charAt(0))) {
            cleaned = "t_" + cleaned;
        }
        return cleaned.length() > 63 ? cleaned.substring(0, 63) : cleaned;
    }
}
