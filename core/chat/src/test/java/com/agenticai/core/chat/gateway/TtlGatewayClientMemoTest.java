package com.agenticai.core.chat.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.HotpathCaches;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class TtlGatewayClientMemoTest {

    private final AtomicInteger builds = new AtomicInteger();

    private ChatClient build() {
        builds.incrementAndGet();
        return mock(ChatClient.class);
    }

    private static GatewayCallSpec spec(ProviderName p, String key, Map<String, Object> opts) {
        return new GatewayCallSpec(p, key, "m", 1024, 0.7, 1.0, null, opts);
    }

    private TtlGatewayClientMemo memo(Duration ttl) {
        return new TtlGatewayClientMemo(ttl, 100, Set.of(ProviderName.ANTHROPIC));
    }

    @Test
    void disabledBuildsEveryTime() {
        var memo = memo(Duration.ZERO);
        var s = spec(ProviderName.ANTHROPIC, "sk-1", Map.of());
        memo.get("acme", s, "m", this::build);
        memo.get("acme", s, "m", this::build);
        assertThat(builds).hasValue(2);
    }

    @Test
    void enabledReusesTheClientForIdenticalInputs() {
        var memo = memo(Duration.ofSeconds(30));
        var s = spec(ProviderName.ANTHROPIC, "sk-1", Map.of());
        ChatClient a = memo.get("acme", s, "m", this::build);
        ChatClient b = memo.get("acme", s, "m", this::build);
        assertThat(a).isSameAs(b);
        assertThat(builds).hasValue(1);
    }

    @Test
    void twoTenantsWithTheSameKeyAndModelNeverShareAClient() {
        var memo = memo(Duration.ofSeconds(30));
        var s = spec(ProviderName.ANTHROPIC, "sk-1", Map.of());
        ChatClient a = memo.get("acme", s, "m", this::build);
        ChatClient b = memo.get("globex", s, "m", this::build);
        assertThat(a).isNotSameAs(b);
        assertThat(builds).hasValue(2);
    }

    @Test
    void rotatedKeyOrDifferentModelBuildsANewClient() {
        var memo = memo(Duration.ofSeconds(30));
        memo.get("acme", spec(ProviderName.ANTHROPIC, "sk-1", Map.of()), "m", this::build);
        memo.get("acme", spec(ProviderName.ANTHROPIC, "sk-2", Map.of()), "m", this::build);
        memo.get("acme", spec(ProviderName.ANTHROPIC, "sk-2", Map.of()), "m2", this::build);
        assertThat(builds).hasValue(3);
    }

    @Test
    void credentialWriteEvictsThatTenantsClientsOnly() {
        var memo = memo(Duration.ofSeconds(30));
        var s = spec(ProviderName.ANTHROPIC, "sk-1", Map.of());
        memo.get("acme", s, "m", this::build);
        memo.get("globex", s, "m", this::build);
        HotpathCaches.evictProviderCredential("acme", "ANTHROPIC");
        memo.get("acme", s, "m", this::build);   // rebuilt
        memo.get("globex", s, "m", this::build); // still cached
        assertThat(builds).hasValue(3);
    }

    @Test
    void neverCachesWithoutTenantWithProviderOptionsOrForUnlistedProviders() {
        var memo = memo(Duration.ofSeconds(30));
        memo.get(null, spec(ProviderName.ANTHROPIC, "sk", Map.of()), "m", this::build);
        memo.get(null, spec(ProviderName.ANTHROPIC, "sk", Map.of()), "m", this::build);
        memo.get("acme", spec(ProviderName.ANTHROPIC, "sk", Map.of("tools", "x")), "m", this::build);
        memo.get("acme", spec(ProviderName.ANTHROPIC, "sk", Map.of("tools", "x")), "m", this::build);
        memo.get("acme", spec(ProviderName.OPENAI, "sk", Map.of()), "m", this::build);
        memo.get("acme", spec(ProviderName.OPENAI, "sk", Map.of()), "m", this::build);
        assertThat(builds).hasValue(6);
    }
}
