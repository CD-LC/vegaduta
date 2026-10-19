package com.agenticai.core.agents.hooks;

import java.util.List;
import java.util.Map;

/**
 * One validated hook. Instances are produced only by {@link HookSchema}, which has
 * already checked every field, so the accessors here can trust what they read.
 *
 * @param id unique within its scope; {@code [a-z0-9][a-z0-9_-]{0,39}}
 * @param enabled a disabled hook is kept in the list but never evaluated
 * @param point where it fires
 * @param match which tool calls it applies to (tool points only)
 * @param action what it does
 * @param params action parameters, already normalised to String / Integer / Boolean / List&lt;String&gt;
 * @param failMode verdict-less behaviour of a {@code webhook} hook; resolved to the default
 *     for the point when the author gave none
 * @param timeoutMs this hook's own webhook timeout, clamped to the platform hard cap by the runtime
 */
public record HookDefinition(
    String id,
    boolean enabled,
    HookPoint point,
    HookMatch match,
    HookActionType action,
    Map<String, Object> params,
    FailMode failMode,
    int timeoutMs
) {

    public HookDefinition {
        match = match == null ? HookMatch.ANY : match;
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public String str(String key) {
        Object v = params.get(key);
        return v instanceof String s ? s : null;
    }

    public Integer intParam(String key) {
        Object v = params.get(key);
        return v instanceof Number n ? n.intValue() : null;
    }

    public boolean bool(String key) {
        return Boolean.TRUE.equals(params.get(key));
    }

    @SuppressWarnings("unchecked")
    public List<String> strList(String key) {
        Object v = params.get(key);
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }
}
