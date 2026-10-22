package com.agenticai.core.omnichannel.teams;

import com.agenticai.core.approval.ApprovalEvaluation;
import com.agenticai.core.approval.HumanApprovalGateService;
import com.agenticai.core.omnichannel.ChannelPayloadSerializationException;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.SetDefaultAgentRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * Manual/API-triggered Teams send + default-agent - same posture as {@code TelegramSendController},
 * with one Bot Framework wrinkle the caller never sees: {@code to} is the conversation id, and the
 * companion {@code serviceUrl} is resolved from {@code teams_conversation_route} (written by every
 * inbound activity). A send to a conversation nobody has messaged yet is a hard error, not a guess
 * - Bot Framework has no global serviceUrl.
 */
@RestController
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
@RequestMapping("/api/channels/teams")
public class TeamsSendController {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final HumanApprovalGateService gateService;
    private final ChannelSessionService channelSessionService;
    private final TeamsCredentialService credentialService;

    public TeamsSendController(
            HumanApprovalGateService gateService,
            ChannelSessionService channelSessionService,
            TeamsCredentialService credentialService) {
        this.gateService = gateService;
        this.channelSessionService = channelSessionService;
        this.credentialService = credentialService;
    }

    @PostMapping("/send")
    public SendResult send(@Valid @RequestBody SendRequest request) {
        String serviceUrl =
                credentialService
                        .serviceUrlFor(request.to())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Unknown Teams conversation id - this platform only learns a conversation's serviceUrl "
                                                        + "once someone in it has messaged the bot at least once."));
        String payloadJson;
        try {
            payloadJson =
                    MAPPER.writeValueAsString(
                            new TeamsChannelAdapter.SendPayload(
                                    serviceUrl, request.to(), request.body(), null));
        } catch (Exception e) {
            throw new ChannelPayloadSerializationException(
                    "Teams", "Failed to serialize Teams send payload", e);
        }
        String requestedBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        ApprovalEvaluation evaluation =
                gateService.evaluate(
                        TeamsChannelAdapter.ACTION_TYPE,
                        "Send Teams message to " + request.to(),
                        payloadJson,
                        requestedBy);
        return new SendResult(evaluation.approved(), evaluation.pendingRequestId());
    }

    @PutMapping("/default-agent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefaultAgent(@Valid @RequestBody SetDefaultAgentRequest request) {
        channelSessionService.setDefaultAgent(ChannelType.TEAMS, request.agentId());
    }

    public record SendRequest(@NotBlank String to, @NotBlank String body) {}

    public record SendResult(boolean approved, java.util.UUID pendingRequestId) {}
}
