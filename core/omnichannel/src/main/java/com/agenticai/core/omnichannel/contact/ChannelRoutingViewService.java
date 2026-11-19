package com.agenticai.core.omnichannel.contact;

import com.agenticai.core.agents.core.AgentLookupService;
import com.agenticai.core.omnichannel.contact.ChannelRoutingController.RoutingResponse;
import com.agenticai.core.omnichannel.session.ChannelDefaultAgent;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-facing facade for the channel routing card: wraps {@link ChannelSessionService} routing
 * rows and the agent-name lookup, and maps to {@link RoutingResponse} so the controller never
 * sees {@link ChannelDefaultAgent} or {@link AgentRepository}.
 */
@Service
public class ChannelRoutingViewService {

    private final ChannelSessionService sessionService;
    private final AgentLookupService agentLookup;

    public ChannelRoutingViewService(
            ChannelSessionService sessionService, AgentLookupService agentLookup) {
        this.sessionService = sessionService;
        this.agentLookup = agentLookup;
    }

    @Transactional(readOnly = true)
    public Optional<RoutingResponse> routingFor(ChannelType channel) {
        return sessionService.routingFor(channel).map(row -> toResponse(channel, row));
    }

    @Transactional(readOnly = true)
    public UUID currentAgentId(ChannelType channel) {
        return sessionService.routingFor(channel).map(ChannelDefaultAgent::getAgentId).orElse(null);
    }

    @Transactional
    public RoutingResponse setRouting(
            ChannelType channel, UUID agentId, boolean autoApproveNewSenders, String inboundSafetyMode) {
        ChannelDefaultAgent saved =
                sessionService.setRouting(channel, agentId, autoApproveNewSenders, inboundSafetyMode);
        return toResponse(channel, saved);
    }

    private RoutingResponse toResponse(ChannelType channel, ChannelDefaultAgent row) {
        String agentName = agentLookup.nameOf(row.getAgentId()).orElse(null);
        return new RoutingResponse(
            channel.name().toLowerCase(Locale.ROOT), row.getAgentId(), agentName,
            row.isAutoApproveNewSenders(), row.getInboundSafetyMode(),
            row.getDeclaredTask(), row.isOpenEndedOverride());
    }
}
