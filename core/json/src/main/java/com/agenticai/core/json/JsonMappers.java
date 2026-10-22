package com.agenticai.core.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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

    /** Equivalent to {@code new ObjectMapper()}: no modules registered, Jackson defaults. */
    public static ObjectMapper plain() {
        return new ObjectMapper();
    }

    /** {@code new ObjectMapper().registerModule(new JavaTimeModule())}. */
    public static ObjectMapper withJavaTime() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /**
     * {@code new ObjectMapper().configure(FAIL_ON_UNKNOWN_PROPERTIES, false)}: ignores unknown
     * fields.
     */
    public static ObjectMapper lenient() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * {@code new ObjectMapper().setDefaultPropertyInclusion(NON_NULL)}: null properties are omitted
     * on write.
     */
    public static ObjectMapper nonNull() {
        return new ObjectMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL);
    }

    /**
     * {@code new ObjectMapper().enable(ORDER_MAP_ENTRIES_BY_KEYS)}: deterministic map key order
     * (canonical form).
     */
    public static ObjectMapper sortedMapKeys() {
        return new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    /**
     * {@code new ObjectMapper(factory)}: a mapper over a caller-built {@link JsonFactory}, for
     * parsers that need their own {@code StreamReadConstraints} (size, depth and number limits on
     * untrusted input).
     */
    public static ObjectMapper withFactory(JsonFactory factory) {
        return new ObjectMapper(factory);
    }

    /**
     * {@code new ObjectMapper().enable(FAIL_ON_TRAILING_TOKENS)}: rejects input with content after
     * the first value.
     */
    public static ObjectMapper strictTrailingTokens() {
        return new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
}
