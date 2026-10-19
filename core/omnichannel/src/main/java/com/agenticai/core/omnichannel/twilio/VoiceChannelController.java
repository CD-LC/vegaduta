package com.agenticai.core.omnichannel.twilio;

import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
import com.agenticai.core.tenancy.ListPaging;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.SetDefaultAgentRequest;
import com.agenticai.core.omnichannel.twilio.dto.VoiceDtmfMappingResponse;
import com.agenticai.core.omnichannel.twilio.dto.VoiceDtmfMappingUpsertRequest;
import com.agenticai.core.omnichannel.twilio.dto.VoiceNumberAgentResponse;
import com.agenticai.core.omnichannel.twilio.dto.VoiceNumberAgentUpsertRequest;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Master Roadmap 2026 item #17 (real-time voice agents) - the {@code /api/channels/voice/**}
 * admin-config surface, split out from {@link TwilioVoiceWebhookController} (which stays {@code
 * /webhook/**}, {@code permitAll()}) since this is a real tenant-admin configuration change, same
 * posture as {@link WhatsAppSendController#setDefaultAgent}. There is no {@code POST /send} here
 * (see {@link TwilioVoiceWebhookController}'s javadoc for why VOICE has no outbound-send concept)
 * and no credential endpoint - VOICE reuses the tenant's existing {@link TwilioCredential} (same
 * Account SID/Auth Token that already authenticates WhatsApp), there is nothing separate to
 * activate.
 *
 * <p>{@code /numbers/**} - per-number -&gt; per-agent inbound routing, so a tenant with several
 * Twilio numbers can answer each one with a different agent instead of funnelling all of them into
 * the single {@code /default-agent} above. See {@link VoiceNumberAgent}'s javadoc for why that is a
 * per-call override rather than a {@code ChannelSession} rebind, and for the DTMF &gt; number &gt;
 * tenant-default precedence order.
 *
 * <p>{@code /dtmf-menu/**} - Master Roadmap 2026 item #17 fast-follow (DTMF handling): CRUD over
 * the per-digit -&gt; agent menu {@link VoiceRelayWebSocketHandler} reads mid-call. See {@link
 * VoiceDtmfMapping}'s javadoc for the full design (per-call agent switch, never a change to this
 * number's default agent).
 */
@RestController
@RequestMapping("/api/channels/voice")
public class VoiceChannelController {

    private final ChannelSessionService channelSessionService;
    private final VoiceDtmfMappingService voiceDtmfMappingService;
    private final VoiceNumberAgentService voiceNumberAgentService;
    private final TenantLimits tenantLimits;

    public VoiceChannelController(
            ChannelSessionService channelSessionService,
            VoiceDtmfMappingService voiceDtmfMappingService,
            VoiceNumberAgentService voiceNumberAgentService,
            TenantLimits tenantLimits) {
        this.channelSessionService = channelSessionService;
        this.voiceDtmfMappingService = voiceDtmfMappingService;
        this.voiceNumberAgentService = voiceNumberAgentService;
        this.tenantLimits = tenantLimits;
    }

    /**
     * Voice is a paid-tier channel surface (plan_limits.external_channels_allowed) - the Twilio
     * credential activation is already gated, but this config surface stays callable on its own, so
     * its writes carry the same gate. Reads and deletes stay open: teardown is always safe, a
     * tenant whose plan dropped must be able to unwire, not be locked in.
     */
    private void requireExternalChannels() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !tenantLimits.externalChannelsAllowed(tenantId)) {
            throw new TenantLimitExceededException(
                    "Your plan does not include external channel connections - upgrade your plan to configure the voice channel.");
        }
    }

    @PutMapping("/default-agent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setDefaultAgent(@Valid @RequestBody SetDefaultAgentRequest request) {
        requireExternalChannels();
        channelSessionService.setDefaultAgent(ChannelType.VOICE, request.agentId());
    }

    /**
     * The dialed number is carried as a path variable for symmetry with {@code /dtmf-menu/{digit}}.
     * A literal {@code +} is legal in a path segment (only query strings decode it as a space), and
     * {@link VoiceNumberAgentService#normalizeNumber} accepts it with or without the usual
     * separators either way.
     */
    @GetMapping("/numbers")
    public ResponseEntity<List<VoiceNumberAgentResponse>> listNumbers(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            voiceNumberAgentService.list(),
            limit, offset, 50, 500);
    }

    @PutMapping("/numbers/{number}")
    public VoiceNumberAgentResponse upsertNumber(
            @PathVariable String number,
            @Valid @RequestBody VoiceNumberAgentUpsertRequest request) {
        requireExternalChannels();
        return voiceNumberAgentService.upsert(number, request.agentId(), request.label());
    }

    @DeleteMapping("/numbers/{number}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNumber(@PathVariable String number) {
        voiceNumberAgentService.delete(number);
    }

    @GetMapping("/dtmf-menu")
    public ResponseEntity<List<VoiceDtmfMappingResponse>> listDtmfMenu(@RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            voiceDtmfMappingService.list(),
            limit, offset, 50, 500);
    }

    @PutMapping("/dtmf-menu/{digit}")
    public VoiceDtmfMappingResponse upsertDtmfMenuEntry(
            @PathVariable String digit, @Valid @RequestBody VoiceDtmfMappingUpsertRequest request) {
        requireExternalChannels();
        return voiceDtmfMappingService.upsert(digit, request.agentId(), request.label());
    }

    @DeleteMapping("/dtmf-menu/{digit}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDtmfMenuEntry(@PathVariable String digit) {
        voiceDtmfMappingService.delete(digit);
    }
}
