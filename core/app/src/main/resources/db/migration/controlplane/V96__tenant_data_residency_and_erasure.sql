-- Master Roadmap 2026 item #22 (GDPR/data-residency basics, §G).
--
-- data_residency_region: nullable, no default backfill - see
-- DataResidencyRegion's javadoc (core/security) for why every existing and
-- newly-signed-up tenant starts UNSPECIFIED rather than this migration
-- guessing INDIA on everyone's behalf. Application-level enum, VARCHAR
-- storage, same convention as every other @Enumerated(STRING) column on
-- this table (plan, status, account_type, experience_mode) - no CHECK
-- constraint, so adding a new enum value later never needs its own
-- migration (see TenantStatus.ERASED, added in this same pass with no
-- accompanying DDL change for exactly that reason).
--
-- erased_at / erased_by: the durable record that a tenant-data-erasure
-- request (TenantDataErasureService) happened, when, and which authenticated
-- caller triggered it. Deliberately stored on THIS row, in control_plane -
-- not via the normal per-tenant AuditLogService/audit_log_entry mechanism
-- every other state-changing action in this codebase uses - because
-- audit_log_entry lives in the TENANT's own schema (see
-- V75__audit_log_entry_immutable.sql, under db/migration/tenant), which
-- erasure itself drops. An audit entry written moments before DROP SCHEMA
-- would be destroyed along with everything else it was trying to record.
-- These two columns survive specifically because they live outside the
-- schema being erased.
ALTER TABLE control_plane.tenant
    ADD COLUMN data_residency_region VARCHAR(32),
    ADD COLUMN erased_at TIMESTAMPTZ,
    ADD COLUMN erased_by VARCHAR(255);
