package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.web.BraveWebSearchClient;
import com.agenticai.core.agents.tools.web.DeepResearchConfig;
import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.agents.tools.web.WebSearchProvider;
import com.agenticai.core.agents.tools.web.WebSearchProviderRegistry;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Whether {@code web_search} is offered: with no registry wired, or the shipped defaults
 * (provider=brave, no fallback), exactly the original Brave-key gate (identity); once the layer is
 * active, "any provider in the chain is configured". {@code guardAndLog} is stubbed to pass the raw
 * callback through. The tools come from core/agents-browser's {@link BuiltInWebTools} behind the
 * kernel's {@link WebToolPort}; the resolver only guards them.
 */
class AgentIntegrationToolResolverWebSearchProviderTest {

    private PlatformToolCredentialService credentials;
    private AgentIntegrationToolResolver resolver;
    private BuiltInWebTools webTools;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        AgentToolGuard toolGuard = mock(AgentToolGuard.class);
        when(toolGuard.guardAndLog(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(0));
        credentials = mock(PlatformToolCredentialService.class);
        ObjectProvider<PlatformToolCredentialService> credentialProvider =
                mock(ObjectProvider.class);
        when(credentialProvider.getIfAvailable()).thenReturn(credentials);
        ObjectProvider<HttpRequestExecutor> http = mock(ObjectProvider.class);
        webTools = new BuiltInWebTools(null, credentialProvider, http, DeepResearchConfig.disabled());
        ObjectProvider<WebToolPort> port = mock(ObjectProvider.class);
        when(port.getIfAvailable()).thenReturn(webTools);
        resolver =
                new AgentIntegrationToolResolver(
                        toolGuard,
                        null,
                        List.of(),
                        null,
                        null,
                        mock(ObjectProvider.class),
                        port,
                        mock(ObjectProvider.class),
                        null);
    }

    private static WebSearchProvider fake(String id, boolean configured) {
        return new WebSearchProvider() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public boolean configured() {
                return configured;
            }

            @Override
            public String notConfiguredMessage() {
                return "not configured";
            }

            @Override
            public List<BraveWebSearchClient.SearchResult> search(String query, Integer count) {
                return List.of();
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<WebSearchProviderRegistry> provide(
            String primary,
            String fallback,
            boolean braveConfigured,
            boolean tavilyConfigured,
            boolean searxngConfigured) {
        List<WebSearchProvider> all =
                new ArrayList<>(
                        List.of(
                                fake("brave", braveConfigured),
                                fake("searxng", searxngConfigured),
                                fake("tavily", tavilyConfigured)));
        WebSearchProviderRegistry registry =
                new WebSearchProviderRegistry(
                        all,
                        primary,
                        WebSearchProviderRegistry.parseList(fallback),
                        Map.of(),
                        null);
        ObjectProvider<WebSearchProviderRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    /**
     * The offered {@code web_search} callback, or null. The schema is ALWAYS offered now - what the
     * gate decides is whether the tool is LIVE or the refusing stub ({@code isUnavailable()}), so a
     * misconfigured deployment is an honest tool result instead of a tool the model cannot see.
     */
    private com.agenticai.core.agents.tools.web.WebSearchToolCallback webSearch() {
        List<ToolCallback> tools =
                resolver.resolveWebTools(
                        UUID.randomUUID(), "s1", new AtomicInteger(), "hello", null, false);
        return tools.stream()
                .filter(t -> "web_search".equals(t.getToolDefinition().name()))
                .map(t -> (com.agenticai.core.agents.tools.web.WebSearchToolCallback) t)
                .findFirst()
                .orElse(null);
    }

    private boolean liveSearchOffered() {
        var t = webSearch();
        return t != null && !t.isUnavailable();
    }

    private boolean stubSearchOffered() {
        var t = webSearch();
        return t != null && t.isUnavailable();
    }

    @Test
    void noRegistryWired_theBraveKeyDecidesExactlyAsBefore() {
        assertTrue(stubSearchOffered());
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        assertTrue(liveSearchOffered());
    }

    @Test
    void theStubAnswersWithTheOperatorFacingReason() {
        String out = webSearch().call("{\"query\":\"atlas electric robot\"}");
        assertTrue(out.contains("Could not search"));
        assertTrue(out.contains("not configured"));
        // Points at the escape hatch that still works without a provider.
        assertTrue(out.contains("fetch_web_page"));
    }

    @Test
    void defaultsKeepTheBraveGateEvenWhenAnotherProviderIsConfigured() {
        // provider=brave, no fallback: the layer is inactive, so a configured SearXNG/Tavily must
        // NOT light the tool.
        webTools.research().setWebSearchRegistry(provide("brave", "", false, true, true));
        assertTrue(stubSearchOffered());
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        assertTrue(liveSearchOffered());
    }

    @Test
    void anActiveLayerOffersTheToolWithoutAnyBraveKeyWhenTheChosenProviderIsConfigured() {
        webTools.research().setWebSearchRegistry(provide("searxng", "", false, false, true));
        assertTrue(liveSearchOffered());
    }

    @Test
    void anActiveLayerWithNothingConfiguredOffersTheStubEvenIfABraveKeyExists() {
        // searxng is chosen and unconfigured; Brave is not in the chain, so its key is irrelevant.
        when(credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH))
                .thenReturn(Optional.of("k"));
        webTools.research().setWebSearchRegistry(provide("searxng", "", true, false, false));
        assertTrue(stubSearchOffered());
    }

    @Test
    void aFallbackOnlyNeedsOneConfiguredMemberOfTheChain() {
        webTools.research().setWebSearchRegistry(provide("brave", "tavily", false, true, false));
        assertTrue(liveSearchOffered());
        webTools.research().setWebSearchRegistry(provide("brave", "tavily", false, false, false));
        assertTrue(stubSearchOffered());
        assertEquals(
                2, provide("brave", "tavily", false, false, false).getIfAvailable().chain().size());
    }
}
