package com.agenticai.core.agents.tools.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.ai.tool.ToolCallback;

/**
 * The per-run (one {@code resolveToolsFor} call = one chat turn) state shared by {@link
 * SearchToolsToolCallback} and {@link CallToolToolCallback}: which tools were deferred, which of
 * those the model has searched for ("activated") so far, and the lazily built search index.
 *
 * <p>The callbacks held here are the SAME guard/log/step-event wrapped instances {@code
 * AgentToolProvider} would have handed the model directly, so running one through {@code call_tool}
 * passes the identical step ceiling, approval gate, classification gate, trifecta logic and output
 * sanitisation. Nothing in this class calls a tool itself.
 *
 * <p>Why activation is tracked rather than letting {@code call_tool} reach every deferred tool: the
 * model must have been shown a tool's schema (by {@code search_tools}) before it can call it, which
 * stops it dispatching guessed names with guessed arguments, and it is what the session carry-over
 * ({@link ToolActivationRegistry}) records.
 */
public final class ToolSearchState {

    private final Map<String, ToolCallback> exposed;
    private final Map<String, ToolCallback> deferred;
    private final ToolSearchService service;
    private final ToolActivationRegistry registry;
    private final String registryKey;
    private final int maxCarryOver;
    /**
     * True when every deferred entry's catalog text was screened at build time (the {@code
     * SearchCatalogScreen} of {@code ToolSearchDeferral}), which is what lets a search RESULT be
     * marked the platform's answer instead of re-screening it per call.
     */
    private final boolean catalogScreened;
    private final Set<String> activated = Collections.synchronizedSet(new LinkedHashSet<>());
    private volatile ToolSearchService.Index index;

    /**
     * @param exposed tools offered to the model directly this turn (callable through {@code
     *     call_tool} too)
     * @param deferred tools hidden behind {@code search_tools}, by exact tool name
     * @param registry nullable; when set, activations are remembered for later turns of the session
     * @param registryKey the session's key in {@code registry}
     * @param maxCarryOver how many activations the registry keeps per session; {@code <= 0}
     *     disables it
     */
    public ToolSearchState(
            Map<String, ToolCallback> exposed,
            Map<String, ToolCallback> deferred,
            ToolSearchService service,
            ToolActivationRegistry registry,
            String registryKey,
            int maxCarryOver) {
        this(exposed, deferred, service, registry, registryKey, maxCarryOver, false);
    }

    /**
     * @param catalogScreened true when {@code deferred} was built through the catalog screen, so
     *     every description a result can show was screened already
     */
    public ToolSearchState(
            Map<String, ToolCallback> exposed,
            Map<String, ToolCallback> deferred,
            ToolSearchService service,
            ToolActivationRegistry registry,
            String registryKey,
            int maxCarryOver,
            boolean catalogScreened) {
        this.exposed = Collections.unmodifiableMap(new LinkedHashMap<>(exposed));
        this.deferred = Collections.unmodifiableMap(new LinkedHashMap<>(deferred));
        this.service = service;
        this.registry = registry;
        this.registryKey = registryKey;
        this.maxCarryOver = maxCarryOver;
        this.catalogScreened = catalogScreened;
    }

    /** Number of tools hidden behind search this turn. */
    public int deferredCount() {
        return deferred.size();
    }

    /**
     * The search index over the deferred tools, built on first use (turns that never search never
     * pay for it).
     */
    ToolSearchService.Index index() {
        ToolSearchService.Index built = index;
        if (built == null) {
            synchronized (this) {
                built = index;
                if (built == null) {
                    built = service.indexCallbacks(deferred.values());
                    index = built;
                }
            }
        }
        return built;
    }

    ToolSearchService service() {
        return service;
    }

    /**
     * Whether the deferred catalog's text was screened at build time - the condition under which a
     * search result may skip the per-call untrusted-content screen.
     */
    boolean catalogScreened() {
        return catalogScreened;
    }

    /** The deferred callback for an exact name, if there is one. */
    Optional<ToolCallback> deferredTool(String name) {
        return Optional.ofNullable(deferred.get(name));
    }

    /**
     * Marks every named deferred tool as activated for the rest of this run and remembers it for
     * later turns. Names that are not deferred tools are ignored.
     *
     * @return the names that were actually activated (deferred and known), in the order given
     */
    List<String> activate(List<String> names) {
        List<String> accepted = new ArrayList<>();
        for (String name : names) {
            if (deferred.containsKey(name)) {
                activated.add(name);
                accepted.add(name);
            }
        }
        if (registry != null && !accepted.isEmpty() && maxCarryOver > 0) {
            registry.remember(registryKey, accepted, maxCarryOver);
        }
        return accepted;
    }

    /** Snapshot of the tools activated so far this run (tests and diagnostics). */
    public Set<String> activatedNames() {
        synchronized (activated) {
            return new LinkedHashSet<>(activated);
        }
    }

    /**
     * What {@code call_tool} may run for {@code name}: a tool offered directly this turn, or a
     * deferred tool the model has activated. Empty for anything else.
     */
    Optional<ToolCallback> callable(String name) {
        if (name == null) {
            return Optional.empty();
        }
        ToolCallback direct = exposed.get(name);
        if (direct != null) {
            return Optional.of(direct);
        }
        return activated.contains(name)
                ? Optional.ofNullable(deferred.get(name))
                : Optional.empty();
    }

    /** True for a name that exists as a deferred tool but has not been searched for yet. */
    boolean isDeferredButInactive(String name) {
        return name != null && deferred.containsKey(name) && !activated.contains(name);
    }
}
