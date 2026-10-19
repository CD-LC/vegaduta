package com.agenticai.core.tenancy;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves "which tenant schema owns this external channel account" - the
 * question an inbound webhook (Twilio today) must answer before it can do
 * anything schema-scoped at all, since it never goes through {@code
 * TenantContextFilter} the way a normal JWT-bearing API call does.
 *
 * <p>Reads and writes go through {@link ChannelAccountLinkRepository}
 * directly rather than any tenant-scoped table - by design, since this is
 * exactly the cross-tenant lookup {@link ChannelAccountLink}'s javadoc
 * explains the need for.
 */
@Service
public class TenantResolutionService {

    private final ChannelAccountLinkRepository repository;

    public TenantResolutionService(ChannelAccountLinkRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<String> resolveTenantSchema(ExternalAccountProvider provider, String externalAccountId) {
        return repository.findByProviderAndExternalAccountId(provider, externalAccountId)
            .map(ChannelAccountLink::getTenantSchema);
    }

    /**
     * Every external account of one provider with the tenant schema that owns it. For background
     * work that has no inbound request to resolve a tenant from (Telegram long polling on an install
     * with no public URL).
     */
    @Transactional(readOnly = true)
    public List<AccountTenant> listAccounts(ExternalAccountProvider provider) {
        return repository.findByProvider(provider).stream()
            .map(l -> new AccountTenant(l.getExternalAccountId(), l.getTenantSchema()))
            .toList();
    }

    public record AccountTenant(String externalAccountId, String tenantSchema) {
    }

    /**
     * Registers which tenant schema owns an external account - called from
     * {@code TwilioCredentialService.activate()} the moment a tenant's
     * Twilio credential is saved, using whichever schema {@link
     * TenantSchemaResolver} currently resolves to (the same schema that
     * credential row itself just landed in).
     *
     * <p>Upsert, not insert-only: if this external account id was linked to
     * a different tenant schema before (a rare case - reusing a Twilio
     * Account SID across tenants shouldn't normally happen, but isn't
     * enforced against), the link is repointed rather than left stale.
     */
    @Transactional
    public void link(ExternalAccountProvider provider, String externalAccountId, String tenantSchema) {
        ChannelAccountLink link = repository.findByProviderAndExternalAccountId(provider, externalAccountId)
            .orElseGet(() -> new ChannelAccountLink(provider, externalAccountId, tenantSchema));
        link.setTenantSchema(tenantSchema);
        repository.save(link);
    }

    /**
     * Removes a link outright - added for core/mcpserver's key rotation/revocation, where a
     * stale link must stop resolving immediately rather than just being repointed (the only
     * mutation every other caller of {@link #link} needed until now). A no-op if no such link
     * exists (rotating/revoking a key that, for whatever reason, was never linked).
     */
    @Transactional
    public void unlink(ExternalAccountProvider provider, String externalAccountId) {
        repository.deleteByProviderAndExternalAccountId(provider, externalAccountId);
    }
}
