package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.meta.dto.MetaActivationRequest;
import com.agenticai.core.omnichannel.meta.dto.MetaCredentialResponse;
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
 * BYOK activation for a tenant's own Meta WhatsApp Business Platform (Cloud
 * API) connection - the direct-to-Meta alternative to {@code
 * TwilioCredentialService}, mirroring its exact shape (validate with a real
 * call, encrypt at rest, one active row per tenant schema, register a
 * cross-tenant link for inbound webhook resolution).
 *
 * <p><b>What a tenant does to get these four values</b> (no code on this
 * platform can shortcut this - it happens entirely on Meta's side):
 * create a Meta App at developers.facebook.com, add the WhatsApp product,
 * grab the test (or a real, purchased) phone number's Phone Number ID and
 * the WhatsApp Business Account ID from the app dashboard, generate a
 * permanent access token for a System User with {@code
 * whatsapp_business_messaging} permission, and copy the App Secret from
 * the app's Settings -&gt; Basic page. This is genuinely more setup than
 * Twilio's sandbox (which can be live in under a minute) - the trade-off
 * for sending directly through Meta with no BSP markup.
 */
@Service
public class MetaWhatsAppCredentialService {

    private final MetaWhatsAppCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final TenantResolutionService tenantResolutionService;
    private final TenantSchemaResolver tenantSchemaResolver;
    private final MetaGraphApiClient graphApiClient;
    private final TenantLimits tenantLimits;

    public MetaWhatsAppCredentialService(
        MetaWhatsAppCredentialRepository repository,
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
    public MetaCredentialResponse activate(MetaActivationRequest request) {
        // Plan gate - WhatsApp is a paid-tier channel. Driven by the
        // data-driven whatsapp_allowed entitlement (control_plane.plan_limits)
        // so a platform-admin's web-admin edit actually controls it, not a
        // hardcoded plan check. Covers both entry points (manual BYOK here and
        // MetaEmbeddedSignupService, which routes through this same method).
        // Keyed on the raw tenant_id claim, same convention every other
        // TenantLimits caller uses (ProviderService/AgentService).
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !tenantLimits.whatsappAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                "Your plan does not include the WhatsApp channel - upgrade your plan to connect WhatsApp."
            );
        }

        MetaGraphApiClient.PhoneNumberInfo info = graphApiClient.fetchPhoneNumberInfo(request.phoneNumberId(), request.accessToken());

        String encryptedToken = encryptionService.encrypt(request.accessToken());
        String encryptedSecret = encryptionService.encrypt(request.appSecret());

        MetaWhatsAppCredential credential = repository.findFirstByOrderByCreatedAtAsc()
            .map(existing -> {
                existing.reactivate(request.phoneNumberId(), request.wabaId(), encryptedToken, encryptedSecret, info.displayPhoneNumber());
                return existing;
            })
            .orElseGet(() -> new MetaWhatsAppCredential(
                request.phoneNumberId(), request.wabaId(), encryptedToken, encryptedSecret, info.displayPhoneNumber()
            ));

        MetaCredentialResponse response = MetaCredentialResponse.from(repository.save(credential));

        // Same cross-tenant lookup pattern TwilioCredentialService registers
        // by AccountSid - here keyed by Phone Number ID, since that's the
        // stable identifier present in every inbound webhook payload's
        // `entry[].changes[].value.metadata.phone_number_id` field.
        tenantResolutionService.link(
            ExternalAccountProvider.META_WHATSAPP, request.phoneNumberId(), tenantSchemaResolver.resolveCurrentTenantIdentifier()
        );

        return response;
    }

    @Transactional(readOnly = true)
    public Optional<MetaCredentialResponse> current() {
        return repository.findFirstByOrderByCreatedAtAsc().map(MetaCredentialResponse::from);
    }

    /**
     * Tenant-initiated "log out of WhatsApp (Meta)" - marks the credential
     * {@link MetaWhatsAppCredentialStatus#DISCONNECTED} (same contract as
     * {@code TwilioCredentialService#disconnect}: row kept, {@link
     * #isConfigured}/{@link #resolveActive} go ACTIVE-only so {@code
     * WhatsAppDispatchService} immediately falls through to the next
     * provider) and drops the phoneNumberId-&gt;tenant link so inbound
     * webhook payloads stop resolving this tenant at all.
     */
    @Transactional
    public void disconnect() {
        repository.findFirstByOrderByCreatedAtAsc().ifPresent(credential -> {
            credential.disconnect();
            repository.save(credential);
            tenantResolutionService.unlink(ExternalAccountProvider.META_WHATSAPP, credential.getPhoneNumberId());
        });
    }

    /** Cheap, non-throwing check {@link com.agenticai.core.omnichannel.WhatsAppDispatchService} uses to pick a provider. */
    @Transactional(readOnly = true)
    public boolean isConfigured() {
        return repository.findFirstByOrderByCreatedAtAsc()
            .filter(c -> c.getStatus() == MetaWhatsAppCredentialStatus.ACTIVE)
            .isPresent();
    }

    /**
     * Resolves the active credential with its secrets decrypted, for {@link
     * com.agenticai.core.omnichannel.WhatsAppDispatchService} and {@link
     * MetaWhatsAppWebhookController}'s internal use only - never exposed via
     * a DTO/controller.
     */
    @Transactional(readOnly = true)
    public DecryptedCredential resolveActive() {
        MetaWhatsAppCredential credential = repository.findFirstByOrderByCreatedAtAsc()
            .filter(c -> c.getStatus() == MetaWhatsAppCredentialStatus.ACTIVE)
            .orElseThrow(MetaWhatsAppNotConfiguredException::new);
        return new DecryptedCredential(
            credential.getPhoneNumberId(),
            encryptionService.decrypt(credential.getEncryptedAccessToken()),
            encryptionService.decrypt(credential.getEncryptedAppSecret()),
            credential.getWabaId()
        );
    }

    public record DecryptedCredential(String phoneNumberId, String accessToken, String appSecret, String wabaId) {
    }
}
