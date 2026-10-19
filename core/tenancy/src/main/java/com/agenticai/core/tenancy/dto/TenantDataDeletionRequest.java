package com.agenticai.core.tenancy.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Master Roadmap 2026 item #22 (GDPR/data-residency basics, §G) - {@code
 * POST /api/tenant/data-deletion}'s request body.
 *
 * <p>{@code confirmOrgName} must match the calling tenant's own {@code
 * Tenant.name} exactly (see {@code TenantDataErasureService#eraseCurrentTenant})
 * - this codebase has no existing "type the name to confirm" precedent to
 * copy (its other destructive actions - {@code TenantEmergencyControlService}'s
 * stop/resume, credential deactivation, {@code ChannelPairingController}'s
 * erase-subject - are all gated by role alone, nothing beyond that). Dropping
 * an entire tenant's Postgres schema and every Keycloak user in it is a
 * different order of consequence than any of those, so this endpoint adds
 * one deliberate extra step modeled on the widely-understood "re-type the
 * resource's name" pattern (GitHub repo deletion, AWS resource deletion,
 * etc.) rather than either inventing a new confirmation-token round trip or
 * shipping this with no more friction than a credential DELETE call gets.
 */
public record TenantDataDeletionRequest(@NotBlank String confirmOrgName) {
}
