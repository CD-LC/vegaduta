package com.agenticai.core.agents.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetService.Modality;
import com.agenticai.core.chat.LlmPricingCatalog;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import reactor.core.publisher.Flux;

/**
 * A response that was produced and shown but came back with no usage metadata (a provider that omits it, a stream
 * cancelled before its usage event) used to release the whole reservation, so the call cost nothing. The user could
 * cancel every stream after the text was out and never spend budget. It is now charged for what was received.
 */
class PerUserBudgetUnmeasuredResponseTest {

    private static final String TENANT = "tenant-a";
    private static final String USER = "user-1";
    private static final long RESERVED = 150_000L;
    private static final TenantPlan PLAN = TenantPlan.FREE;

    // ------------------------------------------------------------------ the advisor

    private PerUserBudgetService budget;
    private PerUserBudgetTrackingAdvisor advisor;

    @BeforeEach
    void setUpAdvisor() {
        budget = mock(PerUserBudgetService.class);
        advisor = new PerUserBudgetTrackingAdvisor(budget, TENANT, USER, Modality.TEXT, "hosted-x", RESERVED, PLAN);
    }

    private static ChatClientResponse answer(String text, Usage usage) {
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (usage != null) {
            metadata.usage(usage);
        }
        ChatResponse chat = ChatResponse.builder()
            .generations(List.of(new Generation(new AssistantMessage(text))))
            .metadata(metadata.build())
            .build();
        return ChatClientResponse.builder().chatResponse(chat).build();
    }

    private void stream(ChatClientResponse... chunks) {
        ChatClientRequest request = mock(ChatClientRequest.class);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(request)).thenReturn(Flux.just(chunks));
        advisor.adviseStream(request, chain).blockLast();
    }

    @Test
    void textWithNoUsageKeepsTheReservationAsTheChargeInsteadOfReleasingIt() {
        stream(answer("x".repeat(4_000), null));

        verify(budget).chargeUnmeasuredResponse(TENANT, USER, Modality.TEXT, RESERVED, 4_000L, PLAN);
        verify(budget, never()).reconcileSpendReservationForUsage(anyString(), anyString(), any(), anyLong(), anyString(), any(), any());
    }

    @Test
    void aNonStreamedAnswerWithNoUsageIsChargedToo() {
        ChatClientRequest request = mock(ChatClientRequest.class);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(request)).thenReturn(answer("x".repeat(400), null));

        advisor.adviseCall(request, chain);

        verify(budget).chargeUnmeasuredResponse(TENANT, USER, Modality.TEXT, RESERVED, 400L, PLAN);
    }

    @Test
    void anAllZeroUsageIsTreatedAsNotMeasured() {
        stream(answer("hello there", new DefaultUsage(0, 0)));

        verify(budget).chargeUnmeasuredResponse(TENANT, USER, Modality.TEXT, RESERVED, 11L, PLAN);
    }

    @Test
    void aMeasuredPromptWithNoCompletionIsPricedFromTheTextThatArrived() {
        stream(answer("x".repeat(4_000), new DefaultUsage(1_200, 0)));

        ArgumentCaptor<Usage> usage = ArgumentCaptor.forClass(Usage.class);
        verify(budget).reconcileSpendReservationForUsage(
            eq(TENANT), eq(USER), eq(Modality.TEXT), eq(RESERVED), eq("hosted-x"), usage.capture(), eq(PLAN));
        assertThat(usage.getValue().getPromptTokens()).isEqualTo(1_200);
        assertThat(usage.getValue().getCompletionTokens()).isEqualTo(1_000);
        verify(budget, never()).chargeUnmeasuredResponse(anyString(), anyString(), any(), anyLong(), anyLong(), any());
    }

    @Test
    void realUsageIsUntouched() {
        stream(answer("short", new DefaultUsage(1_200, 300)));

        ArgumentCaptor<Usage> usage = ArgumentCaptor.forClass(Usage.class);
        verify(budget).reconcileSpendReservationForUsage(
            eq(TENANT), eq(USER), eq(Modality.TEXT), eq(RESERVED), eq("hosted-x"), usage.capture(), eq(PLAN));
        assertThat(usage.getValue().getCompletionTokens()).isEqualTo(300);
    }

    @Test
    void nothingReceivedStillReleasesTheReservation() {
        stream(answer("", null));

        verify(budget, never()).chargeUnmeasuredResponse(anyString(), anyString(), any(), anyLong(), anyLong(), any());
        verify(budget).reconcileSpendReservationForUsage(
            eq(TENANT), eq(USER), eq(Modality.TEXT), eq(RESERVED), eq("hosted-x"), isNull(), eq(PLAN));
    }

    // ------------------------------------------------------------------ the service

    @SuppressWarnings("unchecked")
    private PerUserBudgetService realService(ValueOperations<String, String> ops) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getString(anyString(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(call -> call.getArgument(1));
        TenantLimits limits = mock(TenantLimits.class);
        when(limits.plan(anyString())).thenReturn(TenantPlan.FREE);
        return new PerUserBudgetService(
            redis, settings, mock(LlmPricingCatalog.class), 0L, limits, 2, 3, mock(ObjectProvider.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSmallResponseKeepsTheWholeReservationAsTheCharge() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        PerUserBudgetService service = realService(ops);

        service.chargeUnmeasuredResponse(TENANT, USER, Modality.TEXT, RESERVED, 40L, PLAN);

        // Ten tokens at the fallback rate is far under the reservation: nothing is given back, nothing is added.
        verify(ops, never()).increment(anyString(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aResponseLargerThanTheReservationRaisesTheCharge() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        PerUserBudgetService service = realService(ops);
        BigDecimal estimate = service.estimateCostForChars(TENANT, USER, 4_000_000L, PLAN);
        long estimateMicroCents = estimate.multiply(PerUserBudgetService.MICRO_CENTS_PER_USD).longValue();
        assertThat(estimateMicroCents).isGreaterThan(RESERVED);

        service.chargeUnmeasuredResponse(TENANT, USER, Modality.TEXT, RESERVED, 4_000_000L, PLAN);

        verify(ops).increment(anyString(), eq(estimateMicroCents - RESERVED));
    }

    @Test
    @SuppressWarnings("unchecked")
    void withNoReservationTheReceivedTextIsRecordedAtTheFallbackRate() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        PerUserBudgetService service = realService(ops);
        long expected = service.estimateCostForChars(TENANT, USER, 4_000_000L, PLAN)
            .multiply(PerUserBudgetService.MICRO_CENTS_PER_USD).longValue();

        service.chargeUnmeasuredResponse(TENANT, USER, Modality.TEXT, 0L, 4_000_000L, PLAN);

        verify(ops).increment(anyString(), eq(expected));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nothingReceivedChargesNothing() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        PerUserBudgetService service = realService(ops);

        service.chargeUnmeasuredResponse(TENANT, USER, Modality.TEXT, RESERVED, 0L, PLAN);

        verify(ops, never()).increment(anyString(), anyLong());
    }
}
