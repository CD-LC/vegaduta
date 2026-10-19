package com.agenticai.core.agents.turn;

import com.agenticai.core.chat.gateway.GatewayClientMemo;
import com.agenticai.core.chat.gateway.TtlGatewayClientMemo;
import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link ChatHotpath} and the gateway client memo from the {@code app.chat.hotpath.*} properties. Every
 * default reproduces today's behaviour (TTL 0 / flag false); TTLs are clamped (plan 30 s, credential and client
 * 60 s) so a typo cannot turn a short-TTL cache into a long-lived one. See docs/plans/CORE-HOTPATH-FLAGS.md.
 */
@Configuration
class ChatHotpathConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ChatHotpathConfig.class);

    static final Duration MAX_PLAN_TTL = Duration.ofSeconds(30);
    static final Duration MAX_CREDENTIAL_TTL = Duration.ofSeconds(60);

    static Duration clamp(Duration v, Duration max) {
        if (v == null || v.isNegative()) {
            return Duration.ZERO;
        }
        return v.compareTo(max) > 0 ? max : v;
    }

    /** Client cache lifetime: never longer than the credential-lookup TTL; zero (off) when that cache is off. */
    static Duration effectiveClientTtl(Duration clientTtl, Duration credentialTtl) {
        Duration credential = clamp(credentialTtl, MAX_CREDENTIAL_TTL);
        Duration client = clamp(clientTtl, MAX_CREDENTIAL_TTL);
        return credential.isZero() || client.compareTo(credential) > 0 ? credential : client;
    }

    @Bean
    ChatHotpath chatHotpath(
        ObjectProvider<MeterRegistry> registry,
        @Value("${app.chat.hotpath.plan-cache-ttl:0s}") Duration planTtl,
        @Value("${app.chat.hotpath.credential-cache-ttl:0s}") Duration credentialTtl,
        @Value("${app.chat.hotpath.rl-batch-select:false}") boolean rlBatchSelect,
        @Value("${app.chat.hotpath.async-attachment-fetch:false}") boolean asyncAttachmentFetch
    ) {
        ChatHotpath hotpath = new ChatHotpath(
            clamp(planTtl, MAX_PLAN_TTL), clamp(credentialTtl, MAX_CREDENTIAL_TTL), rlBatchSelect, asyncAttachmentFetch);
        MeterRegistry meters = registry.getIfAvailable();
        if (meters != null) {
            for (String cache : new String[] {"plan", "pool", "credential"}) {
                FunctionCounter.builder("agentic.chat.hotpath.cache", hotpath, h -> h.hits(cache))
                    .tag("cache", cache).tag("result", "hit").register(meters);
                FunctionCounter.builder("agentic.chat.hotpath.cache", hotpath, h -> h.misses(cache))
                    .tag("cache", cache).tag("result", "miss").register(meters);
            }
        }
        return hotpath;
    }

    /**
     * ChatClient reuse. Active only when the credential cache is also on, and never outlives it: the client holds
     * the decrypted key, so its lifetime is capped at the credential-lookup TTL.
     */
    @Bean
    GatewayClientMemo gatewayClientMemo(
        ObjectProvider<MeterRegistry> registry,
        @Value("${app.chat.hotpath.client-cache-ttl:0s}") Duration clientTtl,
        @Value("${app.chat.hotpath.credential-cache-ttl:0s}") Duration credentialTtl,
        @Value("${app.chat.hotpath.client-cache-providers:ANTHROPIC}") String providerList
    ) {
        Set<ProviderName> providers = EnumSet.noneOf(ProviderName.class);
        Arrays.stream(providerList.split(",")).map(String::trim).filter(s -> !s.isEmpty())
            .forEach(s -> {
                try {
                    providers.add(ProviderName.valueOf(s.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException unknown) {
                    // A config typo must degrade (client memo skips that provider), never fail boot.
                    LOG.warn("app.chat.hotpath.client-cache-providers: unknown provider '{}' ignored", s);
                }
            });
        TtlGatewayClientMemo memo = new TtlGatewayClientMemo(effectiveClientTtl(clientTtl, credentialTtl), 500, providers);
        MeterRegistry meters = registry.getIfAvailable();
        if (meters != null) {
            FunctionCounter.builder("agentic.chat.hotpath.cache", memo, TtlGatewayClientMemo::hits)
                .tag("cache", "client").tag("result", "hit").register(meters);
            FunctionCounter.builder("agentic.chat.hotpath.cache", memo, TtlGatewayClientMemo::misses)
                .tag("cache", "client").tag("result", "miss").register(meters);
        }
        return memo;
    }
}
