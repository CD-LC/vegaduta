package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelPayloadSerializationException;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.SetDefaultAgentRequest;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Manual/API-triggered Signal send + default agent - same posture as {@code TelegramSendController}. */
@RestController
@RequestMapping("/api/channels/signal")
public class SignalSendController {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final HumanApprovalGateService gateService;
    private final ChannelSessionService channelSessionService;

    public SignalSendController(HumanApprovalGateService gateService, ChannelSessionService channelSessionService) {
        this.gateService = gateService;
        this.channelSessionService = channelSessionService;
    }

    @PostMapping("/send")
    public SendResult send(@Valid @RequestBody SendRequest request) {
        if (!SignalAddress.isAddressable(request.to())) {
            throw new ChannelConfigurationException(
                "signal", "Send to a number like +4915112345678, a UUID or group:<id>.");
        }
        String payloadJson;
        try {
            payloadJson = MAPPER.writeValueAsString(new SignalChannelAdapter.SendPayload(request.to(), request.body()));
        } catch (Exception e) {
            throw new ChannelPayloadSerializationException("Signal", "Failed to serialize Signal send payload", e);
        }
        String requestedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        ApprovalEvaluation evaluation = gateService.evaluate(
            SignalChannelAdapter.ACTION_TYPE,
            "Send Signal message to " + SignalAddress.mask(request.to()),
            payloadJson,
            requestedBy);
        return new SendResult(evaluation.approved(), evaluation.pendingRequestId());
    }

    @PutMapping("/default-agent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefaultAgent(@Valid @RequestBody SetDefaultAgentRequest request) {
        channelSessionService.setDefaultAgent(ChannelType.SIGNAL, request.agentId());
    }

    public record SendRequest(@NotBlank String to, @NotBlank String body) {}

    public record SendResult(boolean approved, UUID pendingRequestId) {}
}
