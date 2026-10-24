package com.agenticai.core.agents.turn.quality;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.audit.PlatformIssueService;
import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.chat.langfuse.LangfuseScoreClient;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The sampling decision of the live-turn quality judge reads the run-time rate: a platform-setting override beats
 * the configured rate in both directions, and without one the configured rate decides as it always did.
 */
class LlmQualityJudgeServiceSamplingTest {

    private final PlatformSettingsService settings = mock(PlatformSettingsService.class);
    private final ChatClient client = mock(ChatClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);

    @SuppressWarnings("unchecked")
    private LlmQualityJudgeService service(double configuredRate, boolean withRuntimeSetting) {
        ObjectProvider<PlatformIssueService> issues = mock(ObjectProvider.class);
        when(issues.getIfAvailable()).thenReturn(mock(PlatformIssueService.class));
        Executor direct = Runnable::run;
        LlmQualityJudgeService service = new LlmQualityJudgeService(
            issues, mock(LlmCallLogService.class), mock(LangfuseScoreClient.class), configuredRate, 600, direct);
        if (withRuntimeSetting) {
            service.useSampleRate(new JudgeSampleRate(settings, 0, System::currentTimeMillis));
        }
        when(client.prompt().system(org.mockito.ArgumentMatchers.anyString()).user(org.mockito.ArgumentMatchers.anyString())
            .call().content()).thenReturn("OK");
        // The stubbing above went through client.prompt() too; only the calls the service makes count.
        org.mockito.Mockito.clearInvocations(client);
        return service;
    }

    private void sample(LlmQualityJudgeService service) {
        for (int i = 0; i < 20; i++) {
            service.sampleAsync(client, UUID.randomUUID(), "s", "question", "answer");
        }
    }

    @Test
    void anAdminOverrideOfOneSamplesEveryTurnEvenWhenTheConfiguredRateIsZero() {
        when(settings.getDouble(JudgeSampleRate.KEY, 0.0)).thenReturn(1.0);

        sample(service(0.0, true));

        verify(client, org.mockito.Mockito.atLeast(20)).prompt();
    }

    @Test
    void anAdminOverrideOfZeroSwitchesSamplingOffEvenWhenTheConfiguredRateIsOne() {
        when(settings.getDouble(JudgeSampleRate.KEY, 1.0)).thenReturn(0.0);

        sample(service(1.0, true));

        verify(client, never()).prompt();
    }

    @Test
    void withNoOverrideBeanTheConfiguredRateDecides() {
        sample(service(0.0, false));
        verify(client, never()).prompt();

        sample(service(1.0, false));
        verify(client, org.mockito.Mockito.atLeast(20)).prompt();
    }

    @Test
    void anUnsetRowFallsBackToTheConfiguredRate() {
        when(settings.getDouble(org.mockito.ArgumentMatchers.eq(JudgeSampleRate.KEY), anyDouble()))
            .thenAnswer(inv -> inv.getArgument(1));

        sample(service(1.0, true));

        verify(client, org.mockito.Mockito.atLeast(20)).prompt();
    }
}
