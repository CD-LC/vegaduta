package com.agenticai.core.apidocs;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import java.beans.PropertyDescriptor;
import java.io.File;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Currency;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.BeanUtils;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.io.Resource;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Reflection-based Java type to JSON Schema (OpenAPI 3.1 dialect) mapper with a components/schemas
 * registry. Records, POJOs and enums become named components referenced by {@code $ref}; the
 * registry is filled BEFORE a type's properties are walked, so a self-referencing or mutually
 * recursive type terminates with a {@code $ref} instead of recursing forever (the cycle guard).
 *
 * <p>Field naming and exclusion honour Jackson's {@code @JsonProperty} / {@code @JsonIgnore}.
 * Constraints are read from Bean Validation annotations by NAME (no compile dependency on the
 * validation API): {@code @NotNull/@NotBlank/@NotEmpty} make a property required, {@code @Size},
 * {@code @Min/@Max}, {@code @DecimalMin/@DecimalMax}, {@code @Positive...}, {@code @Pattern} and
 * {@code @Email} add the matching keywords. Primitive-typed properties are listed as required (they
 * are always present on a response) and carry their Java default as {@code default}, so SDK
 * generators can treat them as optional on input (the server fills in the default when absent).
 *
 * <p>Pure: no Spring context, no database, no static state.
 */
final class OpenApiSchemaBuilder {

    /** Packages whose types are never walked (JDK, Spring, Jackson, Hibernate, Reactor...). */
    private static final List<String> OPAQUE_PREFIXES =
            List.of(
                    "java.",
                    "javax.",
                    "jakarta.",
                    "org.springframework.",
                    "org.hibernate.",
                    "com.fasterxml.",
                    "reactor.",
                    "org.reactivestreams.",
                    "kotlin.",
                    "org.slf4j.",
                    "io.micrometer.",
                    "org.apache.");

    private final Map<String, Object> components = new TreeMap<>();
    private final Map<String, String> nameByKey = new HashMap<>();
    private final Map<String, String> keyByName = new HashMap<>();
    private final List<String> skipped;

    OpenApiSchemaBuilder(List<String> skipped) {
        this.skipped = skipped;
    }

    /** All registered component schemas, sorted by name. */
    Map<String, Object> components() {
        return components;
    }

    /** Registers a hand-written component schema (used for the shared Error body). */
    void registerFixed(String name, Map<String, Object> schema) {
        nameByKey.put("fixed:" + name, name);
        keyByName.put(name, "fixed:" + name);
        components.put(name, schema);
    }

    static Map<String, Object> ref(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("$ref", "#/components/schemas/" + name);
        return m;
    }

    static boolean isRef(Map<String, Object> schema) {
        return schema.containsKey("$ref");
    }

    // ------------------------------------------------------------------ type dispatch

    /** Schema for a Java type. Always returns a fresh, mutable map. */
    Map<String, Object> schemaFor(ResolvableType type) {
        Class<?> raw = type.resolve();
        if (raw == null || raw == Object.class) {
            return any();
        }
        Map<String, Object> simple = scalar(raw);
        if (simple != null) {
            return simple;
        }
        if (raw == void.class || raw == Void.class) {
            return any();
        }
        if (raw.isArray()) {
            if (raw == byte[].class) {
                return typed("string", "byte");
            }
            return arrayOf(schemaFor(type.getComponentType()), false);
        }
        if (raw == Optional.class) {
            return schemaFor(type.getGeneric(0));
        }
        if (raw == OptionalInt.class) {
            return typed("integer", "int32");
        }
        if (raw == OptionalLong.class) {
            return typed("integer", "int64");
        }
        if (raw == OptionalDouble.class) {
            return typed("number", "double");
        }
        if (isJsonNode(raw)) {
            return any();
        }
        if (MultipartFile.class.isAssignableFrom(raw)
                || Resource.class.isAssignableFrom(raw)
                || InputStream.class.isAssignableFrom(raw)
                || File.class.isAssignableFrom(raw)) {
            return typed("string", "binary");
        }
        if (raw.isEnum()) {
            return component(type, raw, () -> enumSchema(raw));
        }
        if (Map.class.isAssignableFrom(raw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "object");
            ResolvableType value = type.asMap().getGeneric(1);
            Class<?> valueRaw = value.resolve();
            m.put(
                    "additionalProperties",
                    valueRaw == null || valueRaw == Object.class ? Boolean.TRUE : schemaFor(value));
            return m;
        }
        if (Collection.class.isAssignableFrom(raw)) {
            boolean unique = java.util.Set.class.isAssignableFrom(raw);
            return arrayOf(schemaFor(type.asCollection().getGeneric(0)), unique);
        }
        String rawName = raw.getName();
        if (rawName.equals("org.springframework.data.domain.Page")
                || rawName.equals("org.springframework.data.domain.Slice")) {
            return component(type, raw, () -> pageSchema(type));
        }
        if (Throwable.class.isAssignableFrom(raw) || isOpaque(rawName)) {
            return typed("object", null);
        }
        if (raw.isRecord()) {
            return component(type, raw, () -> recordSchema(type, raw));
        }
        return component(type, raw, () -> pojoSchema(type, raw));
    }

    private static boolean isOpaque(String className) {
        for (String p : OPAQUE_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJsonNode(Class<?> raw) {
        for (Class<?> c = raw; c != null; c = c.getSuperclass()) {
            if (c.getName().equals("com.fasterxml.jackson.databind.JsonNode")) {
                return true;
            }
        }
        return false;
    }

    static Map<String, Object> any() {
        return new LinkedHashMap<>();
    }

    private static Map<String, Object> typed(String type, String format) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (format != null) {
            m.put("format", format);
        }
        return m;
    }

    private static Map<String, Object> arrayOf(Map<String, Object> items, boolean unique) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "array");
        m.put("items", items);
        if (unique) {
            m.put("uniqueItems", Boolean.TRUE);
        }
        return m;
    }

    /** Scalars and well-known value types; null when {@code c} is not one of them. */
    private static Map<String, Object> scalar(Class<?> c) {
        if (c == boolean.class || c == Boolean.class) {
            return typed("boolean", null);
        }
        if (c == byte.class
                || c == Byte.class
                || c == short.class
                || c == Short.class
                || c == int.class
                || c == Integer.class
                || c == AtomicInteger.class) {
            return typed("integer", "int32");
        }
        if (c == long.class || c == Long.class || c == AtomicLong.class) {
            return typed("integer", "int64");
        }
        if (c == BigInteger.class) {
            return typed("integer", null);
        }
        if (c == float.class || c == Float.class) {
            return typed("number", "float");
        }
        if (c == double.class || c == Double.class) {
            return typed("number", "double");
        }
        if (c == BigDecimal.class || c == Number.class) {
            return typed("number", null);
        }
        if (c == char.class
                || c == Character.class
                || c == String.class
                || CharSequence.class.isAssignableFrom(c)) {
            return typed("string", null);
        }
        if (c == UUID.class) {
            return typed("string", "uuid");
        }
        if (c == Instant.class
                || c == OffsetDateTime.class
                || c == ZonedDateTime.class
                || c == LocalDateTime.class
                || Date.class.isAssignableFrom(c)) {
            return typed("string", "date-time");
        }
        if (c == LocalDate.class) {
            return typed("string", "date");
        }
        if (c == LocalTime.class) {
            return typed("string", "time");
        }
        if (c == Duration.class || c == Period.class) {
            return typed("string", "duration");
        }
        if (c == URI.class || c == URL.class) {
            return typed("string", "uri");
        }
        if (c == Year.class
                || c == YearMonth.class
                || c == Locale.class
                || c == Currency.class
                || c == Pattern.class
                || c == Class.class
                || Path.class.isAssignableFrom(c)
                || ZoneId.class.isAssignableFrom(c)) {
            return typed("string", null);
        }
        return null;
    }

    // ------------------------------------------------------------------ components

    private Map<String, Object> component(
            ResolvableType type, Class<?> raw, Supplier<Map<String, Object>> build) {
        String key = type.toString();
        String existing = nameByKey.get(key);
        if (existing != null) {
            return ref(existing);
        }
        String name = assignName(key, raw, type);
        // Register a placeholder first: any recursive reference below resolves to this $ref.
        components.put(name, new LinkedHashMap<String, Object>());
        Map<String, Object> schema;
        try {
            schema = build.get();
        } catch (RuntimeException | LinkageError e) {
            skipped.add("schema " + key + ": " + e.getClass().getSimpleName());
            schema = typed("object", null);
        }
        components.put(name, schema);
        return ref(name);
    }

    private String assignName(String key, Class<?> raw, ResolvableType type) {
        String base = baseName(raw, type);
        String candidate = base;
        if (keyByName.containsKey(candidate)) {
            candidate = lastPackageSegment(raw) + "_" + base;
        }
        if (keyByName.containsKey(candidate)) {
            candidate = raw.getPackageName().replace('.', '_') + "_" + base;
        }
        String unique = candidate;
        int n = 2;
        while (keyByName.containsKey(unique)) {
            unique = candidate + "_" + n++;
        }
        nameByKey.put(key, unique);
        keyByName.put(unique, key);
        return unique;
    }

    private static String baseName(Class<?> raw, ResolvableType type) {
        String simple = raw.getSimpleName();
        for (Class<?> e = raw.getEnclosingClass(); e != null; e = e.getEnclosingClass()) {
            simple = e.getSimpleName() + "_" + simple;
        }
        StringBuilder sb = new StringBuilder(simple);
        for (ResolvableType g : type.getGenerics()) {
            Class<?> a = g.resolve();
            sb.append('_').append(a == null || a == Object.class ? "Any" : a.getSimpleName());
        }
        return sb.toString().replaceAll("[^A-Za-z0-9_.\\-]", "_");
    }

    private static String lastPackageSegment(Class<?> raw) {
        String pkg = raw.getPackageName();
        String seg = pkg.substring(pkg.lastIndexOf('.') + 1);
        if (seg.isEmpty()) {
            return "Default";
        }
        return Character.toUpperCase(seg.charAt(0)) + seg.substring(1);
    }

    // ------------------------------------------------------------------ enums

    private static Map<String, Object> enumSchema(Class<?> raw) {
        Method jsonValue = null;
        for (Method m : raw.getDeclaredMethods()) {
            if (m.isAnnotationPresent(JsonValue.class)
                    && m.getParameterCount() == 0
                    && !Modifier.isStatic(m.getModifiers())) {
                jsonValue = m;
                break;
            }
        }
        List<Object> values = new ArrayList<>();
        Object[] constants = raw.getEnumConstants();
        if (constants != null) {
            for (Object constant : constants) {
                String constantName = ((Enum<?>) constant).name();
                String value = constantName;
                try {
                    if (jsonValue != null) {
                        jsonValue.setAccessible(true);
                        Object r = jsonValue.invoke(constant);
                        if (r != null) {
                            value = String.valueOf(r);
                        }
                    } else {
                        Field f = raw.getField(constantName);
                        JsonProperty jp = f.getAnnotation(JsonProperty.class);
                        if (jp != null && !jp.value().isEmpty()) {
                            value = jp.value();
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // keep the constant name
                }
                values.add(value);
            }
        }
        Map<String, Object> m = typed("string", null);
        m.put("enum", values);
        return m;
    }

    // ------------------------------------------------------------------ records and POJOs

    private Map<String, Object> recordSchema(ResolvableType type, Class<?> raw) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();
        for (RecordComponent rc : raw.getRecordComponents()) {
            Field field = ReflectionUtils.findField(raw, rc.getName());
            Method accessor = rc.getAccessor();
            List<AnnotatedElement> sources = new ArrayList<>();
            if (field != null) {
                sources.add(field);
            }
            if (accessor != null) {
                sources.add(accessor);
            }
            ResolvableType propType =
                    field != null
                            ? ResolvableType.forType(field.getGenericType(), type)
                            : ResolvableType.forType(rc.getGenericType(), type);
            addProperty(
                    properties,
                    required,
                    rc.getName(),
                    sources,
                    propType,
                    rc.getType().isPrimitive());
        }
        return objectSchema(properties, required);
    }

    private Map<String, Object> pojoSchema(ResolvableType type, Class<?> raw) {
        Map<String, Object> properties = new TreeMap<>();
        List<Object> required = new ArrayList<>();
        List<PropertyDescriptor> sorted =
                new ArrayList<>(List.of(BeanUtils.getPropertyDescriptors(raw)));
        sorted.sort(Comparator.comparing(PropertyDescriptor::getName));
        List<String> seen = new ArrayList<>();
        for (PropertyDescriptor pd : sorted) {
            Method getter = pd.getReadMethod();
            if (getter == null
                    || "class".equals(pd.getName())
                    || Modifier.isStatic(getter.getModifiers())) {
                continue;
            }
            Field field = ReflectionUtils.findField(raw, pd.getName());
            List<AnnotatedElement> sources = new ArrayList<>();
            sources.add(getter);
            if (field != null) {
                sources.add(field);
            }
            ResolvableType propType = ResolvableType.forType(getter.getGenericReturnType(), type);
            seen.add(pd.getName());
            addProperty(
                    properties,
                    required,
                    pd.getName(),
                    sources,
                    propType,
                    getter.getReturnType().isPrimitive());
        }
        for (Field f : raw.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) || seen.contains(f.getName())) {
                continue;
            }
            ResolvableType propType = ResolvableType.forType(f.getGenericType(), type);
            List<AnnotatedElement> sources = new ArrayList<>();
            sources.add(f);
            addProperty(
                    properties,
                    required,
                    f.getName(),
                    sources,
                    propType,
                    f.getType().isPrimitive());
        }
        return objectSchema(properties, required);
    }

    private static Map<String, Object> objectSchema(
            Map<String, Object> properties, List<Object> required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        if (!properties.isEmpty()) {
            m.put("properties", properties);
        }
        if (!required.isEmpty()) {
            m.put("required", required);
        }
        return m;
    }

    private void addProperty(
            Map<String, Object> properties,
            List<Object> required,
            String javaName,
            List<AnnotatedElement> sources,
            ResolvableType propType,
            boolean primitive) {
        JsonIgnore ignore = firstAnnotation(sources, JsonIgnore.class);
        if (ignore != null && ignore.value()) {
            return;
        }
        JsonProperty jp = firstAnnotation(sources, JsonProperty.class);
        String name = jp != null && !jp.value().isEmpty() ? jp.value() : javaName;
        Map<String, Object> schema = schemaFor(propType);
        boolean isRef = isRef(schema);
        if (!isRef) {
            applyConstraints(schema, sources);
            if (jp != null && jp.access() == JsonProperty.Access.READ_ONLY) {
                schema.put("readOnly", Boolean.TRUE);
            } else if (jp != null && jp.access() == JsonProperty.Access.WRITE_ONLY) {
                schema.put("writeOnly", Boolean.TRUE);
            }
        }
        boolean isRequired =
                hasConstraint(sources, "NotNull", "NotBlank", "NotEmpty")
                        || (jp != null && jp.required());
        if (primitive) {
            isRequired = true;
            Object zero = primitiveDefault(propType.resolve());
            if (zero != null && !isRef) {
                schema.put("default", zero);
            }
        }
        properties.put(name, schema);
        if (isRequired && !required.contains(name)) {
            required.add(name);
        }
    }

    private static Object primitiveDefault(Class<?> c) {
        if (c == boolean.class) {
            return Boolean.FALSE;
        }
        if (c == byte.class
                || c == short.class
                || c == int.class
                || c == long.class
                || c == float.class
                || c == double.class) {
            return 0;
        }
        return null;
    }

    private Map<String, Object> pageSchema(ResolvableType type) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("content", arrayOf(schemaFor(type.getGeneric(0)), false));
        properties.put("totalElements", typed("integer", "int64"));
        properties.put("totalPages", typed("integer", "int32"));
        properties.put("number", typed("integer", "int32"));
        properties.put("size", typed("integer", "int32"));
        List<Object> required = new ArrayList<>();
        required.add("content");
        return objectSchema(properties, required);
    }

    // ------------------------------------------------------------------ annotations

    private static <A extends Annotation> A firstAnnotation(
            List<AnnotatedElement> sources, Class<A> type) {
        for (AnnotatedElement e : sources) {
            A a = e.getAnnotation(type);
            if (a != null) {
                return a;
            }
        }
        return null;
    }

    private static boolean isValidationAnnotation(Annotation a) {
        String n = a.annotationType().getName();
        return n.startsWith("jakarta.validation.constraints.")
                || n.startsWith("javax.validation.constraints.");
    }

    private static boolean hasConstraint(List<AnnotatedElement> sources, String... simpleNames) {
        for (AnnotatedElement e : sources) {
            for (Annotation a : e.getAnnotations()) {
                if (!isValidationAnnotation(a)) {
                    continue;
                }
                for (String s : simpleNames) {
                    if (a.annotationType().getSimpleName().equals(s)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void applyConstraints(
            Map<String, Object> schema, List<AnnotatedElement> sources) {
        Object typeValue = schema.get("type");
        String type = typeValue instanceof String s ? s : "";
        String minKey =
                "string".equals(type)
                        ? "minLength"
                        : "array".equals(type) ? "minItems" : "minProperties";
        String maxKey =
                "string".equals(type)
                        ? "maxLength"
                        : "array".equals(type) ? "maxItems" : "maxProperties";
        boolean sizable = "string".equals(type) || "array".equals(type) || "object".equals(type);
        for (AnnotatedElement e : sources) {
            for (Annotation a : e.getAnnotations()) {
                if (!isValidationAnnotation(a)) {
                    continue;
                }
                switch (a.annotationType().getSimpleName()) {
                    case "NotBlank", "NotEmpty" -> {
                        if (sizable) {
                            schema.put(minKey, 1);
                        }
                    }
                    case "Size" -> {
                        Object min = AnnotationUtils.getValue(a, "min");
                        Object max = AnnotationUtils.getValue(a, "max");
                        if (sizable && min instanceof Integer i && i > 0) {
                            schema.put(minKey, i);
                        }
                        if (sizable && max instanceof Integer i && i < Integer.MAX_VALUE) {
                            schema.put(maxKey, i);
                        }
                    }
                    case "Min", "DecimalMin" ->
                            putNumber(schema, "minimum", AnnotationUtils.getValue(a, "value"));
                    case "Max", "DecimalMax" ->
                            putNumber(schema, "maximum", AnnotationUtils.getValue(a, "value"));
                    case "Positive" -> schema.put("exclusiveMinimum", 0);
                    case "PositiveOrZero" -> schema.put("minimum", 0);
                    case "Negative" -> schema.put("exclusiveMaximum", 0);
                    case "NegativeOrZero" -> schema.put("maximum", 0);
                    case "Pattern" -> {
                        Object regexp = AnnotationUtils.getValue(a, "regexp");
                        if (regexp instanceof String r && !r.isEmpty()) {
                            schema.put("pattern", r);
                        }
                    }
                    case "Email" -> schema.put("format", "email");
                    default -> {
                        // other constraints do not map to a JSON Schema keyword
                    }
                }
            }
        }
    }

    private static void putNumber(Map<String, Object> schema, String key, Object raw) {
        if (raw instanceof Number n) {
            schema.put(key, n);
        } else if (raw instanceof String s) {
            try {
                schema.put(key, new BigDecimal(s));
            } catch (NumberFormatException e) {
                // unparsable bound: leave unconstrained
            }
        }
    }
}
