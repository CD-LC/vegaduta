package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.ChannelPayloadSerializationException;
import com.agenticai.core.omnichannel.whatsapp.policy.WhatsAppBindingPolicy;
import com.agenticai.core.omnichannel.whatsapp.policy.WhatsAppBindingResponses;
import com.agenticai.core.omnichannel.twilio.dto.WhatsAppSendRequest;
import com.agenticai.core.omnichannel.twilio.dto.WhatsAppSendResult;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual/API-triggered WhatsApp send - lets Stage 2 be exercised and verified end-to-end before
 * Stage 5's auto-reply engine or an agent tool exists to trigger a send on its own. Every call here
 * goes through {@link HumanApprovalGateService#evaluate} first, same as any future caller (a
 * campaign, an agent tool) will - this controller has no special bypass.
 */
@RestController
@RequestMapping("/api/channels/whatsapp")
public class WhatsAppSendController {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final HumanApprovalGateService gateService;
    private final WhatsAppBindingPolicy bindingPolicy;

    public WhatsAppSendController(
            HumanApprovalGateService gateService, WhatsAppBindingPolicy bindingPolicy) {
        this.gateService = gateService;
        this.bindingPolicy = bindingPolicy;
    }

    @PostMapping("/send")
    public WhatsAppSendResult send(@Valid @RequestBody WhatsAppSendRequest request) {
        String payloadJson;
        try {
            payloadJson =
                    MAPPER.writeValueAsString(
                            new TwilioWhatsAppChannelAdapter.SendPayload(
                                    request.to(), request.message()));
        } catch (Exception e) {
            throw new ChannelPayloadSerializationException(
                    "WhatsApp", "Failed to serialize WhatsApp send payload", e);
        }
        String requestedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        String summary = "Send WhatsApp message to " + request.to();

        ApprovalEvaluation evaluation =
                gateService.evaluate(
                        TwilioWhatsAppChannelAdapter.ACTION_TYPE,
                        summary,
                        payloadJson,
                        requestedBy);
        return new WhatsAppSendResult(evaluation.approved(), evaluation.pendingRequestId());
    }

    /**
     * Which agent handles inbound WhatsApp messages for the caller's own tenant - resolved from
     * whichever schema {@link TenantContext} points at, so each tenant sets its own independently
     * (see {@link ChannelSessionService}'s javadoc for why this replaced a single global env var).
     * Gated to {@code ROLE_tenant-admin} in {@code SecurityConfig} - this is a real configuration
     * change, not a read.
     */
    @PutMapping("/default-agent")
    public ResponseEntity<Map<String, Object>> setDefaultAgent(
            @Valid @RequestBody WhatsAppDefaultAgentRequest request) {
        return WhatsAppBindingResponses.toResponse(
                bindingPolicy.bindDefault(
                        request.agentId(), request.task(), request.acknowledgeOpenEnded()));
    }

    /**
     * {@code task} is required (docs/channels/whatsapp-policy.md); {@code acknowledgeOpenEnded}
     * proceeds past the open-ended-agent warning and is recorded as an override.
     */
    public record WhatsAppDefaultAgentRequest(
            @NotNull UUID agentId, String task, boolean acknowledgeOpenEnded) {}
}
