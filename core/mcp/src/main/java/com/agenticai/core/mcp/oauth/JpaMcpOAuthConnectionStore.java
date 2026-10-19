package com.agenticai.core.mcp.oauth;

import com.agenticai.core.security.TenantContext;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Durable {@link McpOAuthConnectionStore} on {@code mcp_oauth_connection} (V424).
 * {@code @Primary}, so it supersedes {@link InMemoryMcpOAuthConnectionStore}
 * everywhere the interface is injected.
 *
 * <p><b>Tenant schema.</b> The table lives in each tenant's schema, resolved from
 * the ambient {@link TenantContext} when a transaction opens. The OAuth callback
 * has no JWT and so no ambient tenant (identity travels in the encrypted state),
 * so every call here runs under {@code tenantId} - the ambient context is reused
 * when it already names that tenant, otherwise it is swapped for the call and
 * always restored. Deliberately not {@code @Transactional} itself: the context
 * must be in place before the repository's own transaction starts.
 */
@Component
@Primary
public class JpaMcpOAuthConnectionStore implements McpOAuthConnectionStore {

    private static final String SYSTEM_SUBJECT = "system:mcp-oauth-store";

    private final McpOAuthConnectionRepository repository;

    public JpaMcpOAuthConnectionStore(McpOAuthConnectionRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<McpOAuthConnection> find(String tenantId, String resourceUrl) {
        return inTenant(tenantId, () -> repository.findByResourceUrl(resourceUrl).map(e -> e.toConnection(tenantId)));
    }

    @Override
    public void save(McpOAuthConnection connection) {
        inTenant(connection.tenantId(), () -> {
            McpOAuthConnectionEntity entity = repository.findByResourceUrl(connection.resourceUrl())
                .orElseGet(() -> new McpOAuthConnectionEntity(connection.resourceUrl()));
            entity.apply(connection);
            repository.save(entity);
            return Boolean.TRUE;
        });
    }

    @Override
    public void delete(String tenantId, String resourceUrl) {
        inTenant(tenantId, () -> {
            repository.findByResourceUrl(resourceUrl).ifPresent(repository::delete);
            return Boolean.TRUE;
        });
    }

    private static <T> T inTenant(String tenantId, Supplier<T> work) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("McpOAuthConnectionStore called without a tenant id");
        }
        Optional<TenantContext> previous = TenantContext.current();
        if (previous.isPresent() && Objects.equals(previous.get().getTenantId(), tenantId)) {
            return work.get();
        }
        String subject = previous.map(TenantContext::getSubject).orElse(SYSTEM_SUBJECT);
        TenantContext.set(TenantContext.of(tenantId, subject, Set.of()));
        try {
            return work.get();
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }
}
