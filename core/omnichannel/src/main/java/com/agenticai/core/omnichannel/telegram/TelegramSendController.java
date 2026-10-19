package com.agenticai.core.omnichannel.telegram;

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
 * Manual/API-triggered Telegram send + default-agent - same posture as {@code
 * DiscordSendController}.
 */
@RestController
@RequestMapping("/api/channels/telegram")
public class TelegramSendController {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final HumanApprovalGateService gateService;
    private final ChannelSessionService channelSessionService;

    public TelegramSendController(
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
                            new TelegramChannelAdapter.SendPayload(request.to(), request.body()));
        } catch (Exception e) {
            throw new ChannelPayloadSerializationException(
                    "Telegram", "Failed to serialize Telegram send payload", e);
        }
        String requestedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        ApprovalEvaluation evaluation =
                gateService.evaluate(
                        TelegramChannelAdapter.ACTION_TYPE,
                        "Send Telegram message to " + request.to(),
                        payloadJson,
                        requestedBy);
        return new SendResult(evaluation.approved(), evaluation.pendingRequestId());
    }

    @PutMapping("/default-agent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefaultAgent(@Valid @RequestBody SetDefaultAgentRequest request) {
        channelSessionService.setDefaultAgent(ChannelType.TELEGRAM, request.agentId());
    }

    public record SendRequest(@NotBlank String to, @NotBlank String body) {}

    public record SendResult(boolean approved, java.util.UUID pendingRequestId) {}
}
