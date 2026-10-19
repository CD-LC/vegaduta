package com.agenticai.core.omnichannel.email;

import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import org.springframework.stereotype.Component;

/** Email's connection state for the Channels screen: the IMAP username. */
@Component
public class EmailChannelConnection implements ChannelConnection {

    private final EmailCredentialService credentialService;

    public EmailChannelConnection(EmailCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @Override
    public String key() {
        return "email";
    }

    @Override
    public Summary summary() {
        return credentialService
                .current()
                .filter(c -> c.status() == EmailCredentialStatus.ACTIVE)
                .map(c -> Summary.connected(c.imapUsername()))
                .orElseGet(() -> Summary.notConnected("+ connect"));
    }

    @Override
    public String probe() {
        try {
            credentialService.verifyActive();
            return credentialService.current().map(c -> c.imapUsername()).orElse("connected");
        } catch (EmailNotConfiguredException e) {
            throw new ChannelNotConnectedException();
        }
    }
}
