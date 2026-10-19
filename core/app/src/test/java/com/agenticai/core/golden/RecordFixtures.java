package com.agenticai.core.golden;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;

/**
 * Builds deterministic instances of the API's response records by reflection, so the golden tests
 * cover EVERY component of a DTO (a field added later shows up in the golden diff instead of being
 * silently untested) without hand-maintaining 50-argument constructor calls.
 *
 * <ul>
 *   <li>{@link #populated}: every component non-null, collections with two elements, enums = the
 *       first constant.</li>
 *   <li>{@link #sparse}: every reference component null, collections empty, primitives zero/false -
 *       the "nulls and empty collections" shape.</li>
 * </ul>
 * Values are constants, never derived from the clock or randomness.
 */
public final class RecordFixtures {

    public static final UUID UUID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID UUID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    /** Nanosecond precision on purpose: pins how instants with sub-millisecond digits are written. */
    public static final Instant INSTANT = Instant.parse("2026-09-30T10:15:30.123456789Z");
    public static final Instant INSTANT_WHOLE_SECOND = Instant.parse("2026-09-30T10:15:30Z");

    private static final int MAX_DEPTH = 4;

    private RecordFixtures() {
    }

    public static <T> T populated(Class<T> type) {
        return type.cast(record(type, true, 1));
    }

    public static <T> T sparse(Class<T> type) {
        return type.cast(record(type, false, 1));
    }

    // ------------------------------------------------------------- building

    private static Object build(Class<?> raw, Type generic, boolean populated, String name, int depth) {
        if (raw.isPrimitive()) {
            return primitive(raw, populated);
        }
        if (!populated && !Collection.class.isAssignableFrom(raw) && !Map.class.isAssignableFrom(raw)) {
            return null;
        }
        if (raw == String.class) {
            return name + "-value";
        }
        if (raw == UUID.class) {
            return UUID_1;
        }
        if (raw == Instant.class) {
            return INSTANT;
        }
        if (raw == LocalDate.class) {
            return LocalDate.of(2026, 9, 30);
        }
        if (raw == LocalDateTime.class) {
            return LocalDateTime.of(2026, 9, 30, 10, 15, 30, 123_000_000);
        }
        if (raw == OffsetDateTime.class) {
            return OffsetDateTime.of(2026, 9, 30, 10, 15, 30, 0, ZoneOffset.ofHoursMinutes(5, 30));
        }
        if (raw == Duration.class) {
            return Duration.ofSeconds(90, 500_000_000);
        }
        if (raw == BigDecimal.class) {
            return new BigDecimal("1234.5000");
        }
        if (raw == Double.class) {
            return 0.7d;
        }
        if (raw == Float.class) {
            return 0.5f;
        }
        if (raw == Long.class) {
            return 42L;
        }
        if (raw == Integer.class) {
            return 7;
        }
        if (raw == Boolean.class) {
            return Boolean.TRUE;
        }
        if (raw == Object.class) {
            return "object-value";
        }
        if (raw.isEnum()) {
            return raw.getEnumConstants()[0];
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return collection(raw, generic, populated, name, depth);
        }
        if (Map.class.isAssignableFrom(raw)) {
            Map<String, Object> map = new LinkedHashMap<>();
            if (populated) {
                map.put("first", "a");
                map.put("second", 2);
            }
            return map;
        }
        if (raw.isRecord() && depth < MAX_DEPTH) {
            return record(raw, populated, depth + 1);
        }
        return null;
    }

    private static Object collection(Class<?> raw, Type generic, boolean populated, String name, int depth) {
        Collection<Object> out = Set.class.isAssignableFrom(raw) ? new LinkedHashSet<>() : new ArrayList<>();
        if (!populated) {
            return out;
        }
        Type elementType = generic instanceof ParameterizedType p ? p.getActualTypeArguments()[0] : Object.class;
        Class<?> elementRaw = elementType instanceof Class<?> c ? c
            : elementType instanceof ParameterizedType pt ? (Class<?>) pt.getRawType() : Object.class;
        out.add(build(elementRaw, elementType, true, name + "-1", depth + 1));
        if (elementRaw == String.class) {
            out.add(name + "-2");
        } else if (elementRaw == UUID.class) {
            out.add(UUID_2);
        }
        out.removeIf(java.util.Objects::isNull);
        return out;
    }

    private static Object record(Class<?> type, boolean populated, int depth) {
        RecordComponent[] components = type.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            args[i] = build(components[i].getType(), components[i].getGenericType(), populated, components[i].getName(), depth);
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build fixture for " + type.getName(), e);
        }
    }

    private static Object primitive(Class<?> raw, boolean populated) {
        if (raw == boolean.class) {
            return populated;
        }
        if (raw == int.class) {
            return populated ? 7 : 0;
        }
        if (raw == long.class) {
            return populated ? 42L : 0L;
        }
        if (raw == double.class) {
            return populated ? 0.7d : 0.0d;
        }
        if (raw == float.class) {
            return populated ? 0.5f : 0.0f;
        }
        if (raw == short.class) {
            return (short) (populated ? 3 : 0);
        }
        if (raw == byte.class) {
            return (byte) (populated ? 1 : 0);
        }
        if (raw == char.class) {
            return populated ? 'x' : '\0';
        }
        throw new IllegalArgumentException(raw.getName());
    }

    /** A List of the populated form of {@code type}, for "list of X" endpoints. */
    public static <T> List<T> listOf(Class<T> type) {
        return List.of(populated(type), sparse(type));
    }
}
