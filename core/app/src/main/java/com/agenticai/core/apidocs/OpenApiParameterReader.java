package com.agenticai.core.apidocs;

import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.ResolvableType;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Reads a handler method's argument list into OpenAPI parameters (path, query, header, cookie) and
 * the request body (JSON, multipart parts, or the declared {@code consumes} media types). Schemas
 * are produced by the run's shared {@link OpenApiSchemaBuilder} so DTOs land in {@code
 * components.schemas} once.
 */
final class OpenApiParameterReader {

    private static final ParameterNameDiscoverer NAMES = new DefaultParameterNameDiscoverer();

    private final OpenApiSchemaBuilder schemas;

    OpenApiParameterReader(OpenApiSchemaBuilder schemas) {
        this.schemas = schemas;
    }

    /** Everything collected from a handler's argument list. */
    static final class Params {
        final List<Object> parameters = new ArrayList<>();
        Map<String, Object> bodySchema;
        boolean bodyRequired = true;
        final Map<String, Object> partProperties = new LinkedHashMap<>();
        final List<Object> partRequired = new ArrayList<>();
        final Map<String, Object> partEncoding = new LinkedHashMap<>();
    }

    Params read(Class<?> controller, Method method, String path) {
        Params out = new Params();
        List<Map<String, Object>> pathParams = new ArrayList<>();
        List<Map<String, Object>> others = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> declaredPathVars = new HashSet<>();

        for (int i = 0; i < method.getParameterCount(); i++) {
            MethodParameter mp = new MethodParameter(method, i);
            mp.initParameterNameDiscovery(NAMES);
            ResolvableType type = ResolvableType.forMethodParameter(method, i, controller);
            Class<?> raw = type.resolve();
            String fallbackName = mp.getParameterName() != null ? mp.getParameterName() : "arg" + i;

            PathVariable pv = mp.getParameterAnnotation(PathVariable.class);
            RequestParam rp = mp.getParameterAnnotation(RequestParam.class);
            RequestHeader rh = mp.getParameterAnnotation(RequestHeader.class);
            CookieValue cv = mp.getParameterAnnotation(CookieValue.class);
            RequestBody rb = mp.getParameterAnnotation(RequestBody.class);
            RequestPart part = mp.getParameterAnnotation(RequestPart.class);

            if (pv != null) {
                if (isMap(raw)) {
                    continue;
                }
                String name = firstNonEmpty(pv.name(), pv.value(), fallbackName);
                if (seen.add("path:" + name)) {
                    declaredPathVars.add(name);
                    pathParams.add(parameter(name, "path", true, schemaOf(type), null));
                }
            } else if (rb != null) {
                out.bodySchema = schemas.schemaFor(type);
                out.bodyRequired = rb.required() && raw != Optional.class;
            } else if (part != null) {
                String name = firstNonEmpty(part.name(), part.value(), fallbackName);
                addPart(out, name, type, part.required());
            } else if (rh != null) {
                if (isMap(raw)
                        || (raw != null
                                && org.springframework.http.HttpHeaders.class.isAssignableFrom(
                                        raw))) {
                    continue;
                }
                String name = firstNonEmpty(rh.name(), rh.value(), fallbackName);
                if (seen.add("header:" + name)) {
                    others.add(
                            parameter(
                                    name,
                                    "header",
                                    isRequired(rh.required(), rh.defaultValue(), raw),
                                    schemaOf(type),
                                    rh.defaultValue()));
                }
            } else if (cv != null) {
                String name = firstNonEmpty(cv.name(), cv.value(), fallbackName);
                if (seen.add("cookie:" + name)) {
                    others.add(
                            parameter(
                                    name,
                                    "cookie",
                                    isRequired(cv.required(), cv.defaultValue(), raw),
                                    schemaOf(type),
                                    cv.defaultValue()));
                }
            } else if (rp != null) {
                String name = firstNonEmpty(rp.name(), rp.value(), fallbackName);
                if (isFilePart(type)) {
                    addPart(out, name, type, rp.required());
                } else if (!isMap(raw)) {
                    if (seen.add("query:" + name)) {
                        others.add(
                                parameter(
                                        name,
                                        "query",
                                        isRequired(rp.required(), rp.defaultValue(), raw),
                                        schemaOf(type),
                                        rp.defaultValue()));
                    }
                }
            } else if (isFilePart(type)) {
                addPart(out, fallbackName, type, false);
            } else if (isImplicitQueryType(raw)) {
                if (seen.add("query:" + fallbackName)) {
                    others.add(parameter(fallbackName, "query", false, schemaOf(type), null));
                }
            }
            // Anything else (Principal, HttpServletRequest, Locale, @ModelAttribute, ...) is
            // framework-injected.
        }

        // Every {var} in the path MUST be declared (OpenAPI rule), even when the handler binds
        // it as a Map.
        Matcher m = OpenApiSpecGenerator.PATH_VAR.matcher(path);
        while (m.find()) {
            String name = m.group(1);
            if (!declaredPathVars.contains(name) && seen.add("path:" + name)) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("type", "string");
                pathParams.add(parameter(name, "path", true, s, null));
            }
        }
        // Keep only path params whose name really appears in the template (a handler may name
        // them differently).
        Set<String> templateVars = new HashSet<>();
        Matcher tm = OpenApiSpecGenerator.PATH_VAR.matcher(path);
        while (tm.find()) {
            templateVars.add(tm.group(1));
        }
        for (Map<String, Object> p : pathParams) {
            if (templateVars.contains(String.valueOf(p.get("name")))) {
                out.parameters.add(p);
            }
        }
        out.parameters.addAll(others);
        return out;
    }

    private Map<String, Object> schemaOf(ResolvableType type) {
        return schemas.schemaFor(type);
    }

    private static boolean isRequired(boolean declared, String defaultValue, Class<?> raw) {
        return declared
                && ValueConstants.DEFAULT_NONE.equals(defaultValue)
                && raw != Optional.class;
    }

    private static Map<String, Object> parameter(
            String name,
            String in,
            boolean required,
            Map<String, Object> schema,
            String defaultValue) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("in", in);
        p.put("required", required || "path".equals(in));
        if (defaultValue != null
                && !ValueConstants.DEFAULT_NONE.equals(defaultValue)
                && !OpenApiSchemaBuilder.isRef(schema)) {
            schema.put("default", coerce(schema, defaultValue));
        }
        p.put("schema", schema);
        return p;
    }

    private static Object coerce(Map<String, Object> schema, String value) {
        Object type = schema.get("type");
        try {
            if ("integer".equals(type)) {
                return Long.parseLong(value.trim());
            }
            if ("number".equals(type)) {
                return new java.math.BigDecimal(value.trim());
            }
        } catch (NumberFormatException e) {
            return value;
        }
        if ("boolean".equals(type)) {
            String t = value.trim();
            if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
                return Boolean.parseBoolean(t);
            }
        }
        return value;
    }

    private static boolean isMap(Class<?> raw) {
        return raw != null && Map.class.isAssignableFrom(raw);
    }

    private static ResolvableType partElement(ResolvableType t) {
        Class<?> r = t.resolve();
        if (r == null) {
            return t;
        }
        if (r.isArray()) {
            return t.getComponentType();
        }
        if (Collection.class.isAssignableFrom(r)) {
            return t.asCollection().getGeneric(0);
        }
        return t;
    }

    private static boolean isFilePart(ResolvableType t) {
        Class<?> r = partElement(t).resolve();
        return r != null
                && (MultipartFile.class.isAssignableFrom(r)
                        || r.getName().equals("jakarta.servlet.http.Part"));
    }

    /** Unannotated simple-typed arguments bind as optional query parameters in Spring MVC. */
    private static boolean isImplicitQueryType(Class<?> raw) {
        if (raw == null) {
            return false;
        }
        return raw.isPrimitive()
                || Number.class.isAssignableFrom(raw)
                || CharSequence.class.isAssignableFrom(raw)
                || raw == Boolean.class
                || raw == Character.class
                || raw.isEnum()
                || raw == java.util.UUID.class
                || java.util.Date.class.isAssignableFrom(raw)
                || java.time.temporal.Temporal.class.isAssignableFrom(raw);
    }

    private void addPart(Params out, String name, ResolvableType type, boolean required) {
        Map<String, Object> s;
        if (isFilePart(type)) {
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("type", "string");
            file.put("format", "binary");
            Class<?> r = type.resolve();
            boolean many = r != null && (r.isArray() || Collection.class.isAssignableFrom(r));
            if (many) {
                s = new LinkedHashMap<>();
                s.put("type", "array");
                s.put("items", file);
            } else {
                s = file;
            }
        } else {
            s = schemas.schemaFor(type);
            Map<String, Object> enc = new LinkedHashMap<>();
            enc.put("contentType", OpenApiSpecGenerator.JSON);
            out.partEncoding.put(name, enc);
        }
        out.partProperties.put(name, s);
        if (required) {
            out.partRequired.add(name);
        }
    }

    static Map<String, Object> requestBody(
            RequestMapping classMapping, RequestMapping mapping, Params params) {
        Map<String, Object> content = new LinkedHashMap<>();
        boolean required;
        if (!params.partProperties.isEmpty()) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", params.partProperties);
            if (!params.partRequired.isEmpty()) {
                schema.put("required", params.partRequired);
            }
            Map<String, Object> media = new LinkedHashMap<>();
            media.put("schema", schema);
            if (!params.partEncoding.isEmpty()) {
                media.put("encoding", params.partEncoding);
            }
            content.put("multipart/form-data", media);
            required = !params.partRequired.isEmpty();
        } else if (params.bodySchema != null) {
            List<String> types =
                    mediaTypes(
                            mapping.consumes(),
                            classMapping == null ? null : classMapping.consumes());
            if (types.isEmpty()) {
                types = List.of(OpenApiSpecGenerator.JSON);
            }
            for (String t : types) {
                Map<String, Object> media = new LinkedHashMap<>();
                media.put("schema", copyOf(params.bodySchema));
                content.put(t, media);
            }
            required = params.bodyRequired;
        } else {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("required", required);
        body.put("content", content);
        return body;
    }

    private static Map<String, Object> copyOf(Map<String, Object> schema) {
        return new LinkedHashMap<>(schema);
    }

    /**
     * Declared media types of the method, else of the class; negated ("!x") entries are ignored.
     */
    static List<String> mediaTypes(String[] method, String[] clazz) {
        List<String> out = positive(method);
        return out.isEmpty() ? positive(clazz) : out;
    }

    private static List<String> positive(String[] values) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank() && !v.startsWith("!") && !out.contains(v)) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }
}
