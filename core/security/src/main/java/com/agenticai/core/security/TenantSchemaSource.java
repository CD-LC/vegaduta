package com.agenticai.core.security;

/**
 * Narrow port: the Postgres schema of the tenant bound to the calling thread, for raw-JDBC code
 * that must name the tenant's tables itself because its connection comes straight from the pool
 * (search_path {@code public}) rather than from Hibernate's tenant connection provider. Implemented
 * by the tenancy module ({@code TenantSchemaResolver}); declared here so that a module which only
 * needs the name (settings) does not depend on tenancy.
 *
 * <p>Fails closed like the resolver: a thread without a tenant gets a schema that holds no tables,
 * never the shared one.
 */
public interface TenantSchemaSource {

    /** The unquoted schema name of the current tenant; the caller quotes it. */
    String currentSchemaName();
}
