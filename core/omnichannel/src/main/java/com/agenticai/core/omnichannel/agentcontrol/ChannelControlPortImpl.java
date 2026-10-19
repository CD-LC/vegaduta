package com.agenticai.core.omnichannel.agentcontrol;

import com.agenticai.core.agents.tools.messaging.ChannelControlPort;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.status.ChannelStatusService;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * The {@code core/omnichannel} side of {@link ChannelControlPort} - see that interface's javadoc
 * for the boundary and for what is deliberately NOT exposed (credential connect/disconnect stays
 * human-only).
 *
 * <p>{@link #overview} deliberately delegates to {@link ChannelStatusService#status()} rather
 * than re-deriving the aggregate: that controller already encodes the platform's connectivity
 * semantics (Meta-beats-Twilio precedence, ACTIVE-only credential checks, paired-session counts). A
 * second copy of that logic would drift; calling the bean keeps the console card and the agent's
 * answer byte-identical by construction.
 */
@Component
public class ChannelControlPortImpl implements ChannelControlPort {

    private static final int MAX_SESSIONS = 50;

    private final ChannelStatusService statusController;
    private final ChannelSessionRepository sessionRepository;
    private final ChannelSessionService sessionService;

    public ChannelControlPortImpl(
            ChannelStatusService statusController,
            ChannelSessionRepository sessionRepository,
            ChannelSessionService sessionService) {
        this.statusController = statusController;
        this.sessionRepository = sessionRepository;
        this.sessionService = sessionService;
    }

    @Override
    public List<ChannelOverviewEntry> overview() {
        return statusController.status().channels().stream()
                .map(
                        c ->
                                new ChannelOverviewEntry(
                                        c.key(),
                                        c.label(),
                                        c.state(),
                                        c.detail(),
                                        c.sessionCount(),
                                        c.lastMessageAt(),
                                        c.defaultAgentId() == null
                                                ? null
                                                : c.defaultAgentId().toString()))
                .toList();
    }

    @Override
    public List<ChannelSessionInfo> sessions(String channel, int limit) {
        Optional<ChannelType> resolved = parseChannel(channel);
        if (channel != null && !channel.isBlank() && resolved.isEmpty()) {
            return List.of();
        }
        int cap = Math.max(1, Math.min(limit, MAX_SESSIONS));
        return sessionRepository
                .searchConversations(resolved.orElse(null), null, null, "", PageRequest.of(0, cap))
                .stream()
                .map(ChannelControlPortImpl::toInfo)
                .toList();
    }

    @Override
    public ChannelSessionActionResult actOnSession(
            String action, String sessionId, String agentId) {
        UUID id;
        try {
            id = UUID.fromString(sessionId);
        } catch (RuntimeException e) {
            return new ChannelSessionActionResult(false, "sessionId is not a UUID");
        }
        String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        try {
            return switch (normalized) {
                case "approve_pairing" -> {
                    ChannelSession session = sessionService.approvePairing(id);
                    yield new ChannelSessionActionResult(
                            true, "Pairing approved for " + session.getExternalId());
                }
                case "unpair" -> {
                    ChannelSession session = sessionService.unpairSession(id);
                    yield new ChannelSessionActionResult(
                            true,
                            "Session unpaired - new pairing code " + session.getPairingCode());
                }
                case "rebind_agent" -> {
                    if (agentId == null || agentId.isBlank()) {
                        yield new ChannelSessionActionResult(
                                false, "rebind_agent requires agentId");
                    }
                    sessionService.rebindAgent(id, UUID.fromString(agentId.trim()));
                    yield new ChannelSessionActionResult(
                            true, "Session rebound to agent " + agentId.trim());
                }
                default ->
                        new ChannelSessionActionResult(
                                false,
                                "Unknown action '"
                                        + action
                                        + "' - expected approve_pairing, unpair or rebind_agent");
            };
        } catch (RuntimeException e) {
            return new ChannelSessionActionResult(false, e.getMessage());
        }
    }

    private static ChannelSessionInfo toInfo(ChannelSession session) {
        return new ChannelSessionInfo(
                session.getId().toString(),
                session.getChannel().name().toLowerCase(Locale.ROOT),
                session.getExternalId(),
                session.isPaired(),
                session.getAgentId() == null ? null : session.getAgentId().toString(),
                session.getLastMessageAt(),
                session.getLastMessagePreview());
    }

    private static Optional<ChannelType> parseChannel(String channel) {
        if (channel == null) {
            return Optional.empty();
        }
        return Arrays.stream(ChannelType.values())
                .filter(type -> type.name().equalsIgnoreCase(channel.trim()))
                .findFirst();
    }
}
