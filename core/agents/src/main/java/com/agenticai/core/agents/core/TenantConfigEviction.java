package com.agenticai.core.agents.core;

import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.TenantConfigEntity;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

/**
 * Drops a tenant's cached "is this configured" answers ({@link
 * com.agenticai.core.security.HotpathLookups}) when an entity marked {@link TenantConfigEntity} is
 * written. The entities mark themselves, so this class depends on none of them (and the kernel on
 * no vendor module). A write evicts the tenant's whole config namespace, which is coarse on
 * purpose: these entities change a few times a year.
 */
@Component
class TenantConfigEviction extends WriteEviction {

    private static final long serialVersionUID = 1L;

    TenantConfigEviction(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    boolean watches(Object entity) {
        return AnnotationUtils.findAnnotation(entity.getClass(), TenantConfigEntity.class) != null;
    }

    @Override
    void evict(String tenantId, Object entity) {
        HotpathCaches.evictTenantConfig(tenantId);
    }
}
