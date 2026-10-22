package com.agenticai.core.omnichannel.slack;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.slack.dto.SlackActivationRequest;
import com.agenticai.core.omnichannel.slack.dto.SlackCredentialResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BYOK activation for a tenant's own Slack App - mirrors {@code MetaWhatsAppCredentialService}'s
 * shape exactly.
 *
 * <p><b>What a tenant does to get these two values</b>: create a Slack App at api.slack.com/apps,
 * add the {@code chat:write} and {@code im:history}/ {@code channels:history} OAuth scopes, install
 * it to their workspace to get a Bot Token ({@code xoxb-...}) from OAuth &amp; Permissions,
 * subscribe to the {@code message.im} (and/or {@code message.channels}) event under Event
 * Subscriptions pointing at this platform's {@code /webhook/slack} URL, and copy the Signing Secret
 * from Basic Information.
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class SlackCredentialService {

    private final SlackCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final TenantResolutionService tenantResolutionService;
    private final TenantSchemaResolver tenantSchemaResolver;
    private final SlackApiClient slackApiClient;
    private final TenantLimits tenantLimits;

    public SlackCredentialService(
            SlackCredentialRepository repository,
            EncryptionService encryptionService,
            TenantResolutionService tenantResolutionService,
            TenantSchemaResolver tenantSchemaResolver,
            SlackApiClient slackApiClient,
            TenantLimits tenantLimits) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.tenantResolutionService = tenantResolutionService;
        this.tenantSchemaResolver = tenantSchemaResolver;
        this.slackApiClient = slackApiClient;
        this.tenantLimits = tenantLimits;
    }

    @Transactional
    public SlackCredentialResponse activate(SlackActivationRequest request) {
        // Plan gate - third-party channel connections are a paid-tier
        // entitlement (plan_limits.external_channels_allowed), enforced
        // here at the service boundary rather than only in the UI so a
        // direct API call cannot bypass it.
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !tenantLimits.externalChannelsAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                    "Your plan does not include external channel connections - upgrade your plan to connect Slack.");
        }
        SlackApiClient.AuthInfo authInfo = slackApiClient.authTest(request.botToken());

        String encryptedToken = encryptionService.encrypt(request.botToken());
        String encryptedSecret = encryptionService.encrypt(request.signingSecret());

        SlackCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .map(
                                existing -> {
                                    existing.reactivate(
                                            authInfo.teamId(),
                                            authInfo.teamName(),
                                            authInfo.botUserId(),
                                            encryptedToken,
                                            encryptedSecret);
                                    return existing;
                                })
                        .orElseGet(
                                () ->
                                        new SlackCredential(
                                                authInfo.teamId(),
                                                authInfo.teamName(),
                                                authInfo.botUserId(),
                                                encryptedToken,
                                                encryptedSecret));

        SlackCredentialResponse response =
                SlackCredentialResponse.from(repository.save(credential));

        tenantResolutionService.link(
                ExternalAccountProvider.SLACK,
                authInfo.teamId(),
                tenantSchemaResolver.resolveCurrentTenantIdentifier());

        return response;
    }

    @Transactional(readOnly = true)
    public Optional<SlackCredentialResponse> current() {
        return repository.findFirstByOrderByCreatedAtAsc().map(SlackCredentialResponse::from);
    }

    /**
     * Tenant-initiated "log out of Slack" - marks the credential {@link
     * SlackCredentialStatus#DISCONNECTED} rather than deleting it (same contract as {@code
     * TwilioCredentialService#disconnect}: the row stays so {@link #current} can still show what
     * was connected, while {@link #resolveActive} filters to ACTIVE so every outbound caller stops
     * using it). Also drops the workspace-&gt;tenant link: without that, inbound Events API
     * payloads carrying this Team ID would keep resolving this tenant and keep reaching its agents
     * after "disconnect" ({@code SlackWebhookController} warns and drops payloads with no link).
     * Slack has no server-side webhook registration for us to undo - unlinking IS the disconnect on
     * the inbound side.
     */
    @Transactional
    public void disconnect() {
        repository
                .findFirstByOrderByCreatedAtAsc()
                .ifPresent(
                        credential -> {
                            credential.disconnect();
                            repository.save(credential);
                            tenantResolutionService.unlink(
                                    ExternalAccountProvider.SLACK, credential.getTeamId());
                        });
    }

    @Transactional(readOnly = true)
    public DecryptedCredential resolveActive() {
        SlackCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .filter(c -> c.getStatus() == SlackCredentialStatus.ACTIVE)
                        .orElseThrow(SlackNotConfiguredException::new);
        return new DecryptedCredential(
                credential.getTeamId(),
                credential.getBotUserId(),
                encryptionService.decrypt(credential.getEncryptedBotToken()),
                encryptionService.decrypt(credential.getEncryptedSigningSecret()));
    }

    public record DecryptedCredential(
            String teamId, String botUserId, String botToken, String signingSecret) {}
}
