package com.agenticai.core.agents.core;

import com.agenticai.core.security.TenantContext;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Base for "a write to one of these entities invalidates a cache" listeners. Registers on
 * Hibernate's event registry, so the entities themselves (some very large) need no annotation, and
 * every write path is covered without each knowing about the cache. Evicts at flush and again after
 * commit: between the two, a concurrent reader could re-read the still-uncommitted old row and
 * re-fill the cache. Same discipline as {@code TenantPlanEvictionListener}.
 */
abstract class WriteEviction
        implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private static final long serialVersionUID = 1L;

    private final transient EntityManagerFactory entityManagerFactory;

    WriteEviction(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    /** Whether a write to this entity invalidates the cache. */
    abstract boolean watches(Object entity);

    /**
     * Drops the cached answers affected by a write to {@code entity}; called at flush and after
     * commit.
     */
    abstract void evict(String tenantId, Object entity);

    @PostConstruct
    void register() {
        EventListenerRegistry registry =
                entityManagerFactory
                        .unwrap(SessionFactoryImplementor.class)
                        .getServiceRegistry()
                        .getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        onWrite(event.getEntity());
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        onWrite(event.getEntity());
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        onWrite(event.getEntity());
    }

    @Override
    public boolean requiresPostCommitHandling(EntityPersister persister) {
        return false;
    }

    private void onWrite(Object entity) {
        if (!watches(entity)) {
            return;
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        evict(tenantId, entity);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            evict(tenantId, entity);
                        }
                    });
        }
    }
}
