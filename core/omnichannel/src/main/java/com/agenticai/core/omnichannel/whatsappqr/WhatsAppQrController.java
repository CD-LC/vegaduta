package com.agenticai.core.omnichannel.whatsappqr;

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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's WhatsApp QR card: start/restart pairing (returns the current QR payload for the
 * scan), read live status while waiting, unlink, send, and the channel's default agent.
 *
 * <p>Unlike every sibling channel there is no credential form: linking IS the pairing scan, and
 * the only durable identity is the bridge session key - never shown here. The QR payload in the
 * response completes a device link as this tenant's number, so it is returned only on this
 * authenticated, admin-gated surface and only while pairing is open.
 */
@RestController
@RequestMapping("/api/channels/whatsapp-qr")
public class WhatsAppQrController {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final WhatsAppQrService service;
    private final HumanApprovalGateService gateService;
    private final ChannelSessionService channelSessionService;

    public WhatsAppQrController(
            WhatsAppQrService service,
            HumanApprovalGateService gateService,
            ChannelSessionService channelSessionService) {
        this.service = service;
        this.gateService = gateService;
        this.channelSessionService = channelSessionService;
    }

    /** Start or restart pairing. "Link a different number" is the same call - it replaces the session. */
    @PostMapping("/session")
    public WhatsAppQrStatusResponse connect() {
        return service.connect();
    }

    /** 204 until the tenant has ever linked; the live QR rides in the body while PAIRING. */
    @GetMapping("/session")
    public ResponseEntity<WhatsAppQrStatusResponse> status() {
        return service.status()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** "Unlink this number" - logs the bridge session out too. Idempotent, 204 either way. */
    @DeleteMapping("/session")
    public ResponseEntity<Void> disconnect() {
        service.disconnect();
        return ResponseEntity.noContent().build();
    }

    /** Manual/API-triggered send - same posture as {@code SignalSendController}. */
    @PostMapping("/send")
    public SendResult send(@Valid @RequestBody SendRequest request) {
        if (!WhatsAppQrAddress.isAddressable(request.to())) {
            throw new ChannelConfigurationException(
                    "whatsapp-qr",
                    "Send to a number like 4915112345678, or a stored <digits>@lid recipient.");
        }
        String payloadJson;
        try {
            payloadJson =
                    MAPPER.writeValueAsString(
                            new WhatsAppQrChannelAdapter.SendPayload(request.to(), request.body()));
        } catch (Exception e) {
            throw new ChannelPayloadSerializationException(
                    "WhatsApp QR", "Failed to serialize WhatsApp QR send payload", e);
        }
        String requestedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        ApprovalEvaluation evaluation =
                gateService.evaluate(
                        WhatsAppQrChannelAdapter.ACTION_TYPE,
                        "Send WhatsApp QR message to " + WhatsAppQrAddress.mask(request.to()),
                        payloadJson,
                        requestedBy);
        return new SendResult(evaluation.approved(), evaluation.pendingRequestId());
    }

    @PutMapping("/default-agent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefaultAgent(@Valid @RequestBody SetDefaultAgentRequest request) {
        channelSessionService.setDefaultAgent(ChannelType.WHATSAPP_QR, request.agentId());
    }

    public record SendRequest(@NotBlank String to, @NotBlank String body) {}

    public record SendResult(boolean approved, UUID pendingRequestId) {}
}
