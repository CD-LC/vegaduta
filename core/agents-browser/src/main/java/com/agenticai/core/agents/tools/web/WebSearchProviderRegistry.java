package com.agenticai.core.agents.tools.web;

import com.agenticai.core.agents.capability.WebSearchAvailability;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picks the {@link WebSearchProvider} for a search and degrades down an ordered fallback list.
 *
 * <p><b>Selection.</b> {@code app.web-search.provider} (default {@code brave}) is tried first, then
 * each id in {@code app.web-search.fallback} (default empty) in order. A provider is skipped - with
 * the reason kept for an honest note - when it is not configured, when its optional per-provider
 * daily cap ({@code app.web-search.daily-caps.<id>}, 0 = unlimited) is used up, or when the call
 * itself fails (key rejected, rate limit, quota, outage, bad response). An EMPTY result list is a
 * real answer, not a failure, and does not fall through.
 *
 * <p><b>Flag-off identity.</b> {@link #multiProviderActive()} is false for the shipped defaults
 * (provider {@code brave}, no fallback). In that state the tool resolver does not consult this
 * registry at all and the {@code web_search} / {@code deep_research} path is byte-for-byte today's
 * direct Brave call.
 *
 * <p>Caps count ATTEMPTS the platform makes against a provider (a call that then fails still used a
 * request), through a Redis fixed 24h window shared with the other search counters; Redis being
 * away fails open. The window is per platform, not per tenant: a provider cap protects the
 * operator's bill, per-user fairness stays with {@code DeepResearchDailyAllowance}.
 */
public class WebSearchProviderRegistry implements WebSearchAvailability {

    private static final Logger log = LoggerFactory.getLogger(WebSearchProviderRegistry.class);

    static final String CAP_KEY_PREFIX = "agentic:web-search:daily:";

    /**
     * The provider whose behaviour is "today's" and the default when configuration names nothing
     * valid.
     */
    static final String DEFAULT_PROVIDER = BraveWebSearchProvider.PROVIDER_ID;

    /** The slice of {@link DailyWindowCounter} the caps need, so tests need no Redis. */
    public interface CapCounter {
        /**
         * Adds {@code by} to the window; returns the new count, or 0 when nothing could be counted
         * (fail-open).
         */
        long add(String key, long by);

        /** Takes back what this caller just added. */
        void undo(String key, long by);

        /** Current count (0 when none / Redis away). */
        long current(String key);
    }

    /**
     * A completed search: the results, which provider answered, and an honest note when others were
     * skipped.
     */
    public record Outcome(
            List<BraveWebSearchClient.SearchResult> results, String providerId, String note) {}

    /** One provider as the admin view shows it. {@code role}: primary | fallback | unused. */
    public record ProviderStatus(
            String id,
            String label,
            boolean configured,
            String role,
            int fallbackPosition,
            int dailyCap,
            long usedToday,
            boolean active) {}

    /**
     * The whole picture for the admin view. {@code activeNow} = who would answer a search right now
     * (null = nobody).
     */
    public record Status(
            boolean multiProviderActive,
            String primary,
            List<String> fallback,
            String activeNow,
            List<ProviderStatus> providers) {}

    private final Map<String, WebSearchProvider> providers = new LinkedHashMap<>();
    private final String primary;
    private final List<String> fallback;
    private final Map<String, Integer> caps;
    private final CapCounter counter;

    /**
     * @param all every adapter this deployment can build, by their own {@link
     *     WebSearchProvider#id()}
     * @param primary configured primary id; unknown/blank -> {@code brave}
     * @param fallback ordered fallback ids; unknown ids are ignored with a warning, duplicates and
     *     the primary dropped
     * @param caps daily attempt cap per provider id; missing / <= 0 = unlimited
     * @param counter cap counter; null = caps are not enforced
     */
    public WebSearchProviderRegistry(
            List<WebSearchProvider> all,
            String primary,
            List<String> fallback,
            Map<String, Integer> caps,
            CapCounter counter) {
        for (WebSearchProvider p : all) {
            providers.put(p.id(), p);
        }
        String wanted = normalise(primary);
        if (wanted.isEmpty()) {
            wanted = DEFAULT_PROVIDER;
        } else if (!providers.containsKey(wanted)) {
            log.warn(
                    "app.web-search.provider '{}' is not a known provider ({}); using {}",
                    wanted,
                    providers.keySet(),
                    DEFAULT_PROVIDER);
            wanted = DEFAULT_PROVIDER;
        }
        this.primary = wanted;
        Set<String> order = new LinkedHashSet<>();
        for (String raw : fallback == null ? List.<String>of() : fallback) {
            String id = normalise(raw);
            if (id.isEmpty() || id.equals(this.primary)) {
                continue;
            }
            if (!providers.containsKey(id)) {
                log.warn(
                        "app.web-search.fallback entry '{}' is not a known provider ({}); ignored",
                        id,
                        providers.keySet());
                continue;
            }
            order.add(id);
        }
        this.fallback = List.copyOf(order);
        this.caps = caps == null ? Map.of() : Map.copyOf(caps);
        this.counter = counter;
    }

    /** Splits a comma-separated config value ({@code "tavily, exa"}) into ids. */
    public static List<String> parseList(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.strip());
                }
            }
        }
        return out;
    }

    private static String normalise(String id) {
        return id == null ? "" : id.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * True only when configuration departs from the shipped default (a non-Brave primary, or any
     * fallback). False = the resolver keeps its original direct Brave wiring.
     */
    public boolean multiProviderActive() {
        return !primary.equals(DEFAULT_PROVIDER) || !fallback.isEmpty();
    }

    /** The ordered chain: primary first, then fallbacks. */
    public List<WebSearchProvider> chain() {
        List<WebSearchProvider> chain = new ArrayList<>();
        chain.add(providers.get(primary));
        for (String id : fallback) {
            chain.add(providers.get(id));
        }
        return chain;
    }

    /**
     * Whether any provider in the chain has what it needs (cheap, non-throwing). Gates offering the
     * tool.
     */
    public boolean anyConfigured() {
        for (WebSearchProvider p : chain()) {
            if (p.configured()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The one gate every "is search available?" check shares: the original Brave-key test while the
     * registry is inactive (identity), "something in the chain is configured" once it is active.
     */
    @Override
    public boolean searchAvailable(java.util.function.BooleanSupplier braveKeyPresent) {
        return multiProviderActive() ? anyConfigured() : braveKeyPresent.getAsBoolean();
    }

    /**
     * Runs one search down the chain. Never returns null.
     *
     * @throws WebSearchException with a person-readable reason when no provider could answer; with
     *     a single provider in the chain the message is exactly that provider's own
     */
    public Outcome search(String query, Integer count) {
        if (query == null || query.isBlank()) {
            throw new WebSearchException("no search query was provided.");
        }
        List<WebSearchProvider> chain = chain();
        List<String> skipped = new ArrayList<>();
        WebSearchException only = null;
        for (WebSearchProvider provider : chain) {
            String reason;
            WebSearchException failure = null;
            if (!provider.configured()) {
                reason = "not configured";
                failure = new WebSearchException(provider.notConfiguredMessage());
            } else if (!reserve(provider.id())) {
                reason = "daily limit reached";
                failure =
                        new WebSearchException(
                                "web search has reached its daily limit for "
                                        + provider.label()
                                        + ". Try again later, or use a URL directly with fetch_web_page.");
            } else {
                try {
                    List<BraveWebSearchClient.SearchResult> results = provider.search(query, count);
                    return new Outcome(results, provider.id(), skipNote(skipped, provider));
                } catch (WebSearchException e) {
                    reason = trimSentence(e.getMessage());
                    failure = e;
                }
            }
            skipped.add(provider.label() + ": " + reason);
            only = failure;
        }
        if (chain.size() == 1 && only != null) {
            throw only;
        }
        throw new WebSearchException(
                "no search provider could answer (" + String.join("; ", skipped) + ").");
    }

    /**
     * The honest note for a result that came from something other than the first choice; null when
     * nothing was skipped.
     */
    private static String skipNote(List<String> skipped, WebSearchProvider answered) {
        if (skipped.isEmpty()) {
            return null;
        }
        return "Note: "
                + String.join("; ", skipped)
                + ". The results below come from "
                + answered.label()
                + " instead.";
    }

    /**
     * Messages are sentences ("web search failed (HTTP 500). Try again shortly."); inside a list
     * drop the final dot.
     */
    private static String trimSentence(String message) {
        if (message == null || message.isBlank()) {
            return "failed";
        }
        String s = message.strip();
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /** Counts one attempt against the provider's cap; false when the cap is already spent. */
    private boolean reserve(String id) {
        int cap = caps.getOrDefault(id, 0);
        if (cap <= 0 || counter == null) {
            return true;
        }
        String key = CAP_KEY_PREFIX + id;
        long used = counter.add(key, 1);
        if (used > cap) {
            counter.undo(key, 1);
            return false;
        }
        return true;
    }

    /** Whether this provider can take another call right now (read-only; does not count). */
    private boolean underCap(String id) {
        int cap = caps.getOrDefault(id, 0);
        return cap <= 0 || counter == null || counter.current(CAP_KEY_PREFIX + id) < cap;
    }

    /** Admin snapshot. Reads no secrets; "configured" is presence only, never a live check. */
    public Status status() {
        List<WebSearchProvider> chain = chain();
        String activeNow = null;
        for (WebSearchProvider p : chain) {
            if (p.configured() && underCap(p.id())) {
                activeNow = p.id();
                break;
            }
        }
        List<ProviderStatus> rows = new ArrayList<>();
        for (WebSearchProvider p : providers.values()) {
            int position = fallback.indexOf(p.id()) + 1;
            String role = p.id().equals(primary) ? "primary" : position > 0 ? "fallback" : "unused";
            int cap = Math.max(0, caps.getOrDefault(p.id(), 0));
            long used = cap > 0 && counter != null ? counter.current(CAP_KEY_PREFIX + p.id()) : 0;
            rows.add(
                    new ProviderStatus(
                            p.id(),
                            p.label(),
                            p.configured(),
                            role,
                            position,
                            cap,
                            used,
                            p.id().equals(activeNow)));
        }
        return new Status(multiProviderActive(), primary, fallback, activeNow, rows);
    }
}
