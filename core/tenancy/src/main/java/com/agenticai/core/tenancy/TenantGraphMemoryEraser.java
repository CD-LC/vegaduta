package com.agenticai.core.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Erasure strategy for the tenant's Apache AGE graph-memory graph (the {@code <schema>_kg} schema).
 * Extracted unchanged from {@link TenantDataErasureService}; best-effort by design.
 */
final class TenantGraphMemoryEraser {

    private static final Logger log = LoggerFactory.getLogger(TenantGraphMemoryEraser.class);

    private final TenantRepository tenantRepository;
    private final DataSource dataSource;

    TenantGraphMemoryEraser(TenantRepository tenantRepository, DataSource dataSource) {
        this.tenantRepository = tenantRepository;
        this.dataSource = dataSource;
    }

    /** AGE graph teardown/lookup: the graph name is a bound value, never SQL text. */
    private static final String DROP_GRAPH = "SELECT * FROM ag_catalog.drop_graph(?::name, true)";
    private static final String GRAPH_REGISTERED =
            "SELECT COUNT(*) FROM ag_catalog.ag_graph WHERE name::text = ?";

    /**
     * Drops the tenant's Apache AGE graph-memory graph - the {@code <tenant_schema>_kg} schema
     * {@code V44__graph_memory.sql} and {@code GraphMemorySchemaSupport} both derive. Returns
     * {@code true} if the graph is provably gone, {@code false} if something was left behind
     * (already logged) - the caller uses that only to keep the deletion-audit row honest about
     * which steps actually completed.
     *
     * <p><b>Why this is no longer a plain {@code DROP SCHEMA ... CASCADE} (prod incident
     * 2026-08-17).</b> Erasing tenant "Shyama" failed twice with {@code failure_detail = "Failed to
     * drop schema 'shyama' while erasing tenant 'Shyama'"}, yet the DB afterwards showed schema
     * {@code shyama} successfully DROPPED and {@code shyama_kg} still present - i.e. it was the
     * SECOND statement, the {@code _kg} one, that threw, and the error message named the wrong
     * schema entirely (both statements ran inside one try block that only knew about {@code
     * schemaName}, and both use {@code IF EXISTS}, so "not found" was never a possible cause and
     * the message sent whoever read that audit row hunting the wrong object). The real cause is
     * that an AGE graph is not just a schema: {@code create_graph()} also registers a row in {@code
     * ag_catalog.ag_graph} and wires the graph's label tables to it, and a raw {@code DROP SCHEMA
     * CASCADE} either trips over those catalog dependencies or "succeeds" while orphaning the
     * {@code ag_graph} row. AGE's supported teardown is {@code drop_graph(name, true)}, which
     * unregisters the catalog row and drops the schema as one operation, so that is what this uses
     * whenever the graph is actually registered.
     *
     * <p>The {@code DROP SCHEMA} fallback is kept for the case the original comment here called out
     * - tenants provisioned before the graph-memory migration existed, or with the graph-memory
     * profile never active - where there is no {@code ag_graph} row (and possibly no AGE extension
     * at all, which the {@code to_regclass} probe below tolerates without throwing) but there may
     * still be a stray {@code _kg} schema to clean up. It also runs as a last resort if {@code
     * drop_graph} itself fails, so a half-torn-down graph from an earlier failed attempt (exactly
     * the state Shyama was left in) still gets cleaned up rather than being retried forever.
     *
     * <p><b>Why a failure here is best-effort and no longer aborts the whole erasure.</b> This runs
     * only AFTER the tenant's own schema - every message, document, credential, and audit row the
     * tenant ever had - has been dropped successfully. The old behavior rethrew, which meant {@code
     * Tenant.markErased} never ran and the control-plane row stayed {@code status=ACTIVE}/{@code
     * erased_at=NULL} forever: exactly what happened to Shyama, whose data was gone while the
     * platform still believed the tenant was live, and where every retry re-ran the whole sequence
     * against an already-empty tenant and failed again on the same step. Leaving a graph of
     * extracted entity/relationship triples behind is a real, loggable gap that needs manual
     * cleanup - but it is strictly less bad than a tenant that can never be marked erased, and the
     * log-and- continue posture matches what the Keycloak-user loop and the object-storage loop in
     * this same class already do for their own partial failures.
     */
    boolean dropGraphMemorySchema(Tenant tenant, String schemaName) {
        String graphName = schemaName + TenantSchemaGuard.GRAPH_SCHEMA_SUFFIX;
        // SEC-001: "<schema>_kg" can collide with a DIFFERENT tenant's own
        // schema (legacy org-name-derived names: tenant "acme" vs tenant
        // "acme kg" -> schema acme_kg). Never drop another tenant's schema.
        Optional<Tenant> owner = tenantRepository.findBySchemaName(graphName);
        if (TenantSchemaGuard.isProtectedSchema(graphName)
                || (owner.isPresent()
                        && !java.util.Objects.equals(owner.get().getId(), tenant.getId()))) {
            log.error(
                    "Erasure: NOT dropping '{}' for tenant '{}' - it is a protected schema or"
                            + " another tenant's schema",
                    graphName,
                    tenant.getName());
            return false;
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            if (isAgeGraphRegistered(connection, statement, graphName)) {
                try {
                    // Load AGE on the SAME connection, same reason
                    // GraphMemoryService documents at length: AGE's functions
                    // are only usable on a session that has loaded the
                    // extension's library. AgeSessionSupport skips the load
                    // when AGE is preloaded and otherwise uses the
                    // non-superuser-safe $libdir/plugins path.
                    try (AgeSessionSupport.Session age = AgeSessionSupport.open(connection);
                            PreparedStatement dropGraph =
                                    connection.prepareStatement(DROP_GRAPH)) {
                        dropGraph.setString(1, graphName);
                        dropGraph.execute();
                    }
                    return true;
                } catch (SQLException dropGraphFailure) {
                    log.error(
                            "Erasure: ag_catalog.drop_graph('{}', true) failed while erasing tenant"
                                    + " '{}' - falling back to DROP SCHEMA",
                            graphName,
                            tenant.getName(),
                            dropGraphFailure);
                }
            }
            statement.execute("DROP SCHEMA IF EXISTS " + quoteIdentifier(graphName) + " CASCADE");
            return true;
        } catch (SQLException graphDropFailure) {
            // Named after the schema that ACTUALLY failed, not the tenant
            // schema - the whole point of the 2026-08-17 fix above.
            log.error(
                    "Erasure: failed to drop graph-memory schema '{}' while erasing tenant '{}' -"
                        + " the tenant's own schema IS dropped, so erasure continues and this graph"
                        + " needs manual cleanup",
                    graphName,
                    tenant.getName(),
                    graphDropFailure);
            return false;
        }
    }

    /**
     * {@code true} only if AGE is installed AND this tenant's graph is registered in {@code
     * ag_catalog.ag_graph}. Deliberately probes with {@code to_regclass} first: that returns NULL
     * (rather than throwing) for a table in a schema that doesn't exist, so a database with no AGE
     * extension at all - a perfectly normal deployment, the graph-memory module is profile-gated -
     * answers "no" instead of raising an error this method would then have to distinguish from a
     * real failure. Any unexpected SQL problem also answers "no", which just routes the caller to
     * the plain {@code DROP SCHEMA} path.
     */
    boolean isAgeGraphRegistered(
            Connection connection, Statement statement, String graphName) {
        try (ResultSet ageInstalled =
                statement.executeQuery("SELECT to_regclass('ag_catalog.ag_graph') IS NOT NULL")) {
            if (!ageInstalled.next() || !ageInstalled.getBoolean(1)) {
                return false;
            }
        } catch (SQLException e) {
            log.warn(
                    "Could not probe for the Apache AGE catalog while erasing graph '{}' - {}",
                    graphName,
                    e.getMessage());
            return false;
        }
        try (PreparedStatement lookup = connection.prepareStatement(GRAPH_REGISTERED)) {
            lookup.setString(1, graphName);
            try (ResultSet graphRow = lookup.executeQuery()) {
                return graphRow.next() && graphRow.getLong(1) > 0;
            }
        } catch (SQLException e) {
            log.warn(
                    "Could not look up graph '{}' in ag_catalog.ag_graph - {}",
                    graphName,
                    e.getMessage());
            return false;
        }
    }

    private static String quoteIdentifier(String identifier) {
        return TenantSchemaGuard.quoteIdentifier(identifier);
    }
}
