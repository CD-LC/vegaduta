package com.agenticai.core.agents.tools.browseruse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The live {@link BrowserTakeoverControl}s of this JVM, by browser session key and by takeover id,
 * plus the owner lookups every person-facing call starts with. Split out of {@link
 * BrowserTakeoverService}; the rules are unchanged: a control that is missing, someone else's or
 * terminal answers 404 (existence is not revealed).
 */
final class BrowserTakeoverRegistry {

    private final Map<String, BrowserTakeoverControl> byKey = new ConcurrentHashMap<>();
    private final Map<UUID, BrowserTakeoverControl> byId = new ConcurrentHashMap<>();

    /** Registers a new control for its session, replacing (and unindexing) any previous one. */
    void register(BrowserTakeoverControl control) {
        BrowserTakeoverControl previous = byKey.put(control.sessionKey(), control);
        byId.put(control.takeoverId(), control);
        if (previous != null) {
            byId.remove(previous.takeoverId());
        }
    }

    /** Removes and returns the control of a session, or null. */
    BrowserTakeoverControl removeSession(String sessionKey) {
        BrowserTakeoverControl control = byKey.remove(sessionKey);
        if (control != null) {
            byId.remove(control.takeoverId());
        }
        return control;
    }

    /** Drops a control from both indexes (a terminal takeover). */
    void remove(BrowserTakeoverControl control) {
        byKey.remove(control.sessionKey());
        byId.remove(control.takeoverId());
    }

    BrowserTakeoverControl bySessionKey(String sessionKey) {
        return byKey.get(sessionKey);
    }

    BrowserTakeoverControl byTakeoverId(UUID takeoverId) {
        return takeoverId == null ? null : byId.get(takeoverId);
    }

    boolean isEmpty() {
        return byKey.isEmpty();
    }

    List<BrowserTakeoverControl> snapshot() {
        return new ArrayList<>(byKey.values());
    }

    /** The caller's live takeover by id; 404 when missing, someone else's, or over. */
    BrowserTakeoverControl ownedById(String tenantId, String userId, UUID takeoverId) {
        BrowserTakeoverControl control = byTakeoverId(takeoverId);
        if (control == null
                || !control.isOwnedBy(tenantId, userId)
                || control.state().isTerminal()) {
            throw notFound();
        }
        return control;
    }

    /** The caller's control for an agent + chat session, or null. */
    BrowserTakeoverControl ownedByKey(
            String tenantId, String userId, UUID agentId, String sessionId) {
        if (tenantId == null || agentId == null) {
            return null;
        }
        BrowserTakeoverControl control =
                byKey.get(BrowserUseSessionManager.sessionKey(tenantId, agentId, sessionId));
        return control != null && control.isOwnedBy(tenantId, userId) ? control : null;
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such browser takeover.");
    }
}
