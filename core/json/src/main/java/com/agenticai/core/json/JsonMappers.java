package com.agenticai.core.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place core code builds a non-Spring-managed Jackson {@link ObjectMapper}.
 *
 * <p>ADR-0012 phase P1b: main code never calls {@code new ObjectMapper()} directly (enforced by
 * {@code JsonMappersUsageGuardTest}). Every distinct configuration the code base used is a named
 * factory here, each byte-behaviour-identical to the inline construction it replaced. The Jackson 2
 * to 3 migration then rewrites this class instead of ~180 call sites. Spring-managed mappers (the
 * injected {@code ObjectMapper} bean) are unaffected and should keep being injected.
 *
 * <p>Every call returns a fresh mapper; mappers are thread-safe once configured, so callers keep
 * them in {@code static final} fields exactly as before.
 */
public final class JsonMappers {

    private JsonMappers() {}

    /**
     * Jackson 3 builder carrying the Jackson 2 defaults (ADR-0012 P2): dates as timestamps,
     * unsorted properties, and so on, so the wire format is unchanged by the major-version jump.
     * java.time / Jdk8 support is built into Jackson 3, so no module registration is needed.
     */
    private static JsonMapper.Builder base() {
        return JsonMapper.builder().configureForJackson2();
    }

    /**
     * The concrete {@link JsonMapper} with Jackson 2 wire defaults, for the Hibernate JSON format
     * mapper (JSONB output must not change with the Jackson major version).
     */
    public static JsonMapper jsonMapper() {
        return base().build();
    }

    /** Jackson-2-default mapper: no customisation beyond {@code configureForJackson2()}. */
    public static ObjectMapper plain() {
        return base().build();
    }

    /**
     * Formerly {@code new ObjectMapper().registerModule(new JavaTimeModule())}; java.time is now
     * built in.
     */
    public static ObjectMapper withJavaTime() {
        return base().build();
    }

    /** Ignores unknown fields on read. */
    public static ObjectMapper lenient() {
        return base().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build();
    }

    /** Null properties are omitted on write. */
    public static ObjectMapper nonNull() {
        return base().changeDefaultPropertyInclusion(
                        v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
    }

    /** Deterministic map key order (canonical form). */
    public static ObjectMapper sortedMapKeys() {
        return base().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    }

    /**
     * A mapper over a caller-built {@link JsonFactory}, for parsers that need their own {@code
     * StreamReadConstraints} (size, depth and number limits on untrusted input).
     */
    public static ObjectMapper withFactory(JsonFactory factory) {
        return JsonMapper.builder(factory).configureForJackson2().build();
    }

    /** Rejects input with content after the first value. */
    public static ObjectMapper strictTrailingTokens() {
        return base().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    }
}
