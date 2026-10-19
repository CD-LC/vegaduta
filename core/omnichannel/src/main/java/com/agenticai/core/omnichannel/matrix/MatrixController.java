package com.agenticai.core.omnichannel.matrix;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.omnichannel.ChannelPayloadSerializationException;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.SetDefaultAgentRequest;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
 * The Matrix channel's console API: connect/inspect/disconnect the bot, change its sender and
 * invite policy, send a test message (through the approval gate, like every channel) and pick the
 * default agent. All of it is gated by {@code PERM_MANAGE_CHANNELS} in {@code
 * ToolChannelWorkflowRules}.
 */
@RestController
@RequestMapping("/api/channels/matrix")
public class MatrixController {

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private final MatrixCredentialService credentialService;
    private final HumanApprovalGateService gateService;
    private final ChannelSessionService channelSessionService;

    public MatrixController(
            MatrixCredentialService credentialService,
            HumanApprovalGateService gateService,
            ChannelSessionService channelSessionService) {
        this.credentialService = credentialService;
        this.gateService = gateService;
        this.channelSessionService = channelSessionService;
    }

    @PostMapping("/credential")
    public ResponseEntity<MatrixCredentialResponse> activate(
            @Valid @RequestBody MatrixActivationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.activate(request));
    }

    @GetMapping("/credential")
    public ResponseEntity<MatrixCredentialResponse> current() {
        return credentialService
                .current()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Idempotent: 204 whether or not anything was connected. */
    @DeleteMapping("/credential")
    public ResponseEntity<Void> disconnect() {
        credentialService.disconnect();
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/policy")
    public MatrixCredentialResponse updatePolicy(@Valid @RequestBody MatrixPolicyRequest request) {
        return credentialService.updatePolicy(request);
    }

    @PostMapping("/send")
    public SendResult send(@Valid @RequestBody SendRequest request) {
        String payloadJson;
        try {
            payloadJson =
                    MAPPER.writeValueAsString(
                            new MatrixChannelAdapter.SendPayload(
                                    request.to(), request.body(), null, null));
        } catch (Exception e) {
            throw new ChannelPayloadSerializationException(
                    "Matrix", "Failed to serialize Matrix send payload", e);
        }
        String requestedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        ApprovalEvaluation evaluation =
                gateService.evaluate(
                        MatrixChannelAdapter.ACTION_TYPE,
                        "Send Matrix message to " + request.to(),
                        payloadJson,
                        requestedBy);
        return new SendResult(evaluation.approved(), evaluation.pendingRequestId());
    }

    @PutMapping("/default-agent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefaultAgent(@Valid @RequestBody SetDefaultAgentRequest request) {
        channelSessionService.setDefaultAgent(ChannelType.MATRIX, request.agentId());
    }

    /** {@code to} is a room id ({@code !abc:example.org}). */
    public record SendRequest(
            @NotBlank @Size(max = 255) String to, @NotBlank @Size(max = 64_000) String body) {}

    public record SendResult(boolean approved, UUID pendingRequestId) {}
}
