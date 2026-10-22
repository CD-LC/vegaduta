package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.omnichannel.ChannelPayloadSerializationException;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.SetDefaultAgentRequest;
import com.agenticai.core.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Manual/API-triggered iMessage send and the channel's default agent - same posture as {@code
 * TeamsSendController}: the send goes through the approval gate, never straight to the Mac. {@code
 * to} is a chat GUID, or a phone number / email for a direct chat.
 */
@RestController
@RequestMapping("/api/channels/imessage")
public class ImessageSendController {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final HumanApprovalGateService gateService;
    private final ChannelSessionService channelSessionService;

    public ImessageSendController(
            HumanApprovalGateService gateService, ChannelSessionService channelSessionService) {
        this.gateService = gateService;
        this.channelSessionService = channelSessionService;
    }

    @PostMapping("/send")
    public SendResult send(@Valid @RequestBody SendRequest request) {
        String payloadJson;
        try {
            payloadJson =
                    MAPPER.writeValueAsString(
                            new ImessageChannelAdapter.SendPayload(request.to(), request.body()));
        } catch (Exception e) {
            throw new ChannelPayloadSerializationException(
                    "iMessage", "Failed to serialize iMessage send payload", e);
        }
        String requestedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        ApprovalEvaluation evaluation =
                gateService.evaluate(
                        ImessageChannelAdapter.ACTION_TYPE,
                        "Send iMessage to " + request.to(),
                        payloadJson,
                        requestedBy);
        return new SendResult(evaluation.approved(), evaluation.pendingRequestId());
    }

    @PutMapping("/default-agent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefaultAgent(@Valid @RequestBody SetDefaultAgentRequest request) {
        channelSessionService.setDefaultAgent(ChannelType.IMESSAGE, request.agentId());
    }

    public record SendRequest(
            @NotBlank @Size(max = 320) String to, @NotBlank @Size(max = 8000) String body) {}

    public record SendResult(boolean approved, java.util.UUID pendingRequestId) {}
}
