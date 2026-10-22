package com.agenticai.core.agents.job.trigger;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The optional filter on a trigger: a flat JSON object of {@code field -> expected value}, ALL of
 * which must match the event payload for the trigger to fire. {@code {"channel":"whatsapp"}},
 * {@code {"success":"true"}}.
 *
 * <p><b>Deliberately not an expression language.</b> A predicate DSL (SpEL, JS, JSONPath with
 * comparisons) would be evaluated on a webhook request thread, on a per-tenant string, for every
 * published event - that is an execution surface and a denial-of-service surface at the same time,
 * in exchange for expressive power nobody has asked for yet. Flat equality is a fixed number of
 * string comparisons with no parser to abuse, and the two realistic uses ("only this channel",
 * "only successful generations") are both expressible.
 *
 * <p>Both directions are conservative:
 *
 * <ul>
 *   <li>An entry whose field is ABSENT from the payload does NOT match. A filter is a restriction,
 *       so an unanswerable question resolves to "do not fire", never to "fire anyway".
 *   <li>Keys are validated at {@code PUT} time against the fields that event type actually carries,
 *       so a filter that could never match is a 400 the operator sees immediately rather than a
 *       trigger that silently never fires - which is indistinguishable from a broken feature.
 * </ul>
 */
public final class AgentEventTriggerFilter {

    private AgentEventTriggerFilter() {}

    /**
     * Write-time validation. Rejects anything that could not work at dispatch time: unparseable
     * JSON, a non-object, a nested object/array value (there is nothing scalar to compare it
     * against), or a key this event type never carries.
     */
    public static void requireUsable(
            String filterJson, String eventType, ObjectMapper objectMapper) {
        if (filterJson == null || filterJson.isBlank()) {
            return;
        }
        Map<String, Object> parsed;
        try {
            parsed =
                    objectMapper.readValue(filterJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception notAnObject) {
            throw new IllegalArgumentException(
                    "Invalid trigger: the filter must be a flat JSON object of field -> expected value, e.g. "
                            + "{\"channel\":\"whatsapp\"}. "
                            + notAnObject.getMessage());
        }
        Set<String> available = new TreeSet<>(AgentTriggerEventTypes.payloadFieldsOf(eventType));
        for (Map.Entry<String, Object> entry : parsed.entrySet()) {
            if (!available.contains(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Invalid trigger: the filter refers to '"
                                + entry.getKey()
                                + "', which '"
                                + eventType
                                + "' does not carry, so it could never match. Available fields: "
                                + available
                                + ".");
            }
            if (entry.getValue() instanceof Map || entry.getValue() instanceof Iterable) {
                throw new IllegalArgumentException(
                        "Invalid trigger: the filter value for '"
                                + entry.getKey()
                                + "' must be a plain string, number or boolean - nested objects and arrays are not supported.");
            }
        }
    }

    /**
     * Parses a stored filter for the dispatch path.
     *
     * <p>Returns an empty map for null/blank (no filter, everything matches). Throws for a row that
     * no longer parses, which can only happen if it was hand-edited past {@link #requireUsable} -
     * the caller treats that as "do not fire" and logs it, because a filter that cannot be
     * evaluated must not degrade into "no filter".
     */
    public static Map<String, String> parse(String filterJson, ObjectMapper objectMapper) {
        if (filterJson == null || filterJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed =
                    objectMapper.readValue(filterJson, new TypeReference<Map<String, Object>>() {});
            Map<String, String> expected = new LinkedHashMap<>();
            parsed.forEach(
                    (key, value) ->
                            expected.put(key, value == null ? null : String.valueOf(value)));
            return expected;
        } catch (Exception unparseable) {
            throw new IllegalArgumentException(
                    "Stored trigger filter is not a JSON object: " + unparseable.getMessage());
        }
    }

    /**
     * @return true when every entry matches. Comparison is on the payload value's string form,
     *     case-insensitively: payload values arrive as {@code Boolean}, {@code Integer} and {@code
     *     String} from different publishers for what an operator thinks of as one value, and making
     *     {@code "TRUE"} not match {@code true} would be a filter that looks correct and never
     *     fires.
     */
    public static boolean matches(Map<String, String> expected, Map<String, Object> payload) {
        if (expected.isEmpty()) {
            return true;
        }
        Map<String, Object> actual = payload == null ? Map.of() : payload;
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            Object value = actual.get(entry.getKey());
            if (value == null) {
                // Absent field: a restriction whose question cannot be answered
                // resolves to "do not fire", never to "fire anyway".
                return false;
            }
            if (!String.valueOf(value).equalsIgnoreCase(entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
