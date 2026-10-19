package com.agenticai.core.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.agenticai.core.providers.AwsBedrockCredential;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * The factory used to build (and leak) a fresh Bedrock client per chat
 * turn. Locks in: clients are reused per credential, keyed by a SHA-256
 * fingerprint that never contains the raw secret, and the LRU closes what
 * it evicts.
 */
class AwsBedrockAgentChatClientFactoryTest {

    /** The shared model-construction policy the factory now needs for its
     * {@code ChatClient.builder} + advisor wiring - built with a NOOP
     * ObservationRegistry and no DefaultAgentAdvisor beans, so the only
     * advisor on the built client is the per-call {@code LlmMetricsAdvisor}. */
    @SuppressWarnings("unchecked")
    private static LlmModelSupport modelSupport() {
        org.springframework.beans.factory.ObjectProvider<io.micrometer.observation.ObservationRegistry> registryProvider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(registryProvider.getIfAvailable(org.mockito.ArgumentMatchers.any()))
            .thenReturn(io.micrometer.observation.ObservationRegistry.NOOP);
        org.springframework.beans.factory.ObjectProvider<DefaultAgentAdvisor> advisorProvider =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        return new LlmModelSupport(registryProvider, advisorProvider,
            2, Duration.ofMillis(1), Duration.ofMillis(2), true, "MISTRAL", "low",
            16, Duration.ofMinutes(30), false);
    }

    /** Builds mock clients instead of real SDK clients, and records them. */
    private static final class RecordingFactory extends AwsBedrockAgentChatClientFactory {
        final List<BedrockClients> built = new ArrayList<>();

        RecordingFactory(int maxSize) {
            super(new LlmCallMetricsService(), mock(LlmCallLogService.class), new SimpleMeterRegistry(),
                new LlmPricingCatalog(), modelSupport(), Duration.ofMinutes(10), Duration.ofMinutes(5), maxSize);
        }

        @Override
        BedrockClients buildClients(AwsBedrockCredential credential) {
            BedrockClients clients = new BedrockClients(mock(BedrockRuntimeClient.class), mock(BedrockRuntimeAsyncClient.class));
            built.add(clients);
            return clients;
        }
    }

    private static AwsBedrockCredential cred(String keyId) {
        return new AwsBedrockCredential(keyId, "secret-" + keyId, "us-east-1");
    }

    @Test
    void fingerprintIsStableAndNeverContainsTheSecret() {
        AwsBedrockCredential credential = new AwsBedrockCredential("AKIAEXAMPLE", "superSecretValue", "us-east-1");

        String fingerprint = AwsBedrockAgentChatClientFactory.fingerprint(credential);

        assertEquals(fingerprint, AwsBedrockAgentChatClientFactory.fingerprint(
            new AwsBedrockCredential("AKIAEXAMPLE", "superSecretValue", "us-east-1")));
        assertEquals(64, fingerprint.length());
        assertFalse(fingerprint.contains("superSecretValue"));
        assertNotEquals(fingerprint, AwsBedrockAgentChatClientFactory.fingerprint(
            new AwsBedrockCredential("AKIAEXAMPLE", "rotatedSecret", "us-east-1")));
        assertNotEquals(fingerprint, AwsBedrockAgentChatClientFactory.fingerprint(
            new AwsBedrockCredential("AKIAEXAMPLE", "superSecretValue", "eu-west-1")));
    }

    @Test
    void sameCredentialReusesClients() {
        RecordingFactory factory = new RecordingFactory(8);

        AwsBedrockAgentChatClientFactory.BedrockClients first = factory.clientsFor(cred("a"));
        AwsBedrockAgentChatClientFactory.BedrockClients second = factory.clientsFor(cred("a"));

        assertSame(first, second);
        assertEquals(1, factory.built.size());
    }

    @Test
    void createReusesCachedClientsAcrossTurns() {
        RecordingFactory factory = new RecordingFactory(8);
        String apiKey = cred("a").encode();

        assertNotNull(factory.create(apiKey, "anthropic.claude-3-5-sonnet-20241022-v2:0", 256, 0.2, 0.9));
        assertNotNull(factory.create(apiKey, "anthropic.claude-3-5-sonnet-20241022-v2:0", 256, 0.2, 0.9));

        assertEquals(1, factory.built.size());
    }

    @Test
    void evictsLeastRecentlyUsedAndClosesIt() throws Exception {
        RecordingFactory factory = new RecordingFactory(2);

        AwsBedrockAgentChatClientFactory.BedrockClients a = factory.clientsFor(cred("a"));
        AwsBedrockAgentChatClientFactory.BedrockClients b = factory.clientsFor(cred("b"));
        factory.clientsFor(cred("a"));   // touch a, so b is now least recently used
        factory.clientsFor(cred("c"));   // evicts b

        assertEquals(2, factory.cachedClientCount());
        verify(b.sync()).close();
        verify(b.async()).close();
        verify(a.sync(), never()).close();
        assertSame(a, factory.clientsFor(cred("a")));
    }

    @Test
    void closeAllClosesEverything() throws Exception {
        RecordingFactory factory = new RecordingFactory(8);
        AwsBedrockAgentChatClientFactory.BedrockClients a = factory.clientsFor(cred("a"));
        AwsBedrockAgentChatClientFactory.BedrockClients b = factory.clientsFor(cred("b"));

        factory.closeAll();

        verify(a.sync()).close();
        verify(a.async()).close();
        verify(b.sync()).close();
        verify(b.async()).close();
        assertEquals(0, factory.cachedClientCount());
    }
}
