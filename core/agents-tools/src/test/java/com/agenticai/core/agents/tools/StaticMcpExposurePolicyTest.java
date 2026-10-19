package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.PlatformSettingsService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 2026-09-28 cross-tenant leak fix: application-mcp.yml's static servers are one process shared by
 * every tenant, so only an explicit stateless allowlist is exposed unless the platform flag opens
 * everything - see {@link StaticMcpExposurePolicy}.
 */
class StaticMcpExposurePolicyTest {

    static McpSyncClient client(String connectionName) {
        McpSyncClient client = mock(McpSyncClient.class);
        // Spring AI's McpClientAutoConfiguration puts the yml connection name in clientInfo.title.
        when(client.getClientInfo())
                .thenReturn(
                        new McpSchema.Implementation(
                                "spring-ai-mcp-client - " + connectionName,
                                connectionName,
                                "1.0.0"));
        return client;
    }

    private static final List<String> ALL_STATIC =
            List.of(
                    "filesystem",
                    "memory",
                    "sequential-thinking",
                    "puppeteer",
                    "playwright-vision");

    private static List<McpSyncClient> allStaticClients() {
        return ALL_STATIC.stream().map(StaticMcpExposurePolicyTest::client).toList();
    }

    @Test
    void failClosedDefaultExposesOnlySequentialThinking() {
        List<McpSyncClient> exposed =
                StaticMcpExposurePolicy.FAIL_CLOSED.exposedClients(allStaticClients());

        assertEquals(
                List.of("sequential-thinking"),
                exposed.stream().map(StaticMcpExposurePolicy::serverNameOf).toList());
        assertEquals(
                List.of("filesystem", "memory", "puppeteer", "playwright-vision"),
                StaticMcpExposurePolicy.FAIL_CLOSED.hiddenServerNames(allStaticClients()));
    }

    @Test
    void aNewlyAddedOrUnnamedStaticServerIsHiddenByDefault() {
        StaticMcpExposurePolicy policy =
                new StaticMcpExposurePolicy(List.of("sequential-thinking"), () -> false);
        McpSyncClient unnamed = mock(McpSyncClient.class);

        assertFalse(policy.isExposed("some-new-server"));
        assertFalse(policy.isExposed(null));
        assertTrue(policy.exposedClients(List.of(client("some-new-server"), unnamed)).isEmpty());
    }

    @Test
    void sharedStatefulFlagExposesEveryStaticServer() {
        StaticMcpExposurePolicy policy =
                new StaticMcpExposurePolicy(List.of("sequential-thinking"), () -> true);

        assertEquals(5, policy.exposedClients(allStaticClients()).size());
        assertTrue(policy.isExposed("filesystem"));
        assertTrue(policy.hiddenServerNames(allStaticClients()).isEmpty());
    }

    @Test
    void allowlistIsConfigurable() {
        StaticMcpExposurePolicy policy =
                new StaticMcpExposurePolicy(
                        List.of(" sequential-thinking ", "time", ""), () -> false);

        assertTrue(policy.isExposed("sequential-thinking"));
        assertTrue(policy.isExposed("time"));
        assertFalse(policy.isExposed("memory"));
    }

    @Test
    void springConstructorParsesTheCommaSeparatedAllowlistAndReadsThePlatformFlag() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getBoolean(StaticMcpExposurePolicy.SHARED_STATEFUL_ENABLED_KEY, false))
                .thenReturn(false);
        StaticMcpExposurePolicy policy =
                new StaticMcpExposurePolicy(
                        providerOf(settings),
                        providerOf(null),
                        false,
                        "sequential-thinking, memory");

        assertTrue(policy.isExposed("memory"));
        assertFalse(policy.isExposed("filesystem"));
    }

    @Test
    void cachedFlagReadsThePlatformSettingWithinTtlOnceAndFallsBackOnError() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.getBoolean(StaticMcpExposurePolicy.SHARED_STATEFUL_ENABLED_KEY, false))
                .thenReturn(true);
        Clock clock = Clock.fixed(Instant.parse("2026-09-28T00:00:00Z"), ZoneOffset.UTC);
        StaticMcpExposurePolicy.CachedFlag flag =
                new StaticMcpExposurePolicy.CachedFlag(providerOf(settings), false, clock);

        assertTrue(flag.getAsBoolean());
        assertTrue(flag.getAsBoolean());
        verify(settings, times(1))
                .getBoolean(StaticMcpExposurePolicy.SHARED_STATEFUL_ENABLED_KEY, false);

        PlatformSettingsService broken = mock(PlatformSettingsService.class);
        when(broken.getBoolean(StaticMcpExposurePolicy.SHARED_STATEFUL_ENABLED_KEY, false))
                .thenThrow(new IllegalStateException("db down"));
        assertFalse(
                new StaticMcpExposurePolicy.CachedFlag(providerOf(broken), false, clock)
                        .getAsBoolean());
        // No settings bean at all -> the @Value fallback.
        assertTrue(
                new StaticMcpExposurePolicy.CachedFlag(providerOf(null), true, clock)
                        .getAsBoolean());
    }

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        // AgentMcpToolResolver calls the one-arg overload (getIfAvailable(Supplier<T>)) for its
        // static client list, not the no-arg one stubbed above - without this, Mockito's default
        // null answer for the unstubbed overload silently starves every resolver test of clients.
        when(provider.getIfAvailable(
                        org.mockito.ArgumentMatchers.<java.util.function.Supplier<T>>any()))
                .thenAnswer(
                        invocation ->
                                value != null
                                        ? value
                                        : invocation
                                                .<java.util.function.Supplier<T>>getArgument(0)
                                                .get());
        return provider;
    }
}
