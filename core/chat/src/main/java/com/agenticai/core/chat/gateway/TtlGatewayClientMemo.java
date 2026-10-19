package com.agenticai.core.chat.gateway;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.TtlCache;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Short-TTL {@link GatewayClientMemo}. The key is tenant-first ({@link TtlCache#key}) and carries a SHA-256
 * fingerprint of the API key, so a rotated or replaced credential can never be served a client built from the
 * old one, and the raw key is never part of a cache key. The client itself holds the decrypted key for at most
 * the TTL; entries are also evicted by {@link HotpathCaches#evictProviderCredential} on every credential write.
 * Only allow-listed providers are memoised (a factory must be verified stateless before it is listed), and calls
 * with provider options (e.g. xAI server tools) or without a tenant are never cached.
 */
public final class TtlGatewayClientMemo implements GatewayClientMemo {

    private final TtlCache<ChatClient> cache;
    private final Set<ProviderName> providers;

    public TtlGatewayClientMemo(Duration ttl, int maxEntries, Set<ProviderName> providers) {
        this.cache = new TtlCache<>(HotpathCaches.GATEWAY_CLIENT, ttl, maxEntries);
        this.providers = Set.copyOf(providers);
    }

    @Override
    public ChatClient get(String tenantId, GatewayCallSpec spec, String effectiveModel, Supplier<ChatClient> builder) {
        if (!cache.isEnabled() || tenantId == null || spec.apiKey() == null
            || !spec.providerOptions().isEmpty() || !providers.contains(spec.providerName())) {
            return builder.get();
        }
        String key = TtlCache.key(
            tenantId, spec.providerName().name(), String.valueOf(effectiveModel), String.valueOf(spec.maxTokens()),
            String.valueOf(spec.temperature()), String.valueOf(spec.topP()), fingerprint(spec.apiKey()));
        return cache.get(key, builder);
    }

    public long hits() {
        return cache.hits();
    }

    public long misses() {
        return cache.misses();
    }

    TtlCache<ChatClient> cache() {
        return cache;
    }

    private static String fingerprint(String apiKey) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
