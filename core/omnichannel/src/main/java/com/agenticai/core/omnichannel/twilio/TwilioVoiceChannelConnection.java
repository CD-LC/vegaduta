package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import org.springframework.stereotype.Component;

/**
 * Voice's connection state for the Channels screen. Voice reuses the tenant's existing Twilio
 * credential (see {@code VoiceChannelController} - "there is nothing separate to activate"), so
 * this tracks the same ACTIVE-only Twilio check WhatsApp-via-Twilio uses, and the probe is the same
 * account fetch.
 */
@Component
public class TwilioVoiceChannelConnection implements ChannelConnection {

    private final TwilioCredentialService credentialService;
    private final TwilioAccountProbe accountProbe;

    public TwilioVoiceChannelConnection(
            TwilioCredentialService credentialService, TwilioAccountProbe accountProbe) {
        this.credentialService = credentialService;
        this.accountProbe = accountProbe;
    }

    @Override
    public String key() {
        return "voice";
    }

    @Override
    public Summary summary() {
        return credentialService.isActive()
                ? Summary.connected("Twilio")
                : Summary.notConnected("+ connect Twilio above");
    }

    @Override
    public String probe() {
        if (!credentialService.isActive()) {
            throw new ChannelNotConnectedException();
        }
        return "Twilio - " + accountProbe.accountIdentity();
    }
}
