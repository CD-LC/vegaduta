package com.agenticai.core.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelAccountLinkRepository extends JpaRepository<ChannelAccountLink, UUID> {

    Optional<ChannelAccountLink> findByProviderAndExternalAccountId(ExternalAccountProvider provider, String externalAccountId);

    List<ChannelAccountLink> findByProvider(ExternalAccountProvider provider);

    // core/mcpserver's key rotation/revocation (#unlink in TenantResolutionService) needs to
    // remove a stale link outright, not just leave it pointing at a dead credential - unlike
    // every other provider today, an MCP server API key can be rotated/revoked by the tenant
    // admin at any time, so its link must be removable on demand.
    void deleteByProviderAndExternalAccountId(ExternalAccountProvider provider, String externalAccountId);
}
