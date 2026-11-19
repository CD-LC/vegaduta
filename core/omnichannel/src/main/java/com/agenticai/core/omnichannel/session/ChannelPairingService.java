package com.agenticai.core.omnichannel.session;

import com.agenticai.core.omnichannel.session.ChannelPairingController.PendingPairingResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-facing facade over {@link ChannelSessionService}'s pairing operations: maps
 * {@link ChannelSession} to {@link PendingPairingResponse} (last-4 masking of the external id)
 * so {@link ChannelPairingController} never touches the JPA entity.
 */
@Service
public class ChannelPairingService {

    private final ChannelSessionService channelSessionService;

    public ChannelPairingService(ChannelSessionService channelSessionService) {
        this.channelSessionService = channelSessionService;
    }

    @Transactional(readOnly = true)
    public List<PendingPairingResponse> listPending() {
        return channelSessionService.listPendingPairings().stream()
            .map(ChannelPairingService::toResponse)
            .toList();
    }

    @Transactional
    public PendingPairingResponse approve(UUID sessionId) {
        return toResponse(channelSessionService.approvePairing(sessionId));
    }

    @Transactional
    public PendingPairingResponse unpair(UUID sessionId) {
        return toResponse(channelSessionService.unpairSession(sessionId));
    }

    private static PendingPairingResponse toResponse(ChannelSession session) {
        return new PendingPairingResponse(
            session.getId(),
            session.getChannel(),
            mask(session.getExternalId()),
            session.getPairingCode(),
            session.getCreatedAt()
        );
    }

    private static String mask(String value) {
        if (value == null || value.length() < 4) {
            return "***";
        }
        return "***" + value.substring(value.length() - 4);
    }
}
