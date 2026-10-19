package com.agenticai.core.omnichannel.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Cold-start lane: {@link DemoTelegramBot#initialise()} is an {@code ApplicationReadyEvent}
 * listener, which runs before readiness flips, so its two Telegram HTTP calls can delay readiness.
 * With {@code app.startup.background-warmup.enabled=true} it hops to a background thread; with the
 * flag off (default) it must behave exactly as before.
 */
class DemoTelegramBotStartupTest {

    private static DemoTelegramBot botWithToken(DemoTelegramBotApi api) {
        return new DemoTelegramBot(
                api,
                mock(PlatformSettingsService.class),
                "123:token",
                "",
                "",
                "https://api.example.test",
                true);
    }

    @Test
    void flagOffResolvesTheUsernameSynchronouslyBeforeReturning() {
        DemoTelegramBotApi api = mock(DemoTelegramBotApi.class);
        when(api.fetchBotUsername("123:token")).thenReturn("@demo_bot");
        DemoTelegramBot bot = botWithToken(api);

        bot.initialise();

        // No waiting needed: the call has completed by the time initialise() returned.
        verify(api, times(1)).fetchBotUsername("123:token");
        assertThat(bot.botUsername()).isEqualTo("demo_bot");
    }

    @Test
    void flagOnReturnsWhileTelegramIsStillBlockedAndStillCompletesTheWork() throws Exception {
        DemoTelegramBotApi api = mock(DemoTelegramBotApi.class);
        CountDownLatch release = new CountDownLatch(1);
        when(api.fetchBotUsername("123:token"))
                .thenAnswer(
                        invocation -> {
                            release.await(5, TimeUnit.SECONDS);
                            return "@demo_bot";
                        });
        DemoTelegramBot bot = botWithToken(api);
        ReflectionTestUtils.setField(bot, "backgroundWarmup", true);

        bot.initialise(); // must return although fetchBotInfo is blocked

        assertThat(bot.botUsername()).isNull();
        release.countDown();
        verify(api, timeout(2000)).setWebhook(anyString(), anyString(), anyString());
        assertThat(bot.botUsername()).isEqualTo("demo_bot");
    }

    @Test
    void flagOnContainsAFailureFromTelegram() throws Exception {
        DemoTelegramBotApi api = mock(DemoTelegramBotApi.class);
        when(api.fetchBotUsername("123:token"))
                .thenThrow(new IllegalStateException("telegram down"));
        DemoTelegramBot bot = botWithToken(api);
        ReflectionTestUtils.setField(bot, "backgroundWarmup", true);

        bot.initialise();

        verify(api, timeout(2000)).fetchBotUsername("123:token");
        verify(api, never()).setWebhook(anyString(), anyString(), anyString());
    }
}
