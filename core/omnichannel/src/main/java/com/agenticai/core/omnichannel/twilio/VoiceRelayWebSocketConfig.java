package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.omnichannel.session.ChannelSessionService;
import com.agenticai.core.tenancy.TenantResolutionService;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers {@link VoiceRelayWebSocketHandler} at {@code
 * /webhook/twilio/voice-relay} (Master Roadmap 2026 item #17) - deliberately
 * under {@code /webhook/**} so it inherits the same {@code permitAll()}
 * Spring Security rule every other inbound channel webhook already uses
 * (see {@code SecurityConfig}'s comment on that matcher), rather than
 * needing a new security rule of its own.
 *
 * <p>{@code setAllowedOrigins("*")}: the caller here is Twilio's own server
 * infrastructure opening a WebSocket per {@code <ConversationRelay url="wss://...">},
 * not a browser page - there is no browser-origin trust boundary to enforce.
 * The real authentication happens inside {@link VoiceRelayWebSocketHandler}:
 * the {@code setup} message must carry a {@link VoiceRelayTicketService}
 * ticket minted by the signature-verified inbound-call webhook. (Until the
 * 2026-09-18 security audit it was only "the accountSid resolves to a tenant",
 * which authenticates nobody - an AccountSid is not a secret.)
 *
 * <p>Since the 2026-10 hardening sweep the literal {@code "*"} is gone: Twilio's servers send no
 * {@code Origin}, which the default check accepts, while a browser page (which always sends one)
 * is refused unless {@code app.voice.relay.allowed-origins} lists it. Set that to {@code *} only
 * if a proxy in front of core attaches an {@code Origin} to Twilio's connection.
 */
@Configuration
@EnableWebSocket
public class VoiceRelayWebSocketConfig implements WebSocketConfigurer {

    private final TenantResolutionService tenantResolutionService;
    private final ChannelSessionService channelSessionService;
    private final AgentChatService agentChatService;
    private final VoiceDtmfMappingService voiceDtmfMappingService;
    private final VoiceNumberAgentService voiceNumberAgentService;
    private final VoiceRelayTicketService voiceRelayTicketService;
    private final String[] allowedOrigins;

    public VoiceRelayWebSocketConfig(
        TenantResolutionService tenantResolutionService,
        ChannelSessionService channelSessionService,
        AgentChatService agentChatService,
        VoiceDtmfMappingService voiceDtmfMappingService,
        VoiceNumberAgentService voiceNumberAgentService,
        VoiceRelayTicketService voiceRelayTicketService,
        @Value("${app.voice.relay.allowed-origins:}") String allowedOrigins
    ) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
            .map(String::strip)
            .filter(o -> !o.isEmpty())
            .toArray(String[]::new);
        this.voiceRelayTicketService = voiceRelayTicketService;
        this.tenantResolutionService = tenantResolutionService;
        this.channelSessionService = channelSessionService;
        this.agentChatService = agentChatService;
        this.voiceDtmfMappingService = voiceDtmfMappingService;
        this.voiceNumberAgentService = voiceNumberAgentService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceRelayWebSocketHandler(), "/webhook/twilio/voice-relay").setAllowedOrigins(allowedOrigins);
    }

    @Bean
    public VoiceRelayWebSocketHandler voiceRelayWebSocketHandler() {
        return new VoiceRelayWebSocketHandler(
            tenantResolutionService, channelSessionService, agentChatService, voiceDtmfMappingService,
            voiceNumberAgentService, voiceRelayTicketService
        );
    }
}
