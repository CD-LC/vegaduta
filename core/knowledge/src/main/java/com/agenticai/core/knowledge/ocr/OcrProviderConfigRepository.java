package com.agenticai.core.knowledge.ocr;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Schema-per-tenant multi-tenancy means this table is a physically different
 * table per tenant schema, same as every other single-credential repository
 * in this codebase (see {@code SarvamCredentialRepository}) - {@link
 * #findFirstByOrderByCreatedAtAsc()} finds "the one row for this tenant"
 * with no explicit tenant filter needed.
 */
public interface OcrProviderConfigRepository extends JpaRepository<OcrProviderConfig, UUID> {
    Optional<OcrProviderConfig> findFirstByOrderByCreatedAtAsc();
}
