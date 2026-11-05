package com.agenticai.core.agents.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which conversations have {@code memory.write.enabled} switched off, so the rolling session
 * summary is not folded for them. The session store knows a scoped session id, not the agent, so
 * the chat turn (which knows both) records the switch here at its start and the store asks before
 * it claims a fold batch. In-process and bounded: the fold and the turn that marks it run in the
 * same JVM, and an unmarked (evicted or restarted) session simply folds as it does without the
 * switch. Keys are the scoped ids, never text.
 */
public final class SessionWriteSwitch {

    private static final int MAX_SESSIONS = 5_000;

    private static final Map<String, Boolean> OFF =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                            return size() > MAX_SESSIONS;
                        }
                    });

    private SessionWriteSwitch() {}

    /**
     * Records the switch for a session at the start of a turn: {@code writesOff} true = no summary
     * fold for it.
     */
    public static void mark(String scopedSessionId, boolean writesOff) {
        if (scopedSessionId == null) {
            return;
        }
        if (writesOff) {
            OFF.put(scopedSessionId, Boolean.TRUE);
        } else {
            OFF.remove(scopedSessionId);
        }
    }

    /** True when the session's latest turn found memory writes switched off. */
    public static boolean writesOff(String scopedSessionId) {
        return scopedSessionId != null && OFF.containsKey(scopedSessionId);
    }
}
