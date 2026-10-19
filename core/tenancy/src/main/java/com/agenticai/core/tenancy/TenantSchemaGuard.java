package com.agenticai.core.tenancy;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SEC-001 - the one place that decides (a) what a NEW tenant schema is called
 * and (b) whether a schema name is safe to hand to destructive DDL
 * ({@code DROP SCHEMA ... CASCADE}, {@code drop_graph}).
 *
 * <p><b>The attack this closes.</b> Tenant schema names used to be the
 * sanitised org name from an anonymous {@code POST /api/signup}. An org
 * called {@code acme_corp_kg} produced a schema name equal to another tenant's
 * Apache AGE graph schema; {@code ag_catalog} produced the AGE catalog itself.
 * Flyway then failed on the non-empty schema and the rollback ran {@code DROP
 * SCHEMA "<that name>" CASCADE}, deleting data the request never created.
 *
 * <p>Fix, in three independent layers:
 * <ol>
 *   <li><b>New names are server-generated only</b>: {@code t_} + 26 random
 *       lowercase base32 characters (130 bits). The org name is a display name
 *       and nothing else. {@link #NEW_TENANT_SCHEMA} is the strict shape.
 *   <li><b>Existence pre-check</b>: {@link #schemaExists} consults
 *       {@code pg_namespace} before a schema is created; the creator then runs
 *       a plain {@code CREATE SCHEMA} (no {@code IF NOT EXISTS}), so a race
 *       fails instead of adopting somebody else's schema.
 *   <li><b>Drop only what we created</b>: callers track a created-by-us flag,
 *       and every destructive path additionally refuses {@link
 *       #isProtectedSchema protected} names regardless of how it got there.
 * </ol>
 *
 * <p>Legacy tenant schemas (org-name-derived, created before this change) keep
 * working for migrate/read paths - only NEW creation requires the strict form.
 */
public final class TenantSchemaGuard {

    /** Strict shape of every schema this codebase creates for a new tenant from now on. */
    public static final Pattern NEW_TENANT_SCHEMA = Pattern.compile("^t_[a-z0-9]{16,40}$");

    /**
     * Any syntactically valid plain (lowercase, unquoted) Postgres identifier: what
     * SchemaNameSanitizer produces for legacy tenants, what {@link #generateNewTenantSchemaName}
     * produces for new ones, and the shape of every table/column/index name this codebase
     * interpolates into SQL. The ONE definition - {@link #requirePlainIdentifier} and {@link
     * #quoteIdentifier} are the only way an identifier reaches SQL text, so loosening this pattern
     * is a deliberate, single, reviewable change.
     */
    public static final Pattern PLAIN_IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");

    /** Apache AGE stores each tenant's knowledge graph in schema {@code <tenant_schema>_kg}. */
    public static final String GRAPH_SCHEMA_SUFFIX = "_kg";

    private static final Set<String> RESERVED = Set.of(
        "public", "control_plane", "information_schema", "ag_catalog",
        TenantSchemaResolver.NO_CONTEXT_SCHEMA
    );

    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
    private static final int RANDOM_CHARS = 26;
    private static final SecureRandom RANDOM = new SecureRandom();

    private TenantSchemaGuard() {
        // utility class
    }

    /** A fresh {@code t_<26 random base32 chars>} schema name. Never derived from user input. */
    public static String generateNewTenantSchemaName() {
        StringBuilder name = new StringBuilder("t_");
        for (int i = 0; i < RANDOM_CHARS; i++) {
            name.append(BASE32[RANDOM.nextInt(BASE32.length)]);
        }
        return name.toString();
    }

    public static boolean isNewTenantSchemaName(String schemaName) {
        return schemaName != null && NEW_TENANT_SCHEMA.matcher(schemaName).matches();
    }

    /** Throws unless {@code schemaName} has the strict new-tenant shape. */
    public static String requireNewTenantSchemaName(String schemaName) {
        if (!isNewTenantSchemaName(schemaName)) {
            throw new IllegalArgumentException(
                "Refusing to create tenant schema '" + schemaName + "' - new tenant schemas must match "
                    + NEW_TENANT_SCHEMA.pattern());
        }
        return schemaName;
    }

    /**
     * {@code true} when no destructive DDL may ever target this name: null,
     * not a plain lowercase identifier, a Postgres/platform/AGE system schema
     * ({@code public}, {@code control_plane}, {@code information_schema},
     * {@code ag_catalog}, {@code pg_*}), or the resolver's no-context sentinel.
     */
    public static boolean isProtectedSchema(String schemaName) {
        if (!isPlainIdentifier(schemaName)) {
            return true;
        }
        String lower = schemaName.toLowerCase(Locale.ROOT);
        return RESERVED.contains(lower) || lower.startsWith("pg_");
    }

    /** {@code true} when the name has the {@code <tenant>_kg} AGE graph-schema shape. */
    public static boolean looksLikeGraphSchema(String schemaName) {
        return schemaName != null && schemaName.endsWith(GRAPH_SCHEMA_SUFFIX);
    }

    /** {@code pg_namespace} existence check - bind parameter, never interpolated. */
    public static boolean schemaExists(Connection connection, String schemaName) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?")) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** {@code true} when {@code identifier} is non-null and matches {@link #PLAIN_IDENTIFIER}. */
    public static boolean isPlainIdentifier(String identifier) {
        return identifier != null && PLAIN_IDENTIFIER.matcher(identifier).matches();
    }

    /**
     * The single validation choke point for every schema, table, column or index name this
     * codebase interpolates into SQL text: returns {@code identifier} unchanged when it is a
     * {@link #PLAIN_IDENTIFIER plain identifier}, otherwise throws naming the offending value and
     * the pattern. Deliberately does NOT consult {@link #isProtectedSchema}: reading or recording
     * history for {@code public} is legitimate (the migrator, the startup strategy); refusing to
     * DROP it is each destructive caller's own guard.
     *
     * @throws IllegalArgumentException when {@code identifier} is null or not a plain identifier
     */
    public static String requirePlainIdentifier(String identifier) {
        if (!isPlainIdentifier(identifier)) {
            throw new IllegalArgumentException(
                "Refusing to use '" + identifier + "' as a SQL identifier - not a plain identifier matching "
                    + PLAIN_IDENTIFIER.pattern());
        }
        return identifier;
    }

    /**
     * Validates ({@link #requirePlainIdentifier}) and then renders a value as a double-quoted SQL
     * identifier. Fails closed: a name outside {@link #PLAIN_IDENTIFIER} is refused, never merely
     * quoted - every caller in this codebase passes server-generated, sanitised or constant
     * lowercase names, so there is no legitimate mixed-case or quote-bearing input to render.
     *
     * @throws IllegalArgumentException when {@code identifier} is null or not a plain identifier
     */
    public static String quoteIdentifier(String identifier) {
        return "\"" + requirePlainIdentifier(identifier) + "\"";
    }
}
