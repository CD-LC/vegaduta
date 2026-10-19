package com.agenticai.core.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TtlCache;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProviderCredentialEvictionListenerTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void aCredentialWriteEvictsThatTenantsKeyAndClientForThatProviderOnly() {
        TtlCache<String> keys = new TtlCache<>(HotpathCaches.PROVIDER_KEY, Duration.ofSeconds(10), 10);
        TtlCache<String> clients = new TtlCache<>(HotpathCaches.GATEWAY_CLIENT, Duration.ofSeconds(10), 10);
        keys.get(TtlCache.key("acme", "ANTHROPIC"), () -> "sk-old");
        keys.get(TtlCache.key("acme", "OPENAI"), () -> "sk-oa");
        keys.get(TtlCache.key("globex", "ANTHROPIC"), () -> "sk-g");
        clients.get(TtlCache.key("acme", "ANTHROPIC", "m", "1024", "0.7", "1.0", "fp"), () -> "client");
        TenantContext.set(TenantContext.of("acme", "u", Set.of()));

        new ProviderCredentialEvictionListener().onWrite(
            new LlmProviderCredential(ProviderName.ANTHROPIC, "enc", ProviderStatus.INVALID));

        assertThat(keys.size()).isEqualTo(2);
        assertThat(clients.size()).isZero();
    }

    @Test
    void aWriteWithNoTenantContextFlushesRatherThanGuessing() {
        TtlCache<String> keys = new TtlCache<>(HotpathCaches.PROVIDER_KEY, Duration.ofSeconds(10), 10);
        keys.get(TtlCache.key("acme", "ANTHROPIC"), () -> "sk");
        keys.get(TtlCache.key("globex", "OPENAI"), () -> "sk2");

        new ProviderCredentialEvictionListener().onWrite(
            new LlmProviderCredential(ProviderName.ANTHROPIC, "enc", ProviderStatus.ACTIVE));

        assertThat(keys.size()).isZero();
    }
}
