package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Plan / hosted-pool / credential caches: flag off = the very same collaborator calls as before. */
class ChatHotpathTest {

    private final TenantLimits limits = mock(TenantLimits.class);
    private final ProviderCredentialResolver resolver = mock(ProviderCredentialResolver.class);

    private static ChatHotpath on() {
        return new ChatHotpath(Duration.ofSeconds(10), Duration.ofSeconds(30), false, false);
    }

    // ---- plan cache ----

    @Test
    void planFlagOffCallsTenantLimitsEveryTime() {
        when(limits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        ChatHotpath off = ChatHotpath.OFF;
        assertThat(off.plan(limits, "acme")).isEqualTo(TenantPlan.PREMIUM);
        off.plan(limits, "acme");
        verify(limits, times(2)).plan("acme");
    }

    @Test
    void planFlagOnReadsOncePerTtl() {
        when(limits.plan("acme")).thenReturn(TenantPlan.PREMIUM);
        ChatHotpath hotpath = on();
        hotpath.plan(limits, "acme");
        hotpath.plan(limits, "acme");
        hotpath.plan(limits, "acme");
        verify(limits, times(1)).plan("acme");
        assertThat(hotpath.hits("plan")).isEqualTo(2);
    }

    @Test
    void planEvictionOnTheWritePathIsSeenByTheNextTurn() {
        when(limits.plan("acme")).thenReturn(TenantPlan.PREMIUM, TenantPlan.FREE);
        ChatHotpath hotpath = on();
        assertThat(hotpath.plan(limits, "acme")).isEqualTo(TenantPlan.PREMIUM);
        // downgrade committed on this pod: TenantPlanEvictionListener -> HotpathCaches
        HotpathCaches.evictTenantPlan("acme");
        assertThat(hotpath.plan(limits, "acme")).isEqualTo(TenantPlan.FREE);
    }

    @Test
    void planCacheNeverServesOneTenantsPlanToAnother() {
        when(limits.plan("acme")).thenReturn(TenantPlan.UNLIMITED);
        when(limits.plan("globex")).thenReturn(TenantPlan.FREE);
        ChatHotpath hotpath = on();
        assertThat(hotpath.plan(limits, "acme")).isEqualTo(TenantPlan.UNLIMITED);
        assertThat(hotpath.plan(limits, "globex")).isEqualTo(TenantPlan.FREE);
        // evicting one tenant leaves the other cached
        HotpathCaches.evictTenantPlan("acme");
        hotpath.plan(limits, "globex");
        verify(limits, times(1)).plan("globex");
    }

    @Test
    void nullTenantIsNeverCached() {
        when(limits.plan(null)).thenReturn(TenantPlan.FREE);
        ChatHotpath hotpath = on();
        hotpath.plan(limits, null);
        hotpath.plan(limits, null);
        verify(limits, times(2)).plan(null);
    }

    // ---- hosted-pool answer ----

    @Test
    void poolAnswerCachedForPaidButFreeStaysADirectCall() {
        when(limits.usesPlatformPool("acme", TenantPlan.PREMIUM)).thenReturn(false);
        when(limits.usesPlatformPool("free", TenantPlan.FREE)).thenReturn(true);
        ChatHotpath hotpath = on();
        assertThat(hotpath.usesPlatformPool(limits, "acme", TenantPlan.PREMIUM)).isFalse();
        hotpath.usesPlatformPool(limits, "acme", TenantPlan.PREMIUM);
        assertThat(hotpath.usesPlatformPool(limits, "free", TenantPlan.FREE)).isTrue();
        hotpath.usesPlatformPool(limits, "free", TenantPlan.FREE);
        verify(limits, times(1)).usesPlatformPool("acme", TenantPlan.PREMIUM);
        verify(limits, times(2)).usesPlatformPool("free", TenantPlan.FREE);
    }

    @Test
    void poolFlagOffCallsEveryTimeAndPlanChangeEvictsIt() {
        when(limits.usesPlatformPool("acme", TenantPlan.PREMIUM)).thenReturn(false);
        ChatHotpath.OFF.usesPlatformPool(limits, "acme", TenantPlan.PREMIUM);
        ChatHotpath.OFF.usesPlatformPool(limits, "acme", TenantPlan.PREMIUM);
        verify(limits, times(2)).usesPlatformPool("acme", TenantPlan.PREMIUM);

        ChatHotpath hotpath = on();
        hotpath.usesPlatformPool(limits, "acme", TenantPlan.PREMIUM);
        HotpathCaches.evictTenantPlan("acme");
        hotpath.usesPlatformPool(limits, "acme", TenantPlan.PREMIUM);
        verify(limits, times(4)).usesPlatformPool("acme", TenantPlan.PREMIUM);
    }

    // ---- credential cache ----

    @Test
    void credentialFlagOffResolvesEveryTime() {
        when(resolver.resolveActiveKey(ProviderName.ANTHROPIC)).thenReturn("sk-1");
        ChatHotpath.OFF.resolveActiveKey(resolver, "acme", ProviderName.ANTHROPIC);
        ChatHotpath.OFF.resolveActiveKey(resolver, "acme", ProviderName.ANTHROPIC);
        verify(resolver, times(2)).resolveActiveKey(ProviderName.ANTHROPIC);
    }

    @Test
    void credentialFlagOnResolvesOnceThenRotationIsSeenAfterEviction() {
        when(resolver.resolveActiveKey(ProviderName.ANTHROPIC)).thenReturn("sk-1", "sk-2");
        ChatHotpath hotpath = on();
        assertThat(hotpath.resolveActiveKey(resolver, "acme", ProviderName.ANTHROPIC)).isEqualTo("sk-1");
        assertThat(hotpath.resolveActiveKey(resolver, "acme", ProviderName.ANTHROPIC)).isEqualTo("sk-1");
        // key rotated on this pod: ProviderCredentialEvictionListener -> HotpathCaches
        HotpathCaches.evictProviderCredential("acme", "ANTHROPIC");
        assertThat(hotpath.resolveActiveKey(resolver, "acme", ProviderName.ANTHROPIC)).isEqualTo("sk-2");
        verify(resolver, times(2)).resolveActiveKey(ProviderName.ANTHROPIC);
    }

    @Test
    void credentialsAreTenantScopedAndProviderScoped() {
        when(resolver.resolveActiveKey(ProviderName.ANTHROPIC)).thenReturn("acme-key", "globex-key");
        ChatHotpath hotpath = on();
        assertThat(hotpath.resolveActiveKey(resolver, "acme", ProviderName.ANTHROPIC)).isEqualTo("acme-key");
        assertThat(hotpath.resolveActiveKey(resolver, "globex", ProviderName.ANTHROPIC)).isEqualTo("globex-key");
        assertThat(hotpath.resolveActiveKey(resolver, "acme", ProviderName.ANTHROPIC)).isEqualTo("acme-key");
        // evicting acme/OPENAI must not touch acme/ANTHROPIC
        HotpathCaches.evictProviderCredential("acme", "OPENAI");
        hotpath.resolveActiveKey(resolver, "acme", ProviderName.ANTHROPIC);
        verify(resolver, times(2)).resolveActiveKey(ProviderName.ANTHROPIC);
    }

    @Test
    void failedResolutionIsNotCachedAndSarvamAndNoTenantBypass() {
        when(resolver.resolveActiveKey(ProviderName.OPENAI))
            .thenThrow(new IllegalStateException("not active")).thenReturn("sk-ok");
        ChatHotpath hotpath = on();
        assertThatThrownBy(() -> hotpath.resolveActiveKey(resolver, "acme", ProviderName.OPENAI))
            .isInstanceOf(IllegalStateException.class);
        assertThat(hotpath.resolveActiveKey(resolver, "acme", ProviderName.OPENAI)).isEqualTo("sk-ok");

        when(resolver.resolveActiveKey(ProviderName.SARVAM)).thenReturn("s");
        hotpath.resolveActiveKey(resolver, "acme", ProviderName.SARVAM);
        hotpath.resolveActiveKey(resolver, "acme", ProviderName.SARVAM);
        verify(resolver, times(2)).resolveActiveKey(ProviderName.SARVAM);

        hotpath.resolveActiveKey(resolver, null, ProviderName.GROQ);
        hotpath.resolveActiveKey(resolver, null, ProviderName.GROQ);
        verify(resolver, times(2)).resolveActiveKey(ProviderName.GROQ);
    }

    @Test
    void ttlIsClamped() {
        assertThat(ChatHotpathConfig.clamp(Duration.ofMinutes(10), ChatHotpathConfig.MAX_PLAN_TTL))
            .isEqualTo(Duration.ofSeconds(30));
        assertThat(ChatHotpathConfig.clamp(Duration.ofSeconds(-5), ChatHotpathConfig.MAX_PLAN_TTL)).isZero();
        assertThat(ChatHotpathConfig.clamp(null, ChatHotpathConfig.MAX_PLAN_TTL)).isZero();
        assertThat(ChatHotpathConfig.clamp(Duration.ofSeconds(5), ChatHotpathConfig.MAX_PLAN_TTL))
            .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void clientCacheNeverOutlivesTheCredentialLookupAndIsOffWithoutIt() {
        assertThat(ChatHotpathConfig.effectiveClientTtl(Duration.ofSeconds(60), Duration.ofSeconds(20)))
            .isEqualTo(Duration.ofSeconds(20));
        assertThat(ChatHotpathConfig.effectiveClientTtl(Duration.ofSeconds(10), Duration.ofSeconds(20)))
            .isEqualTo(Duration.ofSeconds(10));
        assertThat(ChatHotpathConfig.effectiveClientTtl(Duration.ofSeconds(10), Duration.ZERO)).isZero();
        assertThat(ChatHotpathConfig.effectiveClientTtl(Duration.ZERO, Duration.ofSeconds(20))).isZero();
    }
}
