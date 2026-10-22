package com.agenticai.core.omnichannel.demo;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.InboundMessageDedupService;
import com.agenticai.core.omnichannel.session.TurnContextProbe;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.agenticai.core.tenancy.demo.DemoChannelLink;
import com.agenticai.core.tenancy.demo.DemoChannelLinkService;
import com.agenticai.core.tenancy.demo.DemoChannelRateLimiter;
import com.agenticai.core.tenancy.demo.DemoChannelType;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import com.agenticai.core.tenancy.demo.DemoWhatsAppPhoneGuard;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0022 stage 4: the demo Telegram and WhatsApp bots run the agent turn inside {@code
 * DemoSessionService#inDemoTenantContext}, which puts the demo tenant on the thread. The {@code
 * TurnContext} they hand to the chat turn must be built INSIDE that scope, so it carries the demo
 * tenant (the very ThreadLocal instance), not the webhook thread's tenant-less state.
 *
 * <p>The mocked {@code inDemoTenantContext} here sets a known tenant around the supplier and
 * restores the thread afterwards, as the real one does.
 */
class DemoWebhooksTurnContextTest {

    private static final String FROM = "919812345678";
    private static final String CHAT_ID = "4242";
    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    private final TenantContext demoTenant =
            TenantContext.of("demo_sandbox", "demo:session", Set.of());

    private DemoSessionService sessions;
    private DemoChannelLinkService links;
    private DemoChannelRateLimiter limiter;
    private InboundMessageDedupService dedup;
    private AgentChatService chat;
    private TurnContextProbe probe;
    private UUID sessionId;
    private DemoChannelLink link;

    @BeforeEach
    void setUp() {
        sessions = mock(DemoSessionService.class);
        links = mock(DemoChannelLinkService.class);
        limiter = mock(DemoChannelRateLimiter.class);
        when(limiter.tryAcquire(anyString())).thenReturn(true);
        dedup = mock(InboundMessageDedupService.class);
        when(dedup.tryClaim(any(ChannelType.class), anyString())).thenReturn(true);
        chat = mock(AgentChatService.class);
        probe = TurnContextProbe.on(chat);

        sessionId = UUID.randomUUID();
        DemoSession session = mock(DemoSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.isExpired(any(Instant.class))).thenReturn(false);
        when(sessions.findById(sessionId)).thenReturn(Optional.of(session));
        when(sessions.isMessageCapReached(session)).thenReturn(false);
        when(sessions.messageLimit()).thenReturn(20);
        when(sessions.ensureSeededAgent(any(), any())).thenReturn(Optional.of(UUID.randomUUID()));
        when(sessions.inDemoTenantContext(any(), any()))
                .thenAnswer(
                        inv -> {
                            TenantContext.set(demoTenant);
                            try {
                                return ((Supplier<?>) inv.getArgument(1)).get();
                            } finally {
                                TenantContext.clear();
                            }
                        });
        link = mock(DemoChannelLink.class);
        when(link.getId()).thenReturn(UUID.randomUUID());
        when(link.getDemoSessionId()).thenReturn(sessionId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void demoTelegramTurnContextIsTheDemoTenantSetByTheScope() throws Exception {
        DemoTelegramBot bot = mock(DemoTelegramBot.class);
        when(bot.isConfigured()).thenReturn(true);
        when(bot.isAvailable()).thenReturn(true);
        when(bot.verifyWebhookSecret(any())).thenReturn(true);
        when(bot.botUsername()).thenReturn("demo_bot");
        when(links.findLiveBinding(DemoChannelType.TELEGRAM, CHAT_ID))
                .thenReturn(Optional.of(link));
        DemoTelegramWebhookController controller =
                new DemoTelegramWebhookController(bot, sessions, links, limiter, chat, dedup);

        controller.inbound(
                MAPPER.readTree(
                        "{\"update_id\":9,\"message\":{\"chat\":{\"id\":"
                                + CHAT_ID
                                + "},\"from\":{\"id\":1},\"text\":\"write a reply\"}}"),
                "secret");

        probe.assertTenant("demo_sandbox", "demo:session");
        assertSame(demoTenant, probe.context().tenantContext().orElse(null));
        TurnContextProbe.assertThreadClean();
    }

    @Test
    void demoWhatsAppTurnContextIsTheDemoTenantSetByTheScope() throws Exception {
        DemoWhatsAppCloudApi meta = mock(DemoWhatsAppCloudApi.class);
        PlatformSettingsService settings = DemoWhatsAppBotTest.settings(true);
        DemoWhatsAppBot bot =
                DemoWhatsAppBotTest.bot(
                        meta,
                        settings,
                        "1234",
                        "tok",
                        DemoWhatsAppBotTest.SECRET,
                        "+919876543210",
                        "vt");
        DemoWhatsAppPhoneGuard phoneGuard = mock(DemoWhatsAppPhoneGuard.class);
        when(phoneGuard.tryConsumeTurn(anyString(), anyInt())).thenReturn(true);
        when(links.findLiveBinding(DemoChannelType.WHATSAPP, bot.hashPhone(FROM)))
                .thenReturn(Optional.of(link));
        DemoWhatsAppWebhookController controller =
                new DemoWhatsAppWebhookController(
                        bot,
                        sessions,
                        links,
                        limiter,
                        phoneGuard,
                        chat,
                        mock(AgentRepository.class),
                        dedup,
                        60,
                        3,
                        Runnable::run);
        byte[] body =
                ("{\"entry\":[{\"changes\":[{\"value\":{\"metadata\":{\"phone_number_id\":\"1234\"},"
                                + "\"messages\":[{\"id\":\"w1\",\"from\":\""
                                + FROM
                                + "\",\"type\":\"text\",\"text\":{\"body\":\"write a reply\"}}]}}]}]}")
                        .getBytes(StandardCharsets.UTF_8);

        controller.inbound(body, DemoWhatsAppBotTest.sign(body, DemoWhatsAppBotTest.SECRET));

        probe.assertTenant("demo_sandbox", "demo:session");
        assertSame(demoTenant, probe.context().tenantContext().orElse(null));
        TurnContextProbe.assertThreadClean();
    }
}
