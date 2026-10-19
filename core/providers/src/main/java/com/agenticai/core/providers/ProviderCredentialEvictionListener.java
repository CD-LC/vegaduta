package com.agenticai.core.providers;

import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.TenantContext;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Evicts the chat hot path's cached decrypted provider key and the ChatClient built from it
 * ({@link HotpathCaches#PROVIDER_KEY}/{@code GATEWAY_CLIENT}) on ANY write to a provider credential row
 * (rotate, activate/deactivate, delete). Evicts at flush and again after commit. The credential table is
 * per-tenant schema, so the tenant is the one in {@link TenantContext} on the writing thread; with no
 * context every entry for the cache is flushed rather than guessing. Other pods only see the change after
 * the cache TTL. Both caches are disabled by default, in which case this is a cheap no-op.
 */
public class ProviderCredentialEvictionListener {

    @PostPersist
    @PostUpdate
    @PostRemove
    void onWrite(LlmProviderCredential credential) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        String provider = credential.getProviderName() == null ? null : credential.getProviderName().name();
        HotpathCaches.evictProviderCredential(tenantId, provider);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    HotpathCaches.evictProviderCredential(tenantId, provider);
                }
            });
        }
    }
}
