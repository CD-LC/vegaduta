package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.omnichannel.ChannelPayloadSerializationException;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.SetDefaultAgentRequest;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual/API-triggered Messenger and Instagram sends, plus each platform's default-agent setting -
 * the social counterpart to {@code WhatsAppSendController}, covering both platforms in one
 * controller since they share a credential and almost all of this logic (only the action type and
 * {@code ChannelType} differ per call).
 */
@RestController
@RequestMapping("/api/channels/social")
public class MetaSocialSendController {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final HumanApprovalGateService gateService;
    private final ChannelSessionService channelSessionService;

    public MetaSocialSendController(
            HumanApprovalGateService gateService, ChannelSessionService channelSessionService) {
        this.gateService = gateService;
        this.channelSessionService = channelSessionService;
    }

    @PostMapping("/messenger/send")
    public SendResult sendMessenger(@Valid @RequestBody SendRequest request) {
        return send(MetaMessengerChannelAdapter.ACTION_TYPE, "Messenger message", request);
    }

    @PostMapping("/instagram/send")
    public SendResult sendInstagram(@Valid @RequestBody SendRequest request) {
        return send(MetaInstagramChannelAdapter.ACTION_TYPE, "Instagram DM", request);
    }

    private SendResult send(String actionType, String label, SendRequest request) {
        String payloadJson;
        try {
            // Both adapters' SendPayload records happen to share the exact
            // same (to, body) shape, so a plain Map serializes identically
            // to either - avoids needing an if/else just to pick a record type.
            payloadJson =
                    MAPPER.writeValueAsString(
                            java.util.Map.of("to", request.to(), "body", request.body()));
        } catch (Exception e) {
            throw new ChannelPayloadSerializationException(
                    label, "Failed to serialize " + label + " send payload", e);
        }
        String requestedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        ApprovalEvaluation evaluation =
                gateService.evaluate(
                        actionType,
                        "Send " + label + " to " + request.to(),
                        payloadJson,
                        requestedBy);
        return new SendResult(evaluation.approved(), evaluation.pendingRequestId());
    }

    /**
     * Which agent handles inbound Messenger messages for the caller's own tenant - same posture as
     * {@code WhatsAppSendController#setDefaultAgent}.
     */
    @PutMapping("/messenger/default-agent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setMessengerDefaultAgent(@Valid @RequestBody SetDefaultAgentRequest request) {
        channelSessionService.setDefaultAgent(ChannelType.MESSENGER, request.agentId());
    }

    /** Which agent handles inbound Instagram DMs for the caller's own tenant. */
    @PutMapping("/instagram/default-agent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setInstagramDefaultAgent(@Valid @RequestBody SetDefaultAgentRequest request) {
        channelSessionService.setDefaultAgent(ChannelType.INSTAGRAM, request.agentId());
    }

    public record SendRequest(@NotBlank String to, @NotBlank String body) {}

    public record SendResult(boolean approved, java.util.UUID pendingRequestId) {}
}
