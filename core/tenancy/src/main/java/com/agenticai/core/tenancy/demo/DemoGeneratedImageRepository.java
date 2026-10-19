package com.agenticai.core.tenancy.demo;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lookups for {@link DemoGeneratedImage}. The only hot path is
 * {@code findById} - the capability-URL resolution behind
 * {@code GET /api/demo/image/{id}}, which is a single primary-key probe by
 * design (see the entity's javadoc for why the id is the credential).
 */
public interface DemoGeneratedImageRepository extends JpaRepository<DemoGeneratedImage, UUID> {

    /** Abuse review / future purge sweep - not used on any request path. */
    long countBySessionId(UUID sessionId);
}
