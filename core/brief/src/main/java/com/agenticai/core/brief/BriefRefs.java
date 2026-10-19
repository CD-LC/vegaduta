package com.agenticai.core.brief;

import com.agenticai.core.brief.model.Brief;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Ref vocabulary shared by the validator, readiness, planner and (later) the apply engine:
 * the ref grammar from {@code brief-schema.v1.json} ({@code ^[a-z][0-9]{1,2}$}), the one-letter
 * kind prefixes, and the deterministic placeholder UUID a ref maps to when a workflow graph is
 * run through {@code OrchestratorService#validate} before anything exists.
 */
public final class BriefRefs {

    public static final Pattern REF = Pattern.compile("^[a-z][0-9]{1,2}$");
    public static final Pattern NODE_PATH = Pattern.compile("^w[0-9]{1,2}\\.[A-Za-z0-9_-]+$");
    public static final Pattern SECRET_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");
    public static final Pattern LOCALE = Pattern.compile("^[a-z]{2,3}(-[A-Za-z]{2,4})?$");
    private static final Pattern UUID_SHAPE = Pattern.compile(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    public static final char AGENT = 'a';
    public static final char WORKFLOW = 'w';
    public static final char TOOL = 't';
    public static final char MCP = 'm';
    public static final char KNOWLEDGE = 'k';
    public static final char SECRET = 's';
    public static final char PROCEDURE = 'p';
    public static final char CHANNEL = 'c';

    private BriefRefs() {
    }

    public static boolean isRef(String value) {
        return value != null && REF.matcher(value).matches();
    }

    public static boolean isRef(String value, char kind) {
        return isRef(value) && value.charAt(0) == kind;
    }

    public static boolean looksLikeUuid(String value) {
        return value != null && UUID_SHAPE.matcher(value).matches();
    }

    /**
     * Stable fake id for a ref, used ONLY to let the real workflow validator run over a graph
     * whose agents do not exist yet. Namespaced so it can never collide with a real random UUID.
     */
    public static UUID placeholder(String ref) {
        return UUID.nameUUIDFromBytes(("brief-placeholder:" + ref).getBytes(StandardCharsets.UTF_8));
    }

    /** Every artifact ref in the Brief mapped to its kind letter, in declaration order. */
    public static Map<String, Character> artifactRefs(Brief brief) {
        Map<String, Character> refs = new LinkedHashMap<>();
        brief.agents().forEach(a -> refs.putIfAbsent(String.valueOf(a.ref()), AGENT));
        brief.workflows().forEach(w -> refs.putIfAbsent(String.valueOf(w.ref()), WORKFLOW));
        brief.tools().forEach(t -> refs.putIfAbsent(String.valueOf(t.ref()), TOOL));
        brief.mcpServers().forEach(m -> refs.putIfAbsent(String.valueOf(m.ref()), MCP));
        brief.knowledge().forEach(k -> refs.putIfAbsent(String.valueOf(k.ref()), KNOWLEDGE));
        brief.secrets().forEach(s -> refs.putIfAbsent(String.valueOf(s.ref()), SECRET));
        brief.procedures().forEach(p -> refs.putIfAbsent(String.valueOf(p.ref()), PROCEDURE));
        brief.channels().forEach(c -> refs.putIfAbsent(String.valueOf(c.ref()), CHANNEL));
        return refs;
    }
}
