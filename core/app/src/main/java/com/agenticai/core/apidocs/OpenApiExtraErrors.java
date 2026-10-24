package com.agenticai.core.apidocs;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Error statuses a handler raises through an exception advice, which reflection on the handler's signature cannot see.
 * Keyed like {@link OpenApiDescriptions} ({@code "<VERB> <path template>"}); the value maps a status to the name of a
 * shared error response in {@code components.responses} (see {@link OpenApiFixedComponents#errorRef}). The prose that
 * says what the status means for the route goes in {@link OpenApiDescriptions}.
 *
 * <p>{@code OpenApiSettingsRoutesTest} fails when a key no longer matches a scanned operation.
 */
final class OpenApiExtraErrors {

    private static final Map<String, Map<String, String>> BY_VERB_AND_PATH;

    static {
        Map<String, Map<String, String>> m = new LinkedHashMap<>();
        // core/settings: SettingsExceptionHandler answers 409 SETTING_LOCKED (pinned by the operator) and
        // 409 SETTING_CONFLICT (two writers raced on the key).
        for (String route :
                new String[] {
                    "PUT /api/settings/tenant/{key}",
                    "DELETE /api/settings/tenant/{key}",
                    "PUT /api/agents/{agentId}/settings/{key}",
                    "DELETE /api/agents/{agentId}/settings/{key}"
                }) {
            m.put(route, Map.of("409", "Conflict"));
        }
        BY_VERB_AND_PATH = Collections.unmodifiableMap(m);
    }

    private OpenApiExtraErrors() {}

    /** Adds the extra statuses of {@code verb} (any case) and {@code path} to {@code responses}, keeping existing ones. */
    static void apply(String verb, String path, Map<String, Object> responses) {
        Map<String, String> extra = BY_VERB_AND_PATH.get(OpenApiDescriptions.key(verb, path));
        if (extra == null) {
            return;
        }
        extra.forEach((status, component) -> responses.putIfAbsent(status, OpenApiFixedComponents.errorRef(component)));
    }

    /** Every {@code "<VERB> <path>"} key, for the drift test. */
    static Set<String> keys() {
        return BY_VERB_AND_PATH.keySet();
    }

    /** The extra statuses of one route (empty if none), for tests. */
    static Map<String, String> statusesFor(String verb, String path) {
        return BY_VERB_AND_PATH.getOrDefault(OpenApiDescriptions.key(verb, path), Map.of());
    }
}
