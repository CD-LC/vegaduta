package com.agenticai.core.agents.tools.web;

import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Builds the {@link WebSearchProviderRegistry} from {@code app.web-search.*}. Every value defaults
 * to "today": provider {@code brave}, no fallback, no caps - in which state the registry reports
 * {@link WebSearchProviderRegistry#multiProviderActive()} = false and the tool resolver ignores it.
 *
 * <p>Platform keys resolve through {@link PlatformToolCredentialService} at CALL time (the same
 * Tool Credentials store the Brave key already uses). The SearXNG base URL is deliberately operator
 * configuration only - never a database/admin-UI value - because it is the one address an
 * agent-facing tool may reach inside the network (see {@link SearxngWebSearchProvider}).
 */
@Configuration
public class WebSearchProviderWiring {

    @Bean
    public WebSearchProviderRegistry webSearchProviderRegistry(
            ObjectProvider<PlatformToolCredentialService> credentials,
            ObjectProvider<StringRedisTemplate> redis,
            @Value("${app.web-search.provider:brave}") String provider,
            @Value("${app.web-search.fallback:}") String fallback,
            @Value("${app.web-search.searxng.base-url:}") String searxngBaseUrl,
            @Value("${app.web-search.searxng.engines:}") String searxngEngines,
            @Value("${app.web-search.searxng.categories:general}") String searxngCategories,
            @Value("${app.web-search.searxng.language:}") String searxngLanguage,
            @Value("${app.web-search.searxng.max-results:10}") int searxngMaxResults,
            @Value("${app.web-search.daily-caps.brave:0}") int capBrave,
            @Value("${app.web-search.daily-caps.searxng:0}") int capSearxng,
            @Value("${app.web-search.daily-caps.tavily:0}") int capTavily,
            @Value("${app.web-search.daily-caps.exa:0}") int capExa,
            @Value("${app.web-search.daily-caps.serper:0}") int capSerper) {
        List<WebSearchProvider> all = new ArrayList<>();
        all.add(
                new BraveWebSearchProvider(
                        new BraveWebSearchClient(),
                        keyOf(credentials, PlatformToolKey.BRAVE_SEARCH)));
        all.add(
                new SearxngWebSearchProvider(
                        searxngBaseUrl,
                        searxngEngines,
                        searxngCategories,
                        searxngLanguage,
                        searxngMaxResults));
        all.add(new TavilyWebSearchProvider(keyOf(credentials, PlatformToolKey.TAVILY_SEARCH)));
        all.add(new ExaWebSearchProvider(keyOf(credentials, PlatformToolKey.EXA_SEARCH)));
        all.add(new SerperWebSearchProvider(keyOf(credentials, PlatformToolKey.SERPER_SEARCH)));

        Map<String, Integer> caps = new HashMap<>();
        caps.put("brave", capBrave);
        caps.put("searxng", capSearxng);
        caps.put("tavily", capTavily);
        caps.put("exa", capExa);
        caps.put("serper", capSerper);

        DailyWindowCounter counter = new DailyWindowCounter(redis);
        return new WebSearchProviderRegistry(
                all,
                provider,
                WebSearchProviderRegistry.parseList(fallback),
                caps,
                new WebSearchProviderRegistry.CapCounter() {
                    @Override
                    public long add(String key, long by) {
                        return counter.add(key, by);
                    }

                    @Override
                    public void undo(String key, long by) {
                        counter.undo(key, by);
                    }

                    @Override
                    public long current(String key) {
                        return counter.current(key);
                    }
                });
    }

    /**
     * The platform key for {@code toolKey}, read at call time; null when the store or the row is
     * absent.
     */
    private static Supplier<String> keyOf(
            ObjectProvider<PlatformToolCredentialService> credentials, String toolKey) {
        return () -> {
            PlatformToolCredentialService service = credentials.getIfAvailable();
            return service == null ? null : service.resolveActiveApiKey(toolKey).orElse(null);
        };
    }
}
