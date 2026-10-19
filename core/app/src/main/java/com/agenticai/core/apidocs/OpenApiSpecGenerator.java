package com.agenticai.core.apidocs;

import com.agenticai.core.common.security.PublicEndpointRateLimitFilter;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * Pure reflection-based OpenAPI 3.1 generator for the platform's REST surface.
 *
 * <p>It scans the classpath for {@code @RestController} classes under {@code com.agenticai.core}
 * with Spring's {@link ClassPathScanningCandidateComponentProvider}, reads the mapping annotations
 * with {@link AnnotatedElementUtils}, and walks DTO records/POJOs via {@link OpenApiSchemaBuilder}.
 * It needs NO application context and NO database, and adds NO dependency (deliberately not
 * springdoc: that starter would auto-expose endpoints and the offline Maven build must keep
 * working).
 *
 * <p>What is derived: paths and verbs (class-level base path merged with the method path, regex
 * path-variable constraints stripped), {@code operationId = <ControllerSimpleName>_<method>} made
 * unique, one tag per controller (see {@link OpenApiControllerAliases}), path/query/header/cookie
 * parameters, the request body (JSON, multipart or declared {@code consumes}), the success response
 * from the return type (unwrapping ResponseEntity / Optional / CompletableFuture / Mono /
 * Callable), SSE streams flagged with {@code x-sse: true}, shared error responses matching {@code
 * GlobalExceptionHandler}'s body, and a per-operation {@code security} requirement (see {@link
 * OpenApiSecurity}). Javadoc is not on the classpath, so prose that reflection cannot derive
 * (session ownership, feature flags) comes from the curated {@link OpenApiDescriptions} map, keyed
 * by verb and path, and leads the operation's {@code description}.
 *
 * <p>The success status is {@code @ResponseStatus} when present, else the one constant 2xx status
 * the handler's body sets on its {@code ResponseEntity} (read from bytecode by {@link
 * OpenApiBodyStatusScanner}: {@code created}, {@code accepted}, {@code noContent}, {@code
 * status(HttpStatus.X)}), else 204 for an empty DELETE, else 200.
 *
 * <p>What is NOT known: the exact 2xx status when a handler picks it at run time from a variable or
 * sets two different ones (documented by the default; clients should accept any 2xx),
 * per-operation error codes beyond the generic set, and permission requirements finer than
 * "authenticated".
 *
 * <p>Output is deterministic: paths, verbs, components and the skipped list are sorted, record
 * properties keep declaration order, and nothing time- or machine-dependent is embedded. A handler
 * that cannot be described is skipped and listed under {@code info.x-skipped}; the generator never
 * throws for an odd handler.
 */
public final class OpenApiSpecGenerator {

    /** Root package scanned for {@code @RestController} classes. */
    public static final String BASE_PACKAGE = "com.agenticai.core";

    /** Version used when nothing better can be detected; mirrors core/pom.xml without -SNAPSHOT. */
    static final String DEFAULT_VERSION = "0.1.0";

    static final String JSON = "application/json";
    private static final List<String> VERB_ORDER =
            List.of("get", "put", "post", "delete", "patch", "options", "head", "trace");

    /** A {@code {name}} variable in a normalized path template. */
    static final Pattern PATH_VAR = Pattern.compile("\\{([^/{}]+)}");

    private final String version;

    public OpenApiSpecGenerator(String version) {
        this.version = version == null || version.isBlank() ? DEFAULT_VERSION : version;
    }

    /**
     * Generator whose version comes from {@code -Dopenapi.version}, the jar's pom.properties, or
     * the default.
     */
    public static OpenApiSpecGenerator withDetectedVersion() {
        return new OpenApiSpecGenerator(detectVersion());
    }

    static String detectVersion() {
        String prop = System.getProperty("openapi.version");
        if (prop != null && !prop.isBlank()) {
            return stripSnapshot(prop.trim());
        }
        try (InputStream in =
                OpenApiSpecGenerator.class.getResourceAsStream(
                        "/META-INF/maven/com.agenticai/app/pom.properties")) {
            if (in != null) {
                Properties p = new Properties();
                p.load(in);
                String v = p.getProperty("version");
                if (v != null && !v.isBlank()) {
                    return stripSnapshot(v.trim());
                }
            }
        } catch (IOException e) {
            // fall through to the default
        }
        return DEFAULT_VERSION;
    }

    private static String stripSnapshot(String v) {
        return v.endsWith("-SNAPSHOT") ? v.substring(0, v.length() - "-SNAPSHOT".length()) : v;
    }

    // ------------------------------------------------------------------ entry points

    /** Scans the classpath for controllers and generates the document. */
    public Map<String, Object> generate() {
        List<String> skipped = new ArrayList<>();
        List<Class<?>> controllers = scanControllers(BASE_PACKAGE, skipped);
        return new Run(version, skipped).generate(controllers);
    }

    /** Generates the document for an explicit controller set (used by the fixture test). */
    public Map<String, Object> generate(Collection<? extends Class<?>> controllers) {
        return new Run(version, new ArrayList<>()).generate(new ArrayList<>(controllers));
    }

    /** Convenience: {@link #generate()} serialized as stable, LF-terminated pretty JSON. */
    public String generateJson() {
        return toJson(generate());
    }

    public static String toJson(Map<String, Object> document) {
        return OpenApiJsonWriter.write(document);
    }

    static List<Class<?>> scanControllers(String basePackage, List<String> skipped) {
        // Spring's scanner drops every class whose @Conditional is not satisfied by the scanning
        // Environment, which
        // would silently remove all feature-flagged endpoints (tutor, /v1/*, research, import ...).
        // They are part
        // of the product's API, so skip condition evaluation and note the gating flag on each
        // operation instead.
        AnnotationTypeFilter restControllers = new AnnotationTypeFilter(RestController.class);
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(MetadataReader metadataReader)
                            throws IOException {
                        return restControllers.match(metadataReader, getMetadataReaderFactory());
                    }
                };
        scanner.addIncludeFilter(restControllers);
        Set<String> names = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
            if (bd.getBeanClassName() != null) {
                names.add(bd.getBeanClassName());
            }
        }
        ClassLoader loader = ClassUtils.getDefaultClassLoader();
        List<Class<?>> out = new ArrayList<>();
        for (String name : names) {
            try {
                out.add(ClassUtils.forName(name, loader));
            } catch (ClassNotFoundException | LinkageError e) {
                skipped.add(
                        "controller "
                                + name
                                + ": could not be loaded ("
                                + e.getClass().getSimpleName()
                                + ")");
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ one generation run

    private static final class Run {
        private final String version;
        private final List<String> skipped;
        private final OpenApiSchemaBuilder schemas;
        private final OpenApiParameterReader parameterReader;
        private final OpenApiResponseReader responseReader;
        private final Map<String, Map<String, Object>> paths = new TreeMap<>();
        private final Map<String, Integer> operationIdUses = new HashMap<>();
        private final Set<String> tags = new TreeSet<>();
        private final OpenApiOperationConventions conventions =
                new OpenApiOperationConventions(OpenApiPermissions.load());

        Run(String version, List<String> skipped) {
            this.version = version;
            this.skipped = skipped;
            this.schemas = new OpenApiSchemaBuilder(skipped);
            this.parameterReader = new OpenApiParameterReader(schemas);
            this.responseReader = new OpenApiResponseReader(schemas);
        }

        Map<String, Object> generate(List<Class<?>> controllers) {
            schemas.registerFixed("Error", OpenApiFixedComponents.errorSchema());
            List<Class<?>> sorted = new ArrayList<>(controllers);
            sorted.sort((a, b) -> a.getName().compareTo(b.getName()));
            for (Class<?> controller : sorted) {
                try {
                    addController(controller);
                } catch (RuntimeException | LinkageError e) {
                    skipped.add(
                            "controller "
                                    + controller.getName()
                                    + ": "
                                    + e.getClass().getSimpleName());
                }
            }
            return document();
        }

        // -------------------------------------------------------------- document assembly

        private Map<String, Object> document() {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("openapi", "3.1.0");
            doc.put("info", info());
            // No built-in deployment: the base URL is a server variable each deployment fills in.
            Map<String, Object> baseUrl = new LinkedHashMap<>();
            baseUrl.put("default", "http://localhost:8080");
            baseUrl.put(
                    "description",
                    "Your VegaDuta API origin, e.g. https://api.example.com (local Docker Compose:"
                            + " http://localhost:8080).");
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("url", "{baseUrl}");
            server.put("description", "Your deployment's API.");
            server.put("variables", Map.of("baseUrl", baseUrl));
            doc.put("servers", List.of(server));
            List<Object> tagList = new ArrayList<>();
            for (String t : tags) {
                Map<String, Object> tag = new LinkedHashMap<>();
                tag.put("name", t);
                tagList.add(tag);
            }
            doc.put("tags", tagList);
            Map<String, Object> externalDocs = new LinkedHashMap<>();
            externalDocs.put(
                    "description", "API conventions: errors, auth, paging, SDKs (docs/api/README.md)");
            externalDocs.put("url", "https://github.com/CD-LC/vegaduta/blob/main/docs/api/README.md");
            doc.put("externalDocs", externalDocs);
            Map<String, Object> orderedPaths = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> e : paths.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                for (String verb : VERB_ORDER) {
                    if (e.getValue().containsKey(verb)) {
                        item.put(verb, e.getValue().get(verb));
                    }
                }
                orderedPaths.put(e.getKey(), item);
            }
            doc.put("paths", orderedPaths);
            doc.put("webhooks", OpenApiWebhooks.webhooks(schemas));
            Map<String, Object> components = new LinkedHashMap<>();
            components.put("headers", OpenApiFixedComponents.headers());
            components.put("parameters", OpenApiFixedComponents.parameters());
            components.put("responses", OpenApiFixedComponents.errorResponses());
            components.put("schemas", schemas.components());
            components.put("securitySchemes", OpenApiFixedComponents.securitySchemes());
            doc.put("components", components);
            return doc;
        }

        private Map<String, Object> info() {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("title", "VegaDuta API");
            info.put("version", version);
            info.put(
                    "description",
                    "REST API of the VegaDuta agentic AI platform. Generated from the Spring"
                        + " controllers by OpenApiSpecGenerator; do not edit by hand. Errors are"
                        + " RFC 9457 Problem Details (application/problem+json) described by the"
                        + " Error schema; branch on its type or code, not on the message. Every"
                        + " response carries an X-Request-Id correlation header. Endpoints flagged"
                        + " x-sse stream Server-Sent Events. Where a handler builds its own"
                        + " ResponseEntity the exact 2xx status may differ from the documented one,"
                        + " so clients should accept any 2xx.");
            Map<String, Object> license = new LinkedHashMap<>();
            license.put("name", "BUSL-1.1");
            license.put("identifier", "BUSL-1.1");
            info.put("license", license);
            info.put("x-skipped", new ArrayList<>(new TreeSet<>(skipped)));
            return info;
        }

        // -------------------------------------------------------------- controllers

        private void addController(Class<?> controller) {
            RequestMapping classMapping =
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            List<String> bases = classMapping == null ? List.of("") : pathsOf(classMapping);
            String tag = tagName(controller);
            MethodIntrospector.MetadataLookup<RequestMapping> lookup =
                    m -> AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
            Map<Method, RequestMapping> handlers =
                    MethodIntrospector.selectMethods(controller, lookup);
            List<Map.Entry<Method, RequestMapping>> ordered = new ArrayList<>(handlers.entrySet());
            ordered.sort(
                    Comparator.comparing(
                                    (Map.Entry<Method, RequestMapping> e) -> e.getKey().getName())
                            .thenComparing(e -> Arrays.toString(e.getKey().getParameterTypes())));
            for (Map.Entry<Method, RequestMapping> e : ordered) {
                String where = controller.getSimpleName() + "#" + e.getKey().getName();
                try {
                    addHandler(
                            controller, tag, bases, classMapping, e.getKey(), e.getValue(), where);
                } catch (RuntimeException | LinkageError ex) {
                    skipped.add(where + ": " + ex.getClass().getSimpleName());
                }
            }
        }

        /** "app.tutor.enabled=true" for a controller gated by @ConditionalOnProperty, else null. */
        private static String featureFlag(Class<?> controller) {
            ConditionalOnProperty cond =
                    AnnotatedElementUtils.findMergedAnnotation(
                            controller, ConditionalOnProperty.class);
            if (cond == null) {
                return null;
            }
            String[] names = cond.name().length > 0 ? cond.name() : cond.value();
            if (names.length == 0) {
                return null;
            }
            String prefix = cond.prefix().isEmpty() ? "" : cond.prefix() + ".";
            String expected = cond.havingValue().isEmpty() ? "true" : cond.havingValue();
            return prefix + names[0] + "=" + expected;
        }

        private static String tagName(Class<?> controller) {
            String n = OpenApiControllerAliases.publishedName(controller);
            return n.length() > "Controller".length() && n.endsWith("Controller")
                    ? n.substring(0, n.length() - "Controller".length())
                    : n;
        }

        private static List<String> pathsOf(RequestMapping m) {
            return m.path().length == 0 ? List.of("") : Arrays.asList(m.path());
        }

        private void addHandler(
                Class<?> controller,
                String tag,
                List<String> bases,
                RequestMapping classMapping,
                Method method,
                RequestMapping mapping,
                String where) {
            if (mapping.method().length == 0) {
                skipped.add(where + ": no HTTP method declared on the mapping");
                return;
            }
            List<RequestMethod> verbs = new ArrayList<>(Arrays.asList(mapping.method()));
            verbs.sort(Comparator.comparingInt(RequestMethod::ordinal));
            for (String base : bases) {
                for (String sub : pathsOf(mapping)) {
                    String path = normalizePath(base, sub);
                    if (path == null) {
                        skipped.add(where + ": unsupported path pattern '" + base + sub + "'");
                        continue;
                    }
                    for (RequestMethod verb : verbs) {
                        String v = verb.name().toLowerCase(java.util.Locale.ROOT);
                        Map<String, Object> existing = paths.get(path);
                        if (existing != null && existing.containsKey(v)) {
                            skipped.add(where + ": duplicate " + verb.name() + " " + path);
                            continue;
                        }
                        Map<String, Object> op =
                                buildOperation(
                                        controller, tag, classMapping, method, mapping, path, v);
                        paths.computeIfAbsent(path, k -> new LinkedHashMap<>()).put(v, op);
                        tags.add(tag);
                    }
                }
            }
        }

        // -------------------------------------------------------------- operation

        private Map<String, Object> buildOperation(
                Class<?> controller,
                String tag,
                RequestMapping classMapping,
                Method method,
                RequestMapping mapping,
                String path,
                String verb) {
            Map<String, Object> op = new LinkedHashMap<>();
            op.put("tags", new ArrayList<Object>(List.of(tag)));
            op.put("summary", humanize(method.getName()));
            op.put(
                    "operationId",
                    uniqueOperationId(
                            OpenApiControllerAliases.publishedName(controller) + "_" + method.getName()));

            OpenApiParameterReader.Params params = parameterReader.read(controller, method, path);
            List<Object> securityReq =
                    OpenApiSecurity.requirementsFor(verb.toUpperCase(java.util.Locale.ROOT), path);
            boolean secured = !securityReq.isEmpty();

            List<String> notes = new ArrayList<>();
            // Curated prose first (ownership, feature flag), then the derived notes.
            String curated = OpenApiDescriptions.describe(verb, path);
            if (curated != null) {
                notes.add(curated);
            }
            if (!secured) {
                notes.add(
                        "Public route: no bearer token is required at the gateway. Webhook, device"
                                + " and pairing endpoints verify their own credential (signature,"
                                + " device token or code) inside the handler.");
            } else if (OpenApiSecurity.isGrantKeyOnly(path)) {
                notes.add("Authenticated with a long-lived vmcp_ grant key, not a user JWT.");
            } else if (OpenApiSecurity.isJwtOrGrantKey(path)) {
                notes.add(
                        "OpenAI-compatible surface: authenticate with a platform JWT or a vmcp_"
                                + " grant key as the api_key.");
            }

            if (!params.parameters.isEmpty()) {
                op.put("parameters", params.parameters);
            }
            Map<String, Object> body =
                    OpenApiParameterReader.requestBody(classMapping, mapping, params);
            if (body != null) {
                op.put("requestBody", body);
            }

            OpenApiResponseReader.ResponseShape shape =
                    responseReader.read(controller, method, mapping, classMapping);
            String featureFlag = featureFlag(controller);
            if (featureFlag != null) {
                op.put("x-feature-flag", featureFlag);
                notes.add(
                        "Only available when the feature flag "
                                + featureFlag
                                + " is on; otherwise this route answers 404.");
            }
            if (shape.sse) {
                op.put("x-sse", Boolean.TRUE);
                if (shape.sseDataSchema != null) {
                    op.put("x-sse-data-schema", shape.sseDataSchema);
                }
                notes.add(
                        "Streams the response as Server-Sent Events (text/event-stream); each"
                                + " event's data field carries the next chunk.");
            }
            if (!notes.isEmpty()) {
                op.put("description", String.join(" ", notes));
            }

            Map<String, Object> responses = new LinkedHashMap<>();
            responses.put(shape.status, withPagingHeaders(shape.response, params.parameters));
            boolean hasInput = body != null || !params.parameters.isEmpty();
            if (hasInput) {
                responses.put("400", OpenApiFixedComponents.errorRef("BadRequest"));
            }
            if (secured) {
                responses.put("401", OpenApiFixedComponents.errorRef("Unauthorized"));
                responses.put("403", OpenApiFixedComponents.errorRef("Forbidden"));
            }
            if (PATH_VAR.matcher(path).find()) {
                responses.put("404", OpenApiFixedComponents.errorRef("NotFound"));
            }
            if (PublicEndpointRateLimitFilter.throttles(verb, path)) {
                responses.put("429", OpenApiFixedComponents.errorRef("TooManyRequests"));
            }
            responses.put("500", OpenApiFixedComponents.errorRef("InternalServerError"));
            op.put("security", securityReq);
            conventions.apply(op, responses, shape.status, controller, method, verb, path);
            op.remove("security"); // keep "responses" before "security", as before
            op.put("responses", sortedResponses(responses));
            op.put("security", securityReq);
            return op;
        }

        /**
         * A list endpoint that takes both {@code limit} and {@code offset} documents the paging
         * headers on its success response (sent when {@code limit} is given; see
         * LIST-ENDPOINT-PAGING.md). OpenAPI response headers are optional, so this does not
         * promise them on an unpaged call.
         */
        @SuppressWarnings("unchecked")
        private static Map<String, Object> withPagingHeaders(
                Map<String, Object> response, List<Object> parameters) {
            Set<String> query = new TreeSet<>();
            for (Object p : parameters) {
                if (p instanceof Map<?, ?> m && "query".equals(m.get("in"))) {
                    query.add(String.valueOf(m.get("name")));
                }
            }
            if (!query.contains("limit") || !query.contains("offset")) {
                return response;
            }
            Map<String, Object> copy = new LinkedHashMap<>(response);
            Map<String, Object> headers = new LinkedHashMap<>();
            Object existing = copy.get("headers");
            if (existing instanceof Map<?, ?> h) {
                headers.putAll((Map<String, Object>) h);
            }
            headers.put(
                    OpenApiFixedComponents.HAS_MORE,
                    OpenApiFixedComponents.headerRef(OpenApiFixedComponents.HAS_MORE));
            headers.put(
                    OpenApiFixedComponents.TOTAL_COUNT,
                    OpenApiFixedComponents.headerRef(OpenApiFixedComponents.TOTAL_COUNT));
            // "headers" sits before "content", as in the shared error responses.
            Map<String, Object> out = new LinkedHashMap<>();
            Object description = copy.remove("description");
            if (description != null) {
                out.put("description", description);
            }
            out.put("headers", headers);
            copy.remove("headers");
            out.putAll(copy);
            return out;
        }

        private static Map<String, Object> sortedResponses(Map<String, Object> responses) {
            return new TreeMap<>(responses);
        }

        private String uniqueOperationId(String base) {
            String clean = base.replaceAll("[^A-Za-z0-9_]", "_");
            int n = operationIdUses.merge(clean, 1, Integer::sum);
            return n == 1 ? clean : clean + "_" + n;
        }

        private static String humanize(String name) {
            String spaced =
                    name.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                            .replace('_', ' ')
                            .trim()
                            .toLowerCase(java.util.Locale.ROOT);
            return spaced.isEmpty()
                    ? name
                    : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
        }
    }

    /**
     * Joins a class-level and method-level path into an OpenAPI path template: slashes collapsed,
     * trailing slash dropped, and regex constraints in {@code {name:regex}} variables removed
     * (OpenAPI has no regex path syntax). Returns null for paths OpenAPI cannot express (ant
     * wildcards, unresolved property placeholders).
     */
    static String normalizePath(String base, String sub) {
        String joined = "/" + (base == null ? "" : base) + "/" + (sub == null ? "" : sub);
        if (joined.contains("${") || joined.contains("?")) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < joined.length()) {
            char ch = joined.charAt(i);
            if (ch != '{') {
                out.append(ch);
                i++;
                continue;
            }
            int depth = 1;
            int j = i + 1;
            while (j < joined.length() && depth > 0) {
                char c = joined.charAt(j);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
                j++;
            }
            if (depth != 0) {
                return null;
            }
            String inner = joined.substring(i + 1, j - 1);
            int colon = inner.indexOf(':');
            String name = (colon >= 0 ? inner.substring(0, colon) : inner).replace("*", "").trim();
            if (name.isEmpty()) {
                return null;
            }
            out.append('{').append(name).append('}');
            i = j;
        }
        String p = out.toString().replaceAll("/{2,}", "/");
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.contains("*")) {
            return null;
        }
        return p;
    }
}
