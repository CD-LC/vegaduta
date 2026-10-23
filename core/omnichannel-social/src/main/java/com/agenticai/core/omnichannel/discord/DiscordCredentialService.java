package com.agenticai.core.omnichannel.discord;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.discord.dto.DiscordActivationRequest;
import com.agenticai.core.omnichannel.discord.dto.DiscordCredentialResponse;
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
 * BYOK activation for a tenant's own Discord Application/Bot - mirrors {@code
 * SlackCredentialService}'s shape.
 *
 * <p><b>What a tenant does to get this value</b>: create an Application at
 * discord.com/developers/applications, add a Bot to it, enable the "Message Content Intent" under
 * Bot settings (required to read ordinary message text, not just mentions/commands), copy the Bot
 * Token, and invite the bot to their server via the OAuth2 URL Generator with the {@code bot} scope
 * and {@code Send Messages}/{@code Read Message History} permissions. There is no webhook URL to
 * configure on Discord's side - inbound delivery instead requires running the standalone {@code
 * channels/discord} Node service (see its README).
 */
@Service
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class DiscordCredentialService {

    private final DiscordCredentialRepository repository;
    private final EncryptionService encryptionService;
    private final TenantResolutionService tenantResolutionService;
    private final TenantSchemaResolver tenantSchemaResolver;
    private final DiscordApiClient discordApiClient;
    private final DiscordGatewayClient discordGatewayClient;
    private final TenantLimits tenantLimits;

    public DiscordCredentialService(
            DiscordCredentialRepository repository,
            EncryptionService encryptionService,
            TenantResolutionService tenantResolutionService,
            TenantSchemaResolver tenantSchemaResolver,
            DiscordApiClient discordApiClient,
            DiscordGatewayClient discordGatewayClient,
            TenantLimits tenantLimits) {
        this.repository = repository;
        this.encryptionService = encryptionService;
        this.tenantResolutionService = tenantResolutionService;
        this.tenantSchemaResolver = tenantSchemaResolver;
        this.discordApiClient = discordApiClient;
        this.discordGatewayClient = discordGatewayClient;
        this.tenantLimits = tenantLimits;
    }

    @Transactional
    public DiscordCredentialResponse activate(DiscordActivationRequest request) {
        // Plan gate - third-party channel connections are a paid-tier
        // entitlement (plan_limits.external_channels_allowed), enforced at
        // the service boundary so a direct API call cannot bypass it.
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !tenantLimits.externalChannelsAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                    "Your plan does not include external channel connections - upgrade your plan to connect Discord.");
        }
        DiscordApiClient.BotInfo botInfo = discordApiClient.fetchBotInfo(request.botToken());

        String encryptedToken = encryptionService.encrypt(request.botToken());

        DiscordCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .map(
                                existing -> {
                                    existing.reactivate(
                                            botInfo.applicationId(),
                                            botInfo.username(),
                                            encryptedToken);
                                    return existing;
                                })
                        .orElseGet(
                                () ->
                                        new DiscordCredential(
                                                botInfo.applicationId(),
                                                botInfo.username(),
                                                encryptedToken));

        DiscordCredentialResponse response =
                DiscordCredentialResponse.from(repository.save(credential));

        tenantResolutionService.link(
                ExternalAccountProvider.DISCORD,
                botInfo.applicationId(),
                tenantSchemaResolver.resolveCurrentTenantIdentifier());

        // Best-effort - see DiscordGatewayClient's javadoc for why a failure
        // here doesn't fail activation itself.
        discordGatewayClient.startBot(botInfo.applicationId(), request.botToken());

        return response;
    }

    @Transactional(readOnly = true)
    public Optional<DiscordCredentialResponse> current() {
        return repository.findFirstByOrderByCreatedAtAsc().map(DiscordCredentialResponse::from);
    }

    /**
     * Tenant-initiated "log out of Discord" - marks the credential {@link
     * DiscordCredentialStatus#DISCONNECTED} (same contract as {@code
     * TwilioCredentialService#disconnect}), drops the application-&gt;tenant link so forwarded
     * inbound messages stop resolving this tenant, and tells the Gateway bridge to drop the
     * WebSocket ({@link DiscordGatewayClient#stopBot} - best-effort, it already swallows its own
     * failures) so the bot stops hearing new messages at all rather than just failing to route
     * them.
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
                                    ExternalAccountProvider.DISCORD, credential.getApplicationId());
                            discordGatewayClient.stopBot(credential.getApplicationId());
                        });
    }

    @Transactional(readOnly = true)
    public DecryptedCredential resolveActive() {
        DiscordCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .filter(c -> c.getStatus() == DiscordCredentialStatus.ACTIVE)
                        .orElseThrow(DiscordNotConfiguredException::new);
        return new DecryptedCredential(
                credential.getApplicationId(),
                encryptionService.decrypt(credential.getEncryptedBotToken()));
    }

    public record DecryptedCredential(String applicationId, String botToken) {}
}
