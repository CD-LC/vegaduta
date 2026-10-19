package com.agenticai.core.omnichannel.teams;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/**
 * Teams text sends for the kernel's {@link ChannelTextSend}: {@link TeamsChannelAdapter}'s action
 * type and payload. The Bot Framework needs the per-conversation serviceUrl, which this platform
 * only learns from inbound activity - the same lookup {@code TeamsSendController} does.
 */
@Component
public class TeamsTextSend implements ChannelTextSend {

    private final TeamsCredentialService credentialService;

    public TeamsTextSend(TeamsCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @Override
    public ChannelType channel() {
        return ChannelType.TEAMS;
    }

    @Override
    public String actionType() {
        return TeamsChannelAdapter.ACTION_TYPE;
    }

    @Override
    public String summary(String to) {
        return "Send Teams message to " + to;
    }

    @Override
    public Object payload(String to, String subject, String body) {
        String serviceUrl =
                credentialService
                        .serviceUrlFor(to)
                        .orElseThrow(
                                () ->
                                        new RecipientNotAddressableException(
                                                "Unknown Teams conversation id - this platform only learns a conversation's serviceUrl "
                                                        + "once someone in it has messaged the bot at least once."));
        return new TeamsChannelAdapter.SendPayload(serviceUrl, to, body, null);
    }
}
