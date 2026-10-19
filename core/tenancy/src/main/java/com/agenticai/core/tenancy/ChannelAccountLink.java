package com.agenticai.core.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps an external channel account identifier (a Twilio Account SID today)
 * to the tenant schema that owns it - the one piece of information an
 * inbound webhook needs before anything else, since webhooks carry no
 * Keycloak JWT and therefore never populate {@code TenantContext} via the
 * normal {@code TenantContextFilter} path.
 *
 * <p>Lives in {@code control_plane}, not any tenant schema, for a structural
 * reason, not just convention: "which tenant does this belong to" is a
 * cross-tenant question by definition - it cannot be answered from inside
 * the very tenant schema it's trying to identify, the same reasoning {@link
 * Tenant} itself lives in {@code control_plane}.
 */
@Entity
@Table(
    name = "channel_account_link",
    schema = "control_plane",
    uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "external_account_id"})
)
public class ChannelAccountLink {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExternalAccountProvider provider;

    @Column(name = "external_account_id", nullable = false, length = 128)
    private String externalAccountId;

    @Column(name = "tenant_schema", nullable = false, length = 63)
    private String tenantSchema;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChannelAccountLink() {
        // JPA
    }

    public ChannelAccountLink(ExternalAccountProvider provider, String externalAccountId, String tenantSchema) {
        this.provider = provider;
        this.externalAccountId = externalAccountId;
        this.tenantSchema = tenantSchema;
        this.createdAt = Instant.now();
    }

    public void setTenantSchema(String tenantSchema) {
        this.tenantSchema = tenantSchema;
    }

    public UUID getId() {
        return id;
    }

    public ExternalAccountProvider getProvider() {
        return provider;
    }

    public String getExternalAccountId() {
        return externalAccountId;
    }

    public String getTenantSchema() {
        return tenantSchema;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
