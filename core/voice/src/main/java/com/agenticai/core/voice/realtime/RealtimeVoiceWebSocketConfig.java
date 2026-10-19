package com.agenticai.core.voice.realtime;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.providers.ProviderCredentialResolver;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers {@link RealtimeVoiceWebSocketHandler} at {@code
 * /webhook/voice/realtime} - the {@code /webhook/**} placement mirrors the
 * Twilio relay exactly ({@code permitAll} handshake, ticket-based auth
 * inside the setup frame). {@code setAllowedOrigins("*")} is required for
 * the same reason as Twilio's relay config documents: the connecting party
 * is not a same-origin page, and the real trust boundary is the ticket.
 * {@code @EnableWebSocket} is already on the omnichannel relay config; this
 * module only adds a handler mapping to the same registry.
 *
 * <p>Unlike Twilio's relay, this one IS opened by a browser page (the console), so the
 * handshake's {@code Origin} is checked: only {@code app.voice.realtime.allowed-origins}
 * (default: the console origins already listed in {@code app.web.allowed-origins}, plus
 * same-origin) may open it. The ticket stays the credential; the origin check stops a third-party
 * page from driving a signed-in user's browser into opening sessions. {@code *} restores the old
 * allow-all behaviour for a deployment whose console origin is not listed.
 */
@Configuration
public class RealtimeVoiceWebSocketConfig implements WebSocketConfigurer {

    private final RealtimeVoiceTicketService ticketService;
    private final ProviderCredentialResolver credentialResolver;
    private final AgentRepository agentRepository;
    private final List<RealtimeVoiceProvider> providers;
    private final String[] allowedOrigins;

    public RealtimeVoiceWebSocketConfig(
        RealtimeVoiceTicketService ticketService,
        ProviderCredentialResolver credentialResolver,
        AgentRepository agentRepository,
        List<RealtimeVoiceProvider> providers,
        @Value("${app.voice.realtime.allowed-origins:${app.web.allowed-origins:}}") String allowedOrigins
    ) {
        this.ticketService = ticketService;
        this.credentialResolver = credentialResolver;
        this.agentRepository = agentRepository;
        this.providers = providers;
        this.allowedOrigins = parseOrigins(allowedOrigins);
    }

    /** Comma-separated origins, blanks dropped. Empty means same-origin only. */
    static String[] parseOrigins(String csv) {
        if (csv == null) {
            return new String[0];
        }
        return Arrays.stream(csv.split(","))
            .map(String::strip)
            .filter(o -> !o.isEmpty())
            .toArray(String[]::new);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(realtimeVoiceWebSocketHandler(), RealtimeVoiceSessionController.RELAY_PATH)
            .setAllowedOrigins(allowedOrigins);
    }

    @Bean
    public RealtimeVoiceWebSocketHandler realtimeVoiceWebSocketHandler() {
        return new RealtimeVoiceWebSocketHandler(
            ticketService, credentialResolver, agentRepository, providers);
    }
}
