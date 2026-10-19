package com.agenticai.core.guardrails.moderation;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Phase P6. Write path for the restricted audit table. Intentionally exposes
 * no query methods beyond {@code JpaRepository}'s inherited ones - nothing in
 * this codebase reads restricted records back through a general surface; any
 * access is a deliberate, counsel-approved, out-of-band operation.
 */
public interface ModerationRestrictedAuditRepository extends JpaRepository<ModerationRestrictedAudit, UUID> {
}
