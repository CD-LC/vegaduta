package com.agenticai.core.omnichannel.teams;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.omnichannel.teams.dto.TeamsActivationRequest;
import com.agenticai.core.omnichannel.teams.dto.TeamsCredentialResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.tenancy.ExternalAccountProvider;
import com.agenticai.core.tenancy.TenantResolutionService;
import com.agenticai.core.tenancy.TenantSchemaResolver;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * BYOK activation for a tenant's own Azure Bot registration (Microsoft Teams channel) - mirrors
 * {@code TelegramCredentialService}'s shape, adapted to what Azure can actually prove: there is no
 * "getMe" equivalent on Bot Framework, so {@link #activate} mints a real client-credentials token
 * against Entra as the "prove it before persisting" step, then links the appId to this tenant
 * schema for inbound webhook resolution (see {@code TeamsWebhookController}).
 *
 * <p><b>What a tenant does to get these values:</b> Azure portal -> Bot resource (or "Azure Bot" /
 * "Bot Channels Registration") -> copy the Microsoft App ID, create/copy a client secret, note the
 * app tenant ("botframework.com" for multi-tenant registrations). The messaging endpoint to paste
 * into the Azure bot's Configuration tab is {@code https://<api-host>/webhook/teams/<appId>}.
 */
@Service
public class TeamsCredentialService {

    static final String DEFAULT_AAD_TENANT = "botframework.com";

    private final TeamsCredentialRepository repository;
    private final TeamsConversationRouteRepository routeRepository;
    private final EncryptionService encryptionService;
    private final TenantResolutionService tenantResolutionService;
    private final TenantSchemaResolver tenantSchemaResolver;
    private final TeamsApiClient apiClient;
    private final TenantLimits tenantLimits;

    public TeamsCredentialService(
            TeamsCredentialRepository repository,
            TeamsConversationRouteRepository routeRepository,
            EncryptionService encryptionService,
            TenantResolutionService tenantResolutionService,
            TenantSchemaResolver tenantSchemaResolver,
            TeamsApiClient apiClient,
            TenantLimits tenantLimits) {
        this.repository = repository;
        this.routeRepository = routeRepository;
        this.encryptionService = encryptionService;
        this.tenantResolutionService = tenantResolutionService;
        this.tenantSchemaResolver = tenantSchemaResolver;
        this.apiClient = apiClient;
        this.tenantLimits = tenantLimits;
    }

    @Transactional
    public TeamsCredentialResponse activate(TeamsActivationRequest request) {
        // Plan gate - third-party channel connections are a paid-tier
        // entitlement (plan_limits.external_channels_allowed), enforced at
        // the service boundary so a direct API call cannot bypass it.
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !tenantLimits.externalChannelsAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                    "Your plan does not include external channel connections - upgrade your plan to connect Microsoft Teams.");
        }
        String aadTenantId =
                StringUtils.hasText(request.aadTenantId())
                        ? request.aadTenantId().trim()
                        : DEFAULT_AAD_TENANT;
        apiClient.validateCredentials(request.appId().trim(), request.appPassword(), aadTenantId);
        String encryptedPassword = encryptionService.encrypt(request.appPassword());

        TeamsCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .map(
                                existing -> {
                                    existing.reactivate(
                                            request.appId().trim(), aadTenantId, encryptedPassword);
                                    return existing;
                                })
                        .orElseGet(
                                () ->
                                        new TeamsCredential(
                                                request.appId().trim(),
                                                aadTenantId,
                                                encryptedPassword));

        TeamsCredentialResponse response =
                TeamsCredentialResponse.from(repository.save(credential));

        tenantResolutionService.link(
                ExternalAccountProvider.TEAMS,
                request.appId().trim(),
                tenantSchemaResolver.resolveCurrentTenantIdentifier());

        return response;
    }

    @Transactional(readOnly = true)
    public Optional<TeamsCredentialResponse> current() {
        return repository.findFirstByOrderByCreatedAtAsc().map(TeamsCredentialResponse::from);
    }

    /**
     * Tenant-initiated "log out of Teams" - marks the credential {@link
     * TeamsCredentialStatus#DISCONNECTED} (same contract as {@code
     * TwilioCredentialService#disconnect}) and drops the appId-&gt;tenant link, so {@code
     * TeamsWebhookController} stops resolving this tenant and inbound activities die at the
     * resolution step. The Azure bot's messaging endpoint keeps pointing at this platform until the
     * tenant removes it on Azure's side - unlinking makes those calls a no-op here.
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
                                    ExternalAccountProvider.TEAMS, credential.getAppId());
                        });
    }

    @Transactional(readOnly = true)
    public DecryptedCredential resolveActive() {
        TeamsCredential credential =
                repository
                        .findFirstByOrderByCreatedAtAsc()
                        .filter(c -> c.getStatus() == TeamsCredentialStatus.ACTIVE)
                        .orElseThrow(TeamsNotConfiguredException::new);
        return new DecryptedCredential(
                credential.getAppId(),
                encryptionService.decrypt(credential.getEncryptedAppPassword()),
                credential.getAadTenantId());
    }

    /**
     * Called by {@code TeamsWebhookController} on every inbound activity: the Bot Framework
     * serviceUrl is per-conversation and can move between regional endpoints, so the freshest one
     * always wins.
     */
    @Transactional
    public void rememberRoute(String conversationId, String serviceUrl) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(serviceUrl)) {
            return;
        }
        TeamsConversationRoute route =
                routeRepository
                        .findById(conversationId)
                        .orElseGet(() -> new TeamsConversationRoute(conversationId, serviceUrl));
        route.refresh(serviceUrl);
        routeRepository.save(route);
    }

    /**
     * The serviceUrl an async send path (operator inbox, workflow Output, manual send) needs when
     * it no longer has the triggering activity in hand - {@code empty} if this conversation has
     * never been seen inbound.
     */
    @Transactional(readOnly = true)
    public Optional<String> serviceUrlFor(String conversationId) {
        return routeRepository.findById(conversationId).map(TeamsConversationRoute::getServiceUrl);
    }

    public record DecryptedCredential(String appId, String appPassword, String aadTenantId) {}
}
