package com.agenticai.core.voice.realtime;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.sharing.PermissionLevel;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session-create endpoint for native speech-to-speech voice (P2 item: Gemini Live / OpenAI Realtime
 * path alongside STT&rarr;LLM&rarr;TTS).
 *
 * <p>Flow: the authenticated client POSTs {@code {agentId, provider}} here; we resolve the tenant's
 * provider credential (BYOK first, same {@link ProviderCredentialResolver} every chat call uses -
 * so a tenant with only an OpenAI key can't open a Gemini session and vice versa), mint a {@link
 * RealtimeVoiceTicketService} ticket, and hand back the relay path + audio rates. The client then
 * opens {@code wss://<host>/webhook/voice/ realtime} and sends the ticket in its setup frame; the
 * relay dials the provider upstream. The API key never reaches the browser.
 *
 * <p><b>Quota note:</b> realtime sessions bill against the tenant's OWN provider key (BYOK) - there
 * is no platform-subsidised realtime spend, so this path does not consume {@code
 * VoiceQuotaService}'s minute pool, which exists for the platform-keyed Sarvam/ElevenLabs pipeline.
 */
@ConditionalOnEditionFeature(EditionFeature.VOICE_REALTIME_SHARE)
@RestController
@RequestMapping("/api/voice/realtime")
public class RealtimeVoiceSessionController {

    /**
     * Public relay path - mirrors the Twilio relay's /webhook/** placement (permitAll + ticket
     * auth).
     */
    public static final String RELAY_PATH = "/webhook/voice/realtime";

    private final AgentService agentService;
    private final ProviderCredentialResolver credentialResolver;
    private final RealtimeVoiceTicketService ticketService;

    public RealtimeVoiceSessionController(
            AgentService agentService,
            ProviderCredentialResolver credentialResolver,
            RealtimeVoiceTicketService ticketService) {
        this.agentService = agentService;
        this.credentialResolver = credentialResolver;
        this.ticketService = ticketService;
    }

    @PostMapping("/sessions")
    public ResponseEntity<RealtimeSessionResponse> createSession(
            @Valid @RequestBody RealtimeSessionRequest request) {
        RealtimeProvider provider = RealtimeProvider.parse(request.provider());
        if (provider == null) {
            throw new IllegalArgumentException(
                    "Unknown realtime provider '"
                            + request.provider()
                            + "' - expected gemini-live or openai-realtime");
        }
        Agent agent = agentService.requireAccess(request.agentId(), PermissionLevel.VIEWER);

        // Fail fast here (400/404 to the API caller) rather than at socket
        // connect, where a missing key would surface as a bare WS close.
        credentialResolver.resolveActiveKey(providerNameFor(provider));

        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse("default");
        String subject = TenantContext.current().map(TenantContext::getSubject).orElse("unknown");
        String ticket = ticketService.mint(tenantId, agent.getId(), provider, subject);

        String model =
                request.model() != null && !request.model().isBlank()
                        ? request.model()
                        : (provider == RealtimeProvider.GEMINI_LIVE
                                ? GeminiLiveRealtimeProvider.DEFAULT_MODEL
                                : OpenAiRealtimeProvider.DEFAULT_MODEL);

        return ResponseEntity.ok(
                new RealtimeSessionResponse(
                        RELAY_PATH,
                        ticket,
                        provider.name(),
                        model,
                        provider == RealtimeProvider.GEMINI_LIVE
                                ? GeminiLiveRealtimeProvider.INPUT_SAMPLE_RATE
                                : OpenAiRealtimeProvider.SAMPLE_RATE,
                        provider == RealtimeProvider.GEMINI_LIVE
                                ? GeminiLiveRealtimeProvider.OUTPUT_SAMPLE_RATE
                                : OpenAiRealtimeProvider.SAMPLE_RATE));
    }

    static ProviderName providerNameFor(RealtimeProvider provider) {
        return provider == RealtimeProvider.GEMINI_LIVE ? ProviderName.GEMINI : ProviderName.OPENAI;
    }
}
