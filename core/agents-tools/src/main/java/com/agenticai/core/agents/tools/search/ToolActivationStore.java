package com.agenticai.core.agents.tools.search;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Where {@link ToolActivationRegistry} keeps the deferred tools a session already searched for.
 * {@link MemoryToolActivationStore} is the zero-config default; {@link RedisToolActivationStore}
 * shares them between replicas so an activation made on one replica is visible on the next turn
 * wherever it lands.
 *
 * <p>Contract: {@code key} is the tenant|agent|session key built by {@link
 * ToolActivationRegistry#key}; {@code names} are tool names (blank ones are skipped), most recent
 * last. {@code maxNames} bounds names kept per session, {@code ttl} the sliding lifetime from the
 * last touch. Both are already capped and normalised by the registry. Everything here is
 * best-effort: an unreachable or empty store costs the model one more search, never a turn.
 */
public interface ToolActivationStore {

    /**
     * Records {@code names} as activated for {@code key}, most recent last, keeping only the
     * newest {@code maxNames} and letting the entry live {@code ttl} past the last touch.
     * {@code maxNames <= 0} records nothing.
     */
    void remember(String key, Collection<String> names, int maxNames, Duration ttl);

    /**
     * The newest {@code limit} activated names for {@code key}, oldest first; empty when none or
     * expired. Reading touches the entry, so a live session does not expire mid-conversation.
     */
    List<String> recall(String key, int limit, Duration ttl);

    /** Sessions currently held (tests and diagnostics only). */
    int sessionCount();
}
