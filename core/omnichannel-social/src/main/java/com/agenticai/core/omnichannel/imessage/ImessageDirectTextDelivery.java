package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.outbound.DirectTextDelivery;
import org.springframework.stereotype.Component;

/** Immediate iMessage sends for workflow Output nodes (not approval-gated, same as the other channels' direct delivery). */
@Component
public class ImessageDirectTextDelivery implements DirectTextDelivery {

    private final ImessageCredentialService credentialService;
    private final ImessageApiClient apiClient;
    private final ImessageRateLimiter rateLimiter;

    public ImessageDirectTextDelivery(
            ImessageCredentialService credentialService,
            ImessageApiClient apiClient,
            ImessageRateLimiter rateLimiter) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public String channel() {
        return "imessage";
    }

    @Override
    public Prepared prepare(String to) {
        ImessageCredentialService.DecryptedCredential credential = credentialService.resolveActive();
        ImessageApiClient.toChatGuid(to); // validates the destination now, before the caller claims its dedup key
        return text -> {
            if (!rateLimiter.tryOutbound(ImessageChannelAdapter.tenantKey())) {
                throw new ChannelDeliveryException("imessage", "iMessage send rate limit reached - try again shortly");
            }
            return apiClient.sendText(credential.serverUrl(), credential.password(), to, text);
        };
    }
}
