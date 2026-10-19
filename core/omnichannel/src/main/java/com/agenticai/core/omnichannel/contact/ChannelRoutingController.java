package com.agenticai.core.omnichannel.contact;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.session.ChannelDefaultAgent;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundSafetyMode;
import com.agenticai.core.tenancy.ListPaging;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /admin/contacts "Channel routing" card (migration V238) - one read and one
 * write for BOTH halves of a channel's inbound mapping: which agent new
 * conversations default to, and whether a never-seen sender still has to be
 * approved by hand first ({@code autoApproveNewSenders}) or goes straight to
 * that agent. The legacy {@code PUT /api/channels/<channel>/default-agent}
 * endpoints stay untouched - this is the combined surface the new page
 * drives, so the two halves can never drift out of sync in the UI.
 *
 * <p>Read stays on plain {@code authenticated()} (same posture as GET
 * /api/channels/status); the PUT is {@code ROLE_tenant-admin} in
 * SecurityConfig - turning on auto-approve means "every stranger on the
 * internet gets a live agent conversation", strictly a bigger grant than the
 * default-agent writes alone.
 */
@RestController
@RequestMapping("/api/channels/routing")
public class ChannelRoutingController {

    private final ChannelSessionService sessionService;
    private final AgentRepository agentRepository;

    public ChannelRoutingController(ChannelSessionService sessionService, AgentRepository agentRepository) {
        this.sessionService = sessionService;
        this.agentRepository = agentRepository;
    }

    @GetMapping
    public ResponseEntity<List<RoutingResponse>> list(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            List.of(ChannelType.values()).stream()
            .map(channel -> sessionService.routingFor(channel)
                .map(row -> toResponse(channel, row))
                .orElseGet(() -> new RoutingResponse(
                    channel.name().toLowerCase(java.util.Locale.ROOT), null, null, false, InboundSafetyMode.FLAG.name(),
                    null, false)))
            .toList(),
            limit, offset, 50, 500);
    }

    @PutMapping("/{channel}")
    public RoutingResponse set(@PathVariable String channel, @Valid @RequestBody RoutingRequest request) {
        ChannelType type = ChannelContactController.parseChannel(channel);
        if (type == ChannelType.WHATSAPP && request.agentId() != null
            && !request.agentId().equals(
                sessionService.routingFor(type).map(ChannelDefaultAgent::getAgentId).orElse(null))) {
            // Attaching or changing the agent on a WhatsApp number needs a declared task and the
            // open-ended check, which PUT /api/channels/whatsapp/default-agent performs
            // (docs/channels/whatsapp-policy.md). Mode flips with the current agent are fine.
            throw new ChannelConfigurationException("whatsapp",
                "Change the WhatsApp agent from the Channels page: it needs a declared task.");
        }
        ChannelDefaultAgent saved = sessionService.setRouting(
            type, request.agentId(), request.autoApproveNewSenders(), request.inboundSafetyMode());
        return toResponse(type, saved);
    }

    private RoutingResponse toResponse(ChannelType channel, ChannelDefaultAgent row) {
        String agentName = agentRepository.findById(row.getAgentId()).map(a -> a.getName()).orElse(null);
        return new RoutingResponse(
            channel.name().toLowerCase(java.util.Locale.ROOT), row.getAgentId(), agentName,
            row.isAutoApproveNewSenders(), row.getInboundSafetyMode(),
            row.getDeclaredTask(), row.isOpenEndedOverride());
    }

    /** {@code inboundSafetyMode} (V242) is optional - omitted keeps the stored mode. */
    public record RoutingRequest(UUID agentId, @NotNull Boolean autoApproveNewSenders, String inboundSafetyMode) {
    }

    public record RoutingResponse(
        String channel, UUID defaultAgentId, String defaultAgentName,
        boolean autoApproveNewSenders, String inboundSafetyMode,
        String declaredTask, boolean openEndedOverride
    ) {
    }
}
