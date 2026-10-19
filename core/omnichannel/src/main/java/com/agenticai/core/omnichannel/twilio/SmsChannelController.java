package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.SetDefaultAgentRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plain-SMS channel configuration (2026-10-14): which agent answers an inbound SMS. Same posture as
 * {@link VoiceChannelController} - there is nothing separate to activate, the tenant's existing
 * Twilio credential (and its number) carries SMS as well as WhatsApp and voice. Replies are never
 * sent from here; the webhook ({@link TwilioSmsWebhookController}) generates them and routes them
 * through the approval gate as {@link TwilioSmsChannelAdapter#ACTION_TYPE}. Gated to {@code
 * PERM_MANAGE_CHANNELS} in {@code ToolChannelWorkflowRules}.
 */
@RestController
@RequestMapping("/api/channels/sms")
public class SmsChannelController {

    private final ChannelSessionService channelSessionService;

    public SmsChannelController(ChannelSessionService channelSessionService) {
        this.channelSessionService = channelSessionService;
    }

    @PutMapping("/default-agent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefaultAgent(@Valid @RequestBody SetDefaultAgentRequest request) {
        channelSessionService.setDefaultAgent(ChannelType.SMS, request.agentId());
    }
}
