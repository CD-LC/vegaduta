package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.meta.dto.MetaSocialActivationRequest;
import com.agenticai.core.omnichannel.meta.dto.MetaSocialCredentialResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BYOK activation for a tenant's Facebook Page connection - Messenger and
 * (optionally) Instagram DMs both ride on this one credential. Mirrors
 * {@link MetaWhatsAppCredentialService}'s exact shape: validate with a real
 * call, encrypt at rest, one active row per tenant schema, register a
 * cross-tenant link so the inbound webhook (which has no JWT) can resolve
 * which tenant a given Page/Instagram account belongs to.
 *
 * <p><b>What a tenant does to get these values</b> (same Meta App they may
 * have already created for WhatsApp can be reused - just add the Messenger
 * product, and the Instagram product if they want DMs too): from
 * developers.facebook.com, add the Messenger product, generate a Page
 * Access Token for the Page they want to connect (Messenger &gt; Settings
 * &gt; Access Tokens), and copy the App Secret from Settings &gt; Basic - the
 * same App Secret already used for WhatsApp if this is the same app. If
 * adding Instagram, the Page must already have an Instagram Professional
 * account linked (Meta Business Suite), and its ID comes from the
 * Instagram product's own setup page.
 */
@Service
public class MetaSocialCredentialService {

    private final MetaSocialCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final TenantResolutionService tenantResolutionService;
    private final TenantSchemaResolver tenantSchemaResolver;
    private final MetaGraphApiClient graphApiClient;
    private final TenantLimits tenantLimits;

    public MetaSocialCredentialService(
        MetaSocialCredentialRepository repository,
        EncryptionService encryptionService,
        TenantResolutionService tenantResolutionService,
        TenantSchemaResolver tenantSchemaResolver,
        MetaGraphApiClient graphApiClient,
        TenantLimits tenantLimits
    ) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.tenantResolutionService = tenantResolutionService;
        this.tenantSchemaResolver = tenantSchemaResolver;
        this.graphApiClient = graphApiClient;
        this.tenantLimits = tenantLimits;
    }

    @Transactional
    public MetaSocialCredentialResponse activate(MetaSocialActivationRequest request) {
        // Plan gate - Messenger/Instagram are paid-tier channel surfaces
        // (plan_limits.external_channels_allowed), enforced at the service
        // boundary so a direct API call cannot bypass it. The WhatsApp side
        // of Meta keeps its own whatsapp_allowed flag - see
        // MetaWhatsAppCredentialService.
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !tenantLimits.externalChannelsAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                "Your plan does not include external channel connections - upgrade your plan to connect Messenger/Instagram."
            );
        }
        MetaGraphApiClient.PageInfo pageInfo = graphApiClient.fetchPageInfo(request.pageId(), request.pageAccessToken());

        // Best-effort only (see fetchInstagramUsername's javadoc) - a
        // lookup failure here shouldn't block activating Messenger, and the
        // instagramBusinessAccountId itself is trusted as entered (the
        // tenant got it directly from their own Meta App dashboard).
        String instagramUsername = request.hasInstagram()
            ? graphApiClient.fetchInstagramUsername(request.instagramBusinessAccountId(), request.pageAccessToken())
            : null;

        String encryptedToken = encryptionService.encrypt(request.pageAccessToken());
        String encryptedSecret = encryptionService.encrypt(request.appSecret());
        String igId = request.hasInstagram() ? request.instagramBusinessAccountId() : null;

        MetaSocialCredential credential = repository.findFirstByOrderByCreatedAtAsc()
            .map(existing -> {
                existing.reactivate(request.pageId(), pageInfo.name(), encryptedToken, encryptedSecret, igId, instagramUsername);
                return existing;
            })
            .orElseGet(() -> new MetaSocialCredential(
                request.pageId(), pageInfo.name(), encryptedToken, encryptedSecret, igId, instagramUsername
            ));

        MetaSocialCredentialResponse response = MetaSocialCredentialResponse.from(repository.save(credential));

        String tenantSchema = tenantSchemaResolver.resolveCurrentTenantIdentifier();
        tenantResolutionService.link(ExternalAccountProvider.META_MESSENGER, request.pageId(), tenantSchema);
        if (request.hasInstagram()) {
            tenantResolutionService.link(ExternalAccountProvider.META_INSTAGRAM, request.instagramBusinessAccountId(), tenantSchema);
        }

        return response;
    }

    @Transactional(readOnly = true)
    public Optional<MetaSocialCredentialResponse> current() {
        return repository.findFirstByOrderByCreatedAtAsc().map(MetaSocialCredentialResponse::from);
    }

    /**
     * Tenant-initiated "log out of this Facebook Page" - marks the
     * credential {@link MetaSocialCredentialStatus#DISCONNECTED} (same
     * contract as {@code TwilioCredentialService#disconnect}) and drops
     * BOTH cross-tenant links this credential registered: Page ID -&gt;
     * tenant (Messenger) and, when Instagram was connected, Instagram
     * Business Account ID -&gt; tenant. Without both, one surface could
     * keep resolving this tenant and reaching its agents after "disconnect".
     */
    @Transactional
    public void disconnect() {
        repository.findFirstByOrderByCreatedAtAsc().ifPresent(credential -> {
            credential.disconnect();
            repository.save(credential);
            tenantResolutionService.unlink(ExternalAccountProvider.META_MESSENGER, credential.getPageId());
            if (credential.hasInstagram()) {
                tenantResolutionService.unlink(ExternalAccountProvider.META_INSTAGRAM, credential.getInstagramBusinessAccountId());
            }
        });
    }

    /**
     * Resolves the active credential with its secrets decrypted, for {@link
     * MetaMessengerChannelAdapter}/{@link MetaInstagramChannelAdapter}/
     * {@link MetaSocialWebhookController} internal use only - never exposed
     * via a DTO/controller.
     */
    @Transactional(readOnly = true)
    public DecryptedCredential resolveActive() {
        MetaSocialCredential credential = repository.findFirstByOrderByCreatedAtAsc()
            .filter(c -> c.getStatus() == MetaSocialCredentialStatus.ACTIVE)
            .orElseThrow(MetaSocialNotConfiguredException::new);
        return new DecryptedCredential(
            credential.getPageId(),
            encryptionService.decrypt(credential.getEncryptedPageAccessToken()),
            encryptionService.decrypt(credential.getEncryptedAppSecret()),
            credential.hasInstagram() ? credential.getInstagramBusinessAccountId() : null
        );
    }

    public record DecryptedCredential(String pageId, String pageAccessToken, String appSecret, String instagramBusinessAccountId) {
        public boolean hasInstagram() {
            return instagramBusinessAccountId != null;
        }
    }
}
