package com.agenticai.core.apidocs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Hand-written operation descriptions for routes whose behaviour cannot be derived by reflection.
 *
 * <p>The generator runs from the compiled classpath only (no application context, no source tree),
 * so a controller method's javadoc is not available to it. This map is the one place to put the
 * prose that matters to an API client: who may call the route, which feature flag gates it, and
 * what a refusal looks like. Keys are {@code "<VERB> <path template>"} exactly as the generator
 * emits them (upper-case verb, regex constraints stripped), for example {@code "GET
 * /api/agents/{agentId}/sessions/{sessionId}/transcript"}. Keying by path rather than operationId
 * keeps the entry stable when a controller method is renamed or a duplicate operationId gets a
 * numeric suffix.
 *
 * <p>{@code OpenApiSpecGeneratorTest#curatedDescriptionsMatchRealOperations} fails when a key no
 * longer matches a scanned operation, so a moved route cannot silently lose its description.
 */
final class OpenApiDescriptions {

    /** The ownership sentence shared by every per-session route. */
    static final String SESSION_OWNER =
            "Readable and changeable only by the caller who started the session: session memory is"
                    + " keyed by tenant + agent + user + client session id, so the same session id"
                    + " presented by any other caller resolves to nothing and answers 404.";

    private static final String EXPORT_FLAG =
            " Additionally requires the CHAT_EXPORT_ENABLED feature flag"
                    + " (app.chat-export.enabled); when it is off this route answers 404.";

    private static final String BRANCHING_FLAG =
            " Requires the APP_CHAT_BRANCHING_ENABLED feature flag (app.chat.branching.enabled);"
                    + " when it is off this route answers 404.";

    private static final Map<String, String> BY_VERB_AND_PATH;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(
                "GET /api/agents/{agentId}/sessions/{sessionId}/transcript",
                "Reload one chat session as a structured transcript. " + SESSION_OWNER);
        m.put(
                "GET /api/agents/{agentId}/sessions/{sessionId}/export",
                "Download one chat session as a file (format=md|json|txt). "
                        + SESSION_OWNER
                        + EXPORT_FLAG);
        m.put(
                "POST /api/agents/{agentId}/chat/sessions/{sessionId}/rewind",
                "Truncate the session transcript to its first keepTurns entries so the"
                        + " conversation continues from there; no turn is re-run. "
                        + SESSION_OWNER);
        m.put(
                "POST /api/agents/{agentId}/chat/sessions/{sessionId}/cancel",
                "Ask the in-flight chat turn of this session to stop; the turn ends on its next"
                        + " poll. "
                        + SESSION_OWNER);
        m.put(
                "GET /api/agents/{agentId}/sessions/{sessionId}/messages",
                "The branch of the conversation currently shown and replayed to the model"
                        + " (branch=active is the only supported view). "
                        + SESSION_OWNER
                        + BRANCHING_FLAG);
        m.put(
                "GET /api/agents/{agentId}/sessions/{sessionId}/messages/{messageId}/siblings",
                "The versions of one message, in order, with the position of this one. "
                        + SESSION_OWNER
                        + BRANCHING_FLAG);
        m.put(
                "PUT /api/agents/{agentId}/sessions/{sessionId}/messages/active-leaf",
                "Switch the conversation to another branch: the server lands on the newest leaf"
                        + " beneath the given message. "
                        + SESSION_OWNER
                        + BRANCHING_FLAG);
        m.put(
                "POST /api/agents/{agentId}/sessions/{sessionId}/messages/{messageId}/edit",
                "Replace the text of an earlier user message on a new branch and stream the new"
                        + " reply; the original branch is kept. "
                        + SESSION_OWNER
                        + BRANCHING_FLAG);
        m.put(
                "POST /api/agents/{agentId}/sessions/{sessionId}/messages/{messageId}/regenerate",
                "Produce another answer to the same user message and stream it; the previous"
                        + " answer is kept as a sibling. "
                        + SESSION_OWNER
                        + BRANCHING_FLAG);
        m.put(
                "PUT /api/settings/tenant/{key}",
                "Set a runtime setting for the whole workspace (tenant admin). Body {value, reason}: a"
                        + " high or security key needs a typed reason, and a security key can only be made"
                        + " stricter than the value it inherits. Answers 409 SETTING_LOCKED when the operator"
                        + " pinned the key (app.settings.locked) and 409 SETTING_CONFLICT when another writer"
                        + " changed the same key at the same moment (reload and retry).");
        m.put(
                "DELETE /api/settings/tenant/{key}",
                "Remove the workspace override so the platform value applies again (tenant admin). A high or"
                        + " security key needs a typed reason (?reason=), like a PUT. Answers 409 SETTING_LOCKED"
                        + " when the key is pinned and 409 SETTING_CONFLICT when another writer changed it at the"
                        + " same moment.");
        m.put(
                "PUT /api/agents/{agentId}/settings/{key}",
                "Set a runtime setting for one agent (EDITOR on the agent; a security key also needs a tenant"
                        + " admin). Same body and rules as the workspace PUT. Answers 409 SETTING_LOCKED when"
                        + " the key is pinned and 409 SETTING_CONFLICT when another writer changed it at the same"
                        + " moment.");
        m.put(
                "DELETE /api/agents/{agentId}/settings/{key}",
                "Remove the agent's override so the workspace value applies again. A high or security key needs"
                        + " a typed reason (?reason=). Answers 409 SETTING_LOCKED when the key is pinned and 409"
                        + " SETTING_CONFLICT when another writer changed it at the same moment.");
        m.put(
                "GET /api/settings/{key}/history",
                "The change history of one setting, newest first. scope=tenant needs a tenant admin; scope=agent"
                        + " with agentId needs EDITOR on the agent. For every key at once use GET"
                        + " /api/settings/history.");
        m.put(
                "GET /api/settings/history",
                "The change history of every setting in one call, newest first. scope=all (default) is the"
                        + " workspace's own changes plus every agent's (tenant admin), scope=tenant the workspace's"
                        + " only, scope=agent every agent's (tenant admin), scope=agent with agentId one agent's"
                        + " (EDITOR on it). since (ISO-8601 instant)"
                        + " keeps rows at or after it; page with limit and offset, the body carries total and"
                        + " hasMore.");
        m.put(
                "GET /api/agents/{agentId}/settings/history",
                "Every change to this agent's settings, newest first (EDITOR on the agent; an agent of"
                        + " another workspace is a 404). since keeps rows at or after that instant; page with"
                        + " limit and offset.");
        BY_VERB_AND_PATH = Collections.unmodifiableMap(m);
    }

    private OpenApiDescriptions() {}

    /** The curated description for {@code verb} (any case) and OpenAPI {@code path}, or null. */
    static String describe(String verb, String path) {
        String k = key(verb, path);
        String own = BY_VERB_AND_PATH.get(k);
        return own != null ? own : OpenApiCoreDescriptions.BY_VERB_AND_PATH.get(k);
    }

    /** Every {@code "<VERB> <path>"} key, for the drift test. */
    static Set<String> keys() {
        Set<String> all = new java.util.LinkedHashSet<>(BY_VERB_AND_PATH.keySet());
        all.addAll(OpenApiCoreDescriptions.BY_VERB_AND_PATH.keySet());
        return all;
    }

    static String key(String verb, String path) {
        return verb.toUpperCase(java.util.Locale.ROOT) + " " + path;
    }
}
