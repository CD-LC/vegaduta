package com.agenticai.core.omnichannel.whatsappqr;

import com.agenticai.core.omnichannel.status.ChannelConnection;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** WhatsApp QR's connection state for the Channels screen: the linked number, or what is wrong. */
@Component
public class WhatsAppQrChannelConnection implements ChannelConnection {

    private final WhatsAppQrService service;
    private final WhatsAppQrClient client;
    private final WhatsAppQrConnectionRepository repository;
    private final ObjectProvider<WhatsAppQrReceiveService> receiveService;

    public WhatsAppQrChannelConnection(
            WhatsAppQrService service,
            WhatsAppQrClient client,
            WhatsAppQrConnectionRepository repository,
            ObjectProvider<WhatsAppQrReceiveService> receiveService) {
        this.service = service;
        this.client = client;
        this.repository = repository;
        this.receiveService = receiveService;
    }

    @Override
    public String key() {
        return "whatsappqr";
    }

    @Override
    public Summary summary() {
        return repository
                .findFirstByOrderByCreatedAtAsc()
                .map(
                        row -> {
                            if (!client.configured() && row.getStatus() != WhatsAppQrStatus.DISCONNECTED) {
                                // A linked row cannot pair or receive without a bridge; "waiting for
                                // QR scan" would be a lie.
                                return Summary.needsAttention(WhatsAppQrReceiveService.NOT_CONFIGURED);
                            }
                            String problem =
                                    receiveService
                                            .stream()
                                            .map(r -> r.problem(row.getSessionKey()))
                                            .filter(p -> p != null)
                                            .findFirst()
                                            .orElse(null);
                            if (row.getStatus() == WhatsAppQrStatus.ACTIVE) {
                                if (problem != null) {
                                    return Summary.needsAttention(problem);
                                }
                                return Summary.connected(
                                        row.getPhoneNumber() == null
                                                ? "linked device"
                                                : row.getPhoneNumber());
                            }
                            if (row.getStatus() == WhatsAppQrStatus.PAIRING) {
                                return Summary.needsAttention(problem != null ? problem : "waiting for QR scan");
                            }
                            return Summary.notConnected("was connected - relink below");
                        })
                .orElseGet(() -> Summary.notConnected("+ link device"));
    }

    @Override
    public String probe() {
        try {
            WhatsAppQrHandle handle = service.resolveActive();
            client.sessionStatus(handle.sessionKey());
            return handle.phoneNumber() == null ? "linked device" : handle.phoneNumber();
        } catch (WhatsAppQrNotConfiguredException e) {
            throw new ChannelNotConnectedException();
        }
    }
}
