package com.agenticai.core.agents.tools;

import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.tools.web.BraveWebSearchClient;
import com.agenticai.core.agents.tools.web.DeepResearchConfig;
import com.agenticai.core.agents.tools.web.DeepResearchService;
import com.agenticai.core.agents.tools.web.DeepResearchSession;
import com.agenticai.core.agents.tools.web.DeepResearchToolCallback;
import com.agenticai.core.agents.tools.web.FetchWebPageToolCallback;
import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.agents.tools.web.HttpWebTaskBackend;
import com.agenticai.core.agents.tools.web.ResearchIntent;
import com.agenticai.core.agents.tools.web.ResearchUnavailableToolCallback;
import com.agenticai.core.agents.tools.web.VerifyCitationsToolCallback;
import com.agenticai.core.agents.tools.web.WebPageFetcher;
import com.agenticai.core.agents.tools.web.WebSearchProviderRegistry;
import com.agenticai.core.agents.tools.web.WebSearchToolCallback;
import com.agenticai.core.agents.tools.web.WebTaskToolCallback;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The built-in web tools ({@code fetch_web_page}, {@code http_request}, {@code web_search}, {@code
 * deep_research} / {@code verify_citations}, {@code web_task}) behind {@link WebToolPort}. Moved out
 * of {@code AgentIntegrationToolResolver} with {@code tools.web} (ADR-0023); the offer gates and the
 * per-turn research state are unchanged. The resolver still wraps each returned tool in the tool
 * guard (and deep_research in the turn's run budget).
 *
 * <p>Research capability, job progress and daily allowance arrive through {@link
 * ResearchToolWiring}; tuning through {@link #applyResearchTuning}. Both are setters so the
 * test-built instances need none of them.
 */
final class BuiltInWebTools implements WebToolPort {

    private final AgentService agentService;
    private final ObjectProvider<PlatformToolCredentialService> platformToolCredentialService;
    // Built-in "call any HTTP API" tool (http_request), an injected bean because it resolves
    // {{secret.NAME}} vault references. Absent = the tool is not offered.
    private final ObjectProvider<HttpRequestExecutor> httpRequestExecutor;
    // fetch_web_page: plain `new`, no Spring/service dependencies (JDK HttpClient + its own SSRF
    // validation). One shared instance so the HttpClient's connection pool is reused across turns.
    private final WebPageFetcher webPageFetcher = new WebPageFetcher();
    // Same self-constructed, stateless posture: it holds only an HttpClient.
    private final BraveWebSearchClient braveWebSearchClient = new BraveWebSearchClient();

    /** deep_research bounds (app.research.deep.*); disabled when absent (slim test contexts). */
    private volatile DeepResearchConfig deepResearchConfig;

    /**
     * deep_research evidence/ceilings per USER turn (shared with delegates via ResearchTurnScope).
     */
    private final ResearchTurnScope.Sessions<DeepResearchSession> researchSessions =
            new ResearchTurnScope.Sessions<>();

    private final ResearchCapabilitySupport research = new ResearchCapabilitySupport();

    // Test seam ONLY (never set in production): drives deep_research and verify_citations through
    // the real session/budget/progress wiring without the network.
    private DeepResearchService.Searcher researchSearcherOverride;
    private DeepResearchService.Fetcher researchFetcherOverride;

    BuiltInWebTools(
            AgentService agentService,
            ObjectProvider<PlatformToolCredentialService> platformToolCredentialService,
            ObjectProvider<HttpRequestExecutor> httpRequestExecutor,
            DeepResearchConfig deepResearchConfig) {
        this.agentService = agentService;
        this.platformToolCredentialService = platformToolCredentialService;
        this.httpRequestExecutor = httpRequestExecutor;
        this.deepResearchConfig =
                deepResearchConfig == null ? DeepResearchConfig.disabled() : deepResearchConfig;
    }

    /** The research collaborators (capability rows, daily allowance, job progress, registry). */
    ResearchCapabilitySupport research() {
        return research;
    }

    DeepResearchConfig deepResearchConfig() {
        return deepResearchConfig;
    }

    void setResearchTransportForTest(
            DeepResearchService.Searcher searcher, DeepResearchService.Fetcher fetcher) {
        this.researchSearcherOverride = searcher;
        this.researchFetcherOverride = fetcher;
    }

    /** Tuning not part of DeepResearchConfig's short constructor (deadline, quotes, intent offer). */
    void applyResearchTuning(
            int totalDeadlineSeconds, int minQuoteChars, boolean autoOfferOnIntent) {
        DeepResearchConfig c = this.deepResearchConfig;
        this.deepResearchConfig =
                new DeepResearchConfig(
                        c.enabled(),
                        c.maxWorkers(),
                        c.maxSources(),
                        c.fetchChars(),
                        c.maxFetchesPerRun(),
                        c.passagesPerSource(),
                        c.resultsPerSearch(),
                        c.workerTimeoutSeconds(),
                        c.maxSearchesPerRun(),
                        totalDeadlineSeconds,
                        minQuoteChars,
                        autoOfferOnIntent);
    }

    @Override
    public boolean researchCapabilityEnabled(UUID agentId) {
        return research.capabilityEnabled(agentId, deepResearchConfig.enabled());
    }

    /**
     * Built-in live web access ({@code fetch_web_page}) - offered unconditionally for every agent:
     * no provider credential, profile, or per-agent opt-in is involved, so there is no cheap
     * boolean to gate on at offer time. Enforcement happens at call time inside {@link
     * WebPageFetcher} (SSRF validation on every redirect hop, 2MB response cap, output-char cap)
     * and comes back as a short "Could not fetch &lt;url&gt;: &lt;reason&gt;", never a thrown
     * exception. {@code http_request} rides alongside under the same posture; {@code
     * app.tools.http-request.*} decides what is offered ({@link HttpRequestToolOffer}).
     */
    @Override
    public List<ToolCallback> pageAndHttpTools(UUID agentId) {
        List<ToolCallback> web = new ArrayList<>();
        web.add(new FetchWebPageToolCallback(webPageFetcher));
        HttpRequestExecutor executor =
                httpRequestExecutor == null ? null : httpRequestExecutor.getIfAvailable();
        if (executor != null) {
            web.addAll(HttpRequestToolOffer.callbacks(executor, agentId));
        }
        return web;
    }

    @Override
    public SearchTools searchTools(
            UUID agentId, String sessionId, AtomicInteger turnKey, String userMessage) {
        PlatformToolCredentialService credentials =
                platformToolCredentialService == null
                        ? null
                        : platformToolCredentialService.getIfAvailable();
        ToolCallback webSearch = null;
        ToolCallback deepResearch = null;
        ToolCallback verify = null;
        // web_search: a live callback when a platform Brave key exists (multi-provider mode: when
        // ANY provider in the chain is configured); otherwise the refusing stub. A silently absent
        // tool is worse than a cheap refusal: the model then concludes it has no web access and
        // answers a research request from stale memory. The key itself is resolved at CALL time, so
        // rotating or removing it needs no redeploy.
        WebSearchProviderRegistry searchRegistry = research.activeSearchRegistry();
        boolean searchConfigured =
                searchRegistry != null
                        ? searchRegistry.anyConfigured()
                        : credentials != null
                                && credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH).isPresent();
        webSearch = WebSearchToolCallback.unavailable();
        if (searchConfigured) {
            webSearch =
                    searchRegistry != null
                            ? new WebSearchToolCallback(searchRegistry)
                            : new WebSearchToolCallback(
                                    braveWebSearchClient,
                                    () ->
                                            credentials
                                                    .resolveActiveApiKey(
                                                            PlatformToolKey.BRAVE_SEARCH)
                                                    .orElse(null));
            // deep_research + verify_citations ride on the same platform search key. Gated by
            // app.research.deep.enabled (default OFF). One session per USER turn (turnKey) so
            // dedupe, the search/fetch ceilings and citation verification all share state.
            DeepResearchConfig researchCfg = deepResearchConfig;
            if (researchCfg.enabled() && researchToolsWanted(agentId, userMessage, researchCfg)) {
                DeepResearchSession researchSession =
                        researchSessions.forTurn(
                                turnKey,
                                () ->
                                        new DeepResearchSession(
                                                researchCfg.maxFetchesPerRun(),
                                                researchCfg.maxSearchesPerRun()));
                DeepResearchService researchService =
                        new DeepResearchService(
                                researchCfg,
                                researchSearcher(searchRegistry, credentials),
                                researchFetcherOverride != null
                                        ? researchFetcherOverride
                                        : (url, maxChars) -> webPageFetcher.fetch(url, maxChars),
                                research.progressSink(sessionId));
                deepResearch =
                        new DeepResearchToolCallback(
                                researchService,
                                researchSession,
                                research.searchAllowance(agentId));
                verify = new VerifyCitationsToolCallback(researchSession, researchCfg);
                if (ResearchTurnScope.mode() == ResearchTurnScope.Mode.STATELESS) {
                    // Edge / approval-replay resolve a fresh tool set just to call one tool by
                    // name; the turn's evidence is gone, so fail closed with a clear message.
                    deepResearch = new ResearchUnavailableToolCallback(deepResearch);
                    verify = new ResearchUnavailableToolCallback(verify);
                }
            }
        }
        return new SearchTools(webSearch, deepResearch, verify, webTask(credentials));
    }

    private DeepResearchService.Searcher researchSearcher(
            WebSearchProviderRegistry searchRegistry, PlatformToolCredentialService credentials) {
        if (researchSearcherOverride != null) {
            return researchSearcherOverride;
        }
        if (searchRegistry != null) {
            return (query, count) -> searchRegistry.search(query, count).results();
        }
        return (query, count) ->
                braveWebSearchClient.search(
                        query,
                        count,
                        credentials.resolveActiveApiKey(PlatformToolKey.BRAVE_SEARCH).orElse(null));
    }

    /**
     * web_task: managed browser automation. Offered only when the platform credential exists AND
     * carries a base URL in extraConfig. The backend is built per call from the resolved credential
     * so rotation/base-url changes take effect immediately.
     */
    private static ToolCallback webTask(PlatformToolCredentialService credentials) {
        if (credentials == null
                || credentials.resolveActiveApiKey(PlatformToolKey.BROWSER_TASK).isEmpty()
                || credentials.resolveActiveExtraConfig(PlatformToolKey.BROWSER_TASK).isEmpty()) {
            return null;
        }
        return new WebTaskToolCallback(
                () -> {
                    String key =
                            credentials
                                    .resolveActiveApiKey(PlatformToolKey.BROWSER_TASK)
                                    .orElse(null);
                    String base =
                            credentials
                                    .resolveActiveExtraConfig(PlatformToolKey.BROWSER_TASK)
                                    .orElse(null);
                    if (key == null || base == null) {
                        return null;
                    }
                    return new HttpWebTaskBackend(base, key);
                });
    }

    /**
     * Whether this resolution gets the two research schemas (~600-800 prompt tokens). Only a normal
     * chat turn is gated: an explicit allow-list entry for deep_research / verify_citations, the
     * agent's "Deep research" capability switch, OR a user message that looks like a research
     * request. Discovery and the stateless lookup path are never sent to a model, so they are not
     * gated.
     */
    private boolean researchToolsWanted(UUID agentId, String userMessage, DeepResearchConfig cfg) {
        if (ResearchTurnScope.mode() != ResearchTurnScope.Mode.TURN) {
            return true;
        }
        if (cfg.autoOfferOnIntent() && ResearchIntent.looksLikeResearch(userMessage)) {
            return true;
        }
        if (agentId == null) {
            return false;
        }
        if (agentService != null) {
            try {
                java.util.Set<String> allowed = agentService.getAllowedToolNames(agentId);
                if (allowed != null
                        && (allowed.contains(DeepResearchToolCallback.TOOL_NAME)
                                || allowed.contains(VerifyCitationsToolCallback.TOOL_NAME))) {
                    return true;
                }
            } catch (RuntimeException e) {
                // fall through to the capability row
            }
        }
        // The labelled per-agent switch (DeepResearchCapability): same opt-in, no raw tool names.
        return researchCapabilityEnabled(agentId);
    }
}
