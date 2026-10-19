package com.agenticai.core.omnichannel.email;

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
 * Manual/API-triggered email send + default-agent - same posture as {@code TelegramSendController}.
 */
@RestController
@RequestMapping("/api/channels/email")
public class EmailSendController {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final HumanApprovalGateService gateService;
    private final ChannelSessionService channelSessionService;

    public EmailSendController(
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
                            new EmailChannelAdapter.SendPayload(
                                    request.to(), request.subject(), request.body()));
        } catch (Exception e) {
            throw new ChannelPayloadSerializationException(
                    "Email", "Failed to serialize email send payload", e);
        }
        String requestedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        ApprovalEvaluation evaluation =
                gateService.evaluate(
                        EmailChannelAdapter.ACTION_TYPE,
                        "Send email to " + request.to(),
                        payloadJson,
                        requestedBy);
        return new SendResult(evaluation.approved(), evaluation.pendingRequestId());
    }

    @PutMapping("/default-agent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefaultAgent(@Valid @RequestBody SetDefaultAgentRequest request) {
        channelSessionService.setDefaultAgent(ChannelType.EMAIL, request.agentId());
    }

    public record SendRequest(
            @NotBlank String to, @NotBlank String subject, @NotBlank String body) {}

    public record SendResult(boolean approved, java.util.UUID pendingRequestId) {}
}
