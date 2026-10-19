package com.agenticai.core.guardrails;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link GuardrailRule}. Routed to the calling tenant's schema
 * by the same Hibernate multi-tenancy resolver every other tenant-scoped
 * repository uses (the entity has no schema qualifier) - so every query here
 * is already implicitly tenant-scoped, no {@code tenantId} filter needed.
 */
public interface GuardrailRuleRepository extends JpaRepository<GuardrailRule, UUID> {

    List<GuardrailRule> findAllByOrderByCreatedAtAsc();

    List<GuardrailRule> findByRuleTypeOrderByCreatedAtAsc(GuardrailRuleType ruleType);
}
