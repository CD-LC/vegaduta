package com.agenticai.core.chat;

import com.agenticai.core.providers.AwsBedrockCredential;
import com.agenticai.core.providers.ProviderName;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * AWS Bedrock is not OpenAI-chat-completions-compatible (it's AWS SigV4-
 * signed, not bearer/API-key auth), so unlike every other factory in this
 * package it does not go through {@code OpenAiApi} at all - it builds a
 * {@link BedrockRuntimeClient} (for {@code call}) and a {@link
 * BedrockRuntimeAsyncClient} (for {@code ConverseStream}) from the decoded
 * {@link AwsBedrockCredential} (access key/secret/region) and wraps them in
 * the hand-rolled {@link AwsBedrockChatModel} - see that class's javadoc for
 * exactly what it does and doesn't cover.
 *
 * <p>{@code model} is the agent's existing free-text model field, expected
 * to be a full Bedrock model id (e.g. {@code
 * "anthropic.claude-3-5-sonnet-20241022-v2:0"}) - same convention every
 * other factory in this package already follows, not a new credential
 * field (see {@link AwsBedrockCredential}'s javadoc for why the credential
 * itself deliberately does not carry a model id).
 *
 * <p><b>Client caching.</b> {@code create} runs per chat turn. It used to
 * build a fresh {@code BedrockRuntimeClient} every time and never close it,
 * leaking an HTTP connection pool per turn. SDK clients are thread-safe and
 * meant to be long-lived, so they are now cached per credential
 * <em>fingerprint</em> - SHA-256 of access key id + secret + region, so the
 * raw secret is never held as a map key - in a small LRU that closes the
 * clients it evicts (and all of them on shutdown). A rotated key simply
 * gets a new fingerprint; the old entry ages out. Caveat: a turn still
 * running on a client at the moment it is evicted fails; with the default
 * capacity that needs more than {@code max-size} distinct Bedrock
 * credentials active at once.
 *
 * <p><b>Timeouts.</b> {@code apiCallTimeout} (whole call incl. retries,
 * default 10 min) and {@code apiCallAttemptTimeout} (one attempt, default
 * 5 min) are set explicitly so a hung Bedrock call is bounded, and sized for
 * long completions. The SDK's HTTP-level defaults still apply underneath
 * (2s connect; 30s socket/read idle on the sync Apache client, 30s read idle
 * on the async Netty client) - for streaming that is an inter-event gap, not
 * a total, so it only bites if Bedrock goes silent for 30s. A long
 * NON-streamed completion can still hit the sync client's 30s socket timeout;
 * raising that needs an explicit {@code httpClientBuilder}, not done here.
 *
 * <p><b>The one factory in this package with no {@link
 * ResilientToolCallingManagerFactory}</b>, and deliberately so: {@link
 * AwsBedrockChatModel} is a hand-rolled {@code ChatModel} that does not
 * implement tool calling at all (Bedrock's {@code toolConfig} is never sent,
 * so a Bedrock turn cannot produce a tool call to sanitize; tool definitions
 * are ignored with a WARN), and it has no {@code ToolCallingManager} seam to
 * wire one into. If tool calling is added to that class later, it must go
 * through that factory like every other provider here - see its javadoc for
 * what the wrapper protects against.
 */
@Component
public class AwsBedrockAgentChatClientFactory implements AgentChatClientFactory {

    private static final Logger log = LoggerFactory.getLogger(AwsBedrockAgentChatClientFactory.class);

    /** One credential's pair of SDK clients; both are closed together on eviction. */
    record BedrockClients(BedrockRuntimeClient sync, BedrockRuntimeAsyncClient async) {
        void close() {
            closeQuietly(sync);
            closeQuietly(async);
        }

        private static void closeQuietly(AutoCloseable client) {
            try {
                if (client != null) {
                    client.close();
                }
            } catch (Exception e) {
                log.warn("Failed to close evicted AWS Bedrock client: {}", e.toString());
            }
        }
    }

    private final LlmCallMetricsService llmCallMetricsService;
    private final LlmCallLogService llmCallLogService;
    private final MeterRegistry meterRegistry;
    private final LlmPricingCatalog pricingCatalog;
    // 2026-10-05 observability audit fix: this was the ONE factory in the
    // package still on raw ChatClient.builder(chatModel) + a hand-built
    // LlmMetricsAdvisor - which meant the app's ObservationRegistry never
    // reached the ChatClient (all advisor/model observations went to NOOP),
    // and the LlmMetricsAdvisor's own invoke_agent span config (real registry,
    // genai-attrs mode, Langfuse user-id mode - set by LlmModelSupport#advisors)
    // plus every DefaultAgentAdvisor bean (core/rl trajectory advisor, ...)
    // silently never applied to Bedrock turns. Same shared builder/advisor
    // pipeline as every other provider now.
    private final LlmModelSupport modelSupport;
    private final Duration apiCallTimeout;
    private final Duration apiCallAttemptTimeout;
    private final int maxCachedClients;

    /** Access-ordered, so iteration order is least-recently-used first. Guarded by itself. */
    private final LinkedHashMap<String, BedrockClients> clientCache = new LinkedHashMap<>(16, 0.75f, true);
    /**
     * Guards {@link #clientCache}. A ReentrantLock, not {@code synchronized}: a miss builds the AWS SDK clients
     * inside the critical section, and {@code synchronized} around that pins a virtual thread to its carrier on
     * JDK 21 (JAVA-024).
     */
    private final java.util.concurrent.locks.ReentrantLock clientCacheLock = new java.util.concurrent.locks.ReentrantLock();

    public AwsBedrockAgentChatClientFactory(
        LlmCallMetricsService llmCallMetricsService, LlmCallLogService llmCallLogService,
        MeterRegistry meterRegistry, LlmPricingCatalog pricingCatalog,
        LlmModelSupport modelSupport,
        @Value("${app.llm.bedrock.api-call-timeout:10m}") Duration apiCallTimeout,
        @Value("${app.llm.bedrock.api-call-attempt-timeout:5m}") Duration apiCallAttemptTimeout,
        @Value("${app.llm.bedrock.client-cache.max-size:64}") int maxCachedClients
    ) {
        this.llmCallMetricsService = llmCallMetricsService;
        this.llmCallLogService = llmCallLogService;
        this.meterRegistry = meterRegistry;
        this.pricingCatalog = pricingCatalog;
        this.modelSupport = modelSupport;
        this.apiCallTimeout = apiCallTimeout;
        this.apiCallAttemptTimeout = apiCallAttemptTimeout;
        this.maxCachedClients = Math.max(1, maxCachedClients);
    }

    @Override
    public ProviderName supportedProvider() {
        return ProviderName.AWS_BEDROCK;
    }

    @Override
    public ChatClient create(String apiKey, String model, int maxTokens, double temperature, double topP) {
        AwsBedrockCredential credential = AwsBedrockCredential.decode(apiKey);
        BedrockClients clients = clientsFor(credential);

        AwsBedrockChatModel chatModel = new AwsBedrockChatModel(
            clients.sync(), clients.async(), model, maxTokens, temperature, topP);

        return modelSupport.chatClientBuilder(chatModel)
            .defaultAdvisors(modelSupport.advisors(
                new LlmMetricsAdvisor(llmCallMetricsService, llmCallLogService, ProviderName.AWS_BEDROCK, model, meterRegistry, pricingCatalog)))
            .build();
    }

    /** Cached clients for this credential, building (and possibly evicting the LRU entry) on a miss. */
    BedrockClients clientsFor(AwsBedrockCredential credential) {
        String key = fingerprint(credential);
        BedrockClients result;
        BedrockClients evicted = null;
        clientCacheLock.lock();
        try {
            result = clientCache.get(key);
            if (result == null) {
                result = buildClients(credential);
                clientCache.put(key, result);
                if (clientCache.size() > maxCachedClients) {
                    Iterator<Map.Entry<String, BedrockClients>> eldest = clientCache.entrySet().iterator();
                    evicted = eldest.next().getValue();
                    eldest.remove();
                }
            }
        } finally {
            clientCacheLock.unlock();
        }
        if (evicted != null) {
            // Closed outside the lock: shutting down an HTTP pool / event loop can take a moment.
            evicted.close();
        }
        return result;
    }

    /** Seam for tests; builds both clients with the configured timeouts. */
    BedrockClients buildClients(AwsBedrockCredential credential) {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(credential.accessKeyId(), credential.secretAccessKey()));
        Region region = Region.of(credential.region());
        ClientOverrideConfiguration overrides = ClientOverrideConfiguration.builder()
            .apiCallTimeout(apiCallTimeout)
            .apiCallAttemptTimeout(apiCallAttemptTimeout)
            .build();

        BedrockRuntimeClient sync = BedrockRuntimeClient.builder()
            .region(region)
            .credentialsProvider(credentials)
            .overrideConfiguration(overrides)
            .build();
        BedrockRuntimeAsyncClient async;
        try {
            async = BedrockRuntimeAsyncClient.builder()
                .region(region)
                .credentialsProvider(credentials)
                .overrideConfiguration(overrides)
                .build();
        } catch (RuntimeException e) {
            // No async HTTP implementation on the classpath (netty-nio-client is
            // a transitive runtime dependency of the SDK today, so this should
            // not happen) - fall back to AwsBedrockChatModel's call()-backed stream().
            log.warn("AWS Bedrock async client unavailable, streaming degrades to a single blocking chunk: {}", e.toString());
            async = null;
        }
        return new BedrockClients(sync, async);
    }

    int cachedClientCount() {
        clientCacheLock.lock();
        try {
            return clientCache.size();
        } finally {
            clientCacheLock.unlock();
        }
    }

    /** SHA-256 over key id, secret and region - never the raw secret as a key. */
    static String fingerprint(AwsBedrockCredential credential) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {credential.accessKeyId(), credential.secretAccessKey(), credential.region()}) {
                digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @PreDestroy
    void closeAll() {
        List<BedrockClients> all;
        clientCacheLock.lock();
        try {
            all = new ArrayList<>(clientCache.values());
            clientCache.clear();
        } finally {
            clientCacheLock.unlock();
        }
        all.forEach(BedrockClients::close);
    }
}
