package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Signal's connection state for the Channels screen: the connected number, or what is wrong. */
@Component
public class SignalChannelConnection implements ChannelConnection {

    private final SignalCredentialService credentialService;
    private final SignalApiClient apiClient;
    private final ObjectProvider<SignalReceiveService> receiveService;

    public SignalChannelConnection(
            SignalCredentialService credentialService,
            SignalApiClient apiClient,
            ObjectProvider<SignalReceiveService> receiveService) {
        this.credentialService = credentialService;
        this.apiClient = apiClient;
        this.receiveService = receiveService;
    }

    @Override
    public String key() {
        return "signal";
    }

    @Override
    public Summary summary() {
        return credentialService
                .current()
                .filter(c -> c.status() == SignalCredentialStatus.ACTIVE)
                .map(
                        c -> {
                            // Cheap: the receiver's own health, no outbound call.
                            String problem =
                                    receiveService
                                            .stream()
                                            .map(r -> r.problem(c.number()))
                                            .filter(p -> p != null)
                                            .findFirst()
                                            .orElse(null);
                            return problem == null
                                    ? Summary.connected(c.number())
                                    : Summary.needsAttention(problem);
                        })
                .orElseGet(() -> Summary.notConnected("+ connect"));
    }

    @Override
    public String probe() {
        try {
            SignalConnection connection = credentialService.resolveActive();
            credentialService.verify(connection);
            return connection.accountNumber();
        } catch (SignalNotConfiguredException e) {
            throw new ChannelNotConnectedException();
        }
    }
}
