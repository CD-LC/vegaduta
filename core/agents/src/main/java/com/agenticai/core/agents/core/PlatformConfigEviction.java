package com.agenticai.core.agents.core;

import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.PlatformConfigEntity;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

/**
 * Drops the cached platform-wide config answers ({@link
 * com.agenticai.core.security.HotpathLookups#platform}) when an entity marked {@link
 * PlatformConfigEntity} is written. The platform twin of {@link TenantConfigEviction}: the entities
 * mark themselves, so this class depends on none of them. These rows change when an operator edits
 * the hosted pool, a few times a year, so a write drops every platform answer on the pod.
 */
@Component
class PlatformConfigEviction extends WriteEviction {

    private static final long serialVersionUID = 1L;

    PlatformConfigEviction(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    boolean watches(Object entity) {
        return AnnotationUtils.findAnnotation(entity.getClass(), PlatformConfigEntity.class)
                != null;
    }

    @Override
    void evict(String tenantId, Object entity) {
        HotpathCaches.evictPlatformConfig();
    }
}
