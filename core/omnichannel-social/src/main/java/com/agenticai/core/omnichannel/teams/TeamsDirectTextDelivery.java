package com.agenticai.core.omnichannel.teams;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.outbound.DirectTextDelivery;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.stereotype.Component;

/**
 * Immediate Teams sends for workflow Output nodes: the tenant's bot credential plus the
 * conversation's Bot Framework serviceUrl, which this platform only learns from inbound activity.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class TeamsDirectTextDelivery implements DirectTextDelivery {

    private final TeamsCredentialService credentialService;
    private final TeamsApiClient apiClient;

    public TeamsDirectTextDelivery(
            TeamsCredentialService credentialService, TeamsApiClient apiClient) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
    }

    @Override
    public String channel() {
        return "teams";
    }

    @Override
    public Prepared prepare(String conversationId) {
        TeamsCredentialService.DecryptedCredential credential = credentialService.resolveActive();
        String serviceUrl =
                credentialService
                        .serviceUrlFor(conversationId)
                        .orElseThrow(
                                () ->
                                        new ChannelConfigurationException(
                                                "teams",
                                                "Unknown Teams conversation - this platform only learns a conversation's serviceUrl "
                                                        + "once someone in it has messaged the bot at least once."));
        return text ->
                apiClient.sendActivity(
                        credential.appId(),
                        credential.appPassword(),
                        credential.aadTenantId(),
                        serviceUrl,
                        conversationId,
                        text,
                        null);
    }
}
