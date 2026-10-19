package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import org.springframework.stereotype.Component;

/**
 * SMS's connection state for the Channels screen (2026-10-14). Like voice, plain SMS reuses the
 * tenant's Twilio credential, so this is the same ACTIVE-only check and the same account probe as
 * {@link TwilioVoiceChannelConnection}.
 */
@Component
public class TwilioSmsChannelConnection implements ChannelConnection {

    private final TwilioCredentialService credentialService;
    private final TwilioAccountProbe accountProbe;

    public TwilioSmsChannelConnection(
            TwilioCredentialService credentialService, TwilioAccountProbe accountProbe) {
        this.credentialService = credentialService;
        this.accountProbe = accountProbe;
    }

    @Override
    public String key() {
        return "sms";
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
