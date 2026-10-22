package com.agenticai.core.tenancy.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PlatformSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * SEC-004 (demo half): an anonymous caller holding a demo token could previously have any chat
 * session's history mailed to themselves by passing its UUID - the service read {@code
 * agentic:session:<uuid>} verbatim. The key is now derived from the CALLER'S demo session, so only
 * that sandbox's own web-chat history is reachable.
 */
class DemoTranscriptEmailServiceTest {

    private static final String PREFIX = "agentic:session:";
    private static final String NOTHING_TO_SEND =
            "There's no conversation to send yet - chat with your agent first, then email the transcript.";
    private static final String MAIL_UNAVAILABLE =
            "Email sending isn't available right now. Your address is saved - your sandbox keeps working.";

    private final Map<String, List<String>> redisLists = new HashMap<>();
    private ListOperations<String, String> listOps;
    private DemoTranscriptEmailService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), anyLong(), anyLong()))
                .thenAnswer(inv -> redisLists.getOrDefault(inv.getArgument(0), List.of()));
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        // No mail sender: a transcript that WAS found ends in MAIL_UNAVAILABLE, one that was
        // not found ends in NOTHING_TO_SEND - which is exactly the distinction under test.
        ObjectProvider<JavaMailSender> mailProvider = mock(ObjectProvider.class);
        when(mailProvider.getIfAvailable()).thenReturn(null);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        DemoChannelMessageRepository mirror = mock(DemoChannelMessageRepository.class);
        when(mirror.findByDemoSessionIdOrderByCreatedAtAsc(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        service =
                new DemoTranscriptEmailService(
                        mock(DemoSessionRecorder.class),
                        mirror,
                        settings,
                        redisProvider,
                        mailProvider,
                        new ObjectMapper(),
                        "no-reply@example.com",
                        "https://app.example",
                        2);
    }

    private static DemoSession sandbox(String schema) throws Exception {
        Instant now = Instant.now();
        DemoSession session =
                new DemoSession(
                        "hash-" + UUID.randomUUID(),
                        UUID.randomUUID(),
                        schema,
                        null,
                        "203.0.113.7",
                        null,
                        now,
                        now.plus(5, ChronoUnit.DAYS));
        Field id = DemoSession.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(session, UUID.randomUUID());
        session.recordSeededAgent(UUID.randomUUID());
        return session;
    }

    private void storeTurn(String key) {
        redisLists.put(key, List.of("{\"role\":\"USER\",\"content\":\"my card ends 4242\"}"));
    }

    @Test
    void aSessionIdThatDoesNotBelongToTheCallersSandboxIsNeverRead() throws Exception {
        DemoSession victim = sandbox("demo_shared");
        DemoSession attacker = sandbox("demo_shared");
        String victimChatId = UUID.randomUUID().toString();
        // The victim's history, under both the pre-fix raw key and the victim's scoped key.
        storeTurn(PREFIX + victimChatId);
        storeTurn(PREFIX + DemoTranscriptEmailService.webChatMemoryId(victim, victimChatId));

        DemoTranscriptEmailService.Result result =
                service.send(attacker, "attacker@example.com", victimChatId);

        assertThat(result.sent()).isFalse();
        assertThat(result.message()).isEqualTo(NOTHING_TO_SEND);
        verify(listOps, never()).range(eq(PREFIX + victimChatId), anyLong(), anyLong());
        verify(listOps, never())
                .range(
                        eq(
                                PREFIX
                                        + DemoTranscriptEmailService.webChatMemoryId(
                                                victim, victimChatId)),
                        anyLong(),
                        anyLong());
    }

    @Test
    void theCallersOwnSandboxConversationIsStillFound() throws Exception {
        DemoSession own = sandbox("demo_shared");
        String chatId = UUID.randomUUID().toString();
        storeTurn(PREFIX + DemoTranscriptEmailService.webChatMemoryId(own, chatId));

        DemoTranscriptEmailService.Result result = service.send(own, "me@example.com", chatId);

        // Found -> proceeds to the send step (which then reports no mail sender here).
        assertThat(result.message()).isEqualTo(MAIL_UNAVAILABLE);
    }

    @Test
    void theScopedIdMatchesWhatTheDemoChatWritesUnder() throws Exception {
        DemoSession own = sandbox("demo_shared");
        String chatId = UUID.randomUUID().toString();
        // DemoChatController: tenant = sandbox schema, agent = seeded agent, subject = "demo:" +
        // session id.
        assertThat(DemoTranscriptEmailService.webChatMemoryId(own, chatId))
                .isEqualTo(
                        DemoTranscriptEmailService.scopedSessionId(
                                own.getTenantSchema(),
                                own.getAgentId(),
                                "demo:" + own.getId(),
                                chatId));
    }
}
