package com.agenticai.core.apidocs;

import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;

/**
 * Derives a handler's documented success response for {@link OpenApiSpecGenerator}: the 2xx
 * status ({@code @ResponseStatus}, else the one constant status the method body sets on its
 * {@code ResponseEntity} as found by {@link OpenApiBodyStatusScanner}, else 204 for an empty
 * DELETE, else 200; a 204 response carries no content), the body schema after
 * stripping transport wrappers (ResponseEntity, Optional, futures, Mono, Callable, DeferredResult),
 * {@code Flux} as an array or an SSE stream, and binary bodies. The response-side counterpart of
 * {@link OpenApiParameterReader}.
 */
final class OpenApiResponseReader {

    private static final String SSE = "text/event-stream";
    private static final String OCTET = "application/octet-stream";

    private final OpenApiSchemaBuilder schemas;
    private final OpenApiBodyStatusScanner bodyStatus = new OpenApiBodyStatusScanner();

    OpenApiResponseReader(OpenApiSchemaBuilder schemas) {
        this.schemas = schemas;
    }

    /** The documented success response of a handler. */
    static final class ResponseShape {
        String status = "200";
        Map<String, Object> response;
        boolean sse;
        Map<String, Object> sseDataSchema;
    }

    ResponseShape read(
            Class<?> controller,
            Method method,
            RequestMapping mapping,
            RequestMapping classMapping) {
        ResponseShape shape = new ResponseShape();
        ResolvableType type = unwrap(ResolvableType.forMethodReturnType(method, controller));
        Class<?> raw = type.resolve();
        List<String> declared =
                OpenApiParameterReader.mediaTypes(
                        mapping.produces(),
                        classMapping == null ? null : classMapping.produces());

        boolean empty = false;
        boolean binary = false;
        ResolvableType bodyType = type;
        boolean arrayOfElement = false;

        if (raw == null) {
            bodyType = ResolvableType.NONE;
        } else if (raw == void.class || raw == Void.class) {
            empty = true;
        } else if (raw.getName().equals("reactor.core.publisher.Flux")) {
            ResolvableType element = type.getGeneric(0);
            Class<?> er = element.resolve();
            boolean serverSentEvent =
                    er != null
                            && er.getName()
                                    .equals("org.springframework.http.codec.ServerSentEvent");
            if (serverSentEvent) {
                element = element.getGeneric(0);
            }
            if (serverSentEvent || declared.contains(SSE)) {
                shape.sse = true;
                Class<?> dr = element.resolve();
                if (dr != null && dr != String.class && dr != Object.class) {
                    shape.sseDataSchema = schemas.schemaFor(element);
                }
            } else {
                bodyType = element;
                arrayOfElement = true;
            }
        } else if (ResponseBodyEmitter.class.isAssignableFrom(raw)) {
            shape.sse = true;
        } else if (StreamingResponseBody.class.isAssignableFrom(raw)
                || Resource.class.isAssignableFrom(raw)
                || raw == byte[].class) {
            binary = true;
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put(
                "description",
                shape.sse ? "Server-Sent Events stream." : "Successful response.");
        Map<String, Object> content = new LinkedHashMap<>();
        if (shape.sse) {
            List<String> types = new ArrayList<>(List.of(SSE));
            for (String t : types) {
                Map<String, Object> media = new LinkedHashMap<>();
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "string");
                media.put("schema", schema);
                content.put(t, media);
            }
        } else if (binary) {
            List<String> types = declared.isEmpty() ? List.of(OCTET) : declared;
            for (String t : types) {
                Map<String, Object> media = new LinkedHashMap<>();
                Map<String, Object> schema = new LinkedHashMap<>();
                schema.put("type", "string");
                schema.put("format", "binary");
                media.put("schema", schema);
                content.put(t, media);
            }
        } else if (!empty) {
            Map<String, Object> schema = schemas.schemaFor(bodyType);
            if (arrayOfElement) {
                Map<String, Object> arr = new LinkedHashMap<>();
                arr.put("type", "array");
                arr.put("items", schema);
                schema = arr;
            }
            List<String> types = declared;
            if (types.isEmpty()) {
                types = raw == String.class ? List.of("text/plain") : List.of(OpenApiSpecGenerator.JSON);
            }
            for (String t : types) {
                Map<String, Object> media = new LinkedHashMap<>();
                media.put("schema", new LinkedHashMap<>(schema));
                content.put(t, media);
            }
        }
        ResponseStatus rs =
                AnnotatedElementUtils.findMergedAnnotation(method, ResponseStatus.class);
        if (rs == null) {
            rs = AnnotatedElementUtils.findMergedAnnotation(controller, ResponseStatus.class);
        }
        Optional<Integer> fromBody =
                rs == null && !shape.sse ? bodyStatus.successStatus(method) : Optional.empty();
        if (rs != null) {
            shape.status = String.valueOf(rs.code().value());
        } else if (fromBody.isPresent()) {
            shape.status = String.valueOf(fromBody.get());
        } else if (empty
                && mapping.method().length > 0
                && mapping.method()[0] == RequestMethod.DELETE) {
            shape.status = "204";
        }
        if (!content.isEmpty() && !"204".equals(shape.status)) {
            response.put("content", content);
        }
        shape.response = response;
        return shape;
    }

    /**
     * Strips transport wrappers (ResponseEntity, Optional, futures, Mono, Callable,
     * DeferredResult).
     */
    private static ResolvableType unwrap(ResolvableType start) {
        ResolvableType t = start;
        for (int i = 0; i < 8; i++) {
            Class<?> raw = t.resolve();
            if (raw == null) {
                break;
            }
            String n = raw.getName();
            boolean wrapper =
                    HttpEntity.class.isAssignableFrom(raw)
                            || raw == Optional.class
                            || CompletionStage.class.isAssignableFrom(raw)
                            || raw == Callable.class
                            || n.equals(
                                    "org.springframework.web.context.request.async.DeferredResult")
                            || n.equals(
                                    "org.springframework.web.context.request.async.WebAsyncTask")
                            || n.equals("reactor.core.publisher.Mono");
            if (!wrapper) {
                break;
            }
            t = t.getGeneric(0);
        }
        return t;
    }
}
