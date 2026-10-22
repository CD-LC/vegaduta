package com.agenticai.core.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Golden-small check of {@link OpenApiSpecGenerator} against tiny fixture controllers: header, tags
 * and paths, one operation of each shape (create, parameters, streaming, delete, regex path, public
 * route), the component schemas and the skipped-handler list. The structural checks over the real
 * controller set live in {@link OpenApiSpecGeneratorTest}.
 */
class OpenApiFixtureSpecTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    // ================================================================== golden-small fixture

    enum Kind {
        SMALL,
        LARGE
    }

    record Widget(
            UUID id,
            @NotBlank @Size(max = 40) String name,
            int count,
            List<String> tags,
            Kind kind,
            Widget parent) {}

    record CreateWidget(
            @NotBlank String name,
            @Min(1) Integer quantity,
            @JsonProperty("note_text") String note,
            @JsonIgnore String secret) {}

    @RequestMapping("/api/fixture")
    static class FixtureController {

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        public Widget create(@RequestBody CreateWidget body) {
            return null;
        }

        @GetMapping("/{id}")
        public ResponseEntity<Widget> get(
                @PathVariable UUID id,
                @RequestParam(required = false) Integer limit,
                @RequestHeader("X-Trace") String trace,
                Principal principal) {
            return null;
        }

        @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter events(@PathVariable UUID id) {
            return null;
        }

        @DeleteMapping("/{id}")
        public ResponseEntity<Void> delete(@PathVariable UUID id) {
            return null;
        }

        @GetMapping("/odd/{rest:.+}")
        public Map<String, Object> odd(@PathVariable("rest") String rest) {
            return null;
        }
    }

    static class PublicFixtureController {

        @PostMapping("/api/signup")
        public Map<String, String> signup(@RequestBody Map<String, String> body) {
            return null;
        }
    }

    @RequestMapping("/api/odd")
    static class OddController {

        @RequestMapping("/nomethod")
        public void anyMethod() {}

        @GetMapping("/files/**")
        public String wild() {
            return null;
        }
    }

    private static JsonNode fixtureDoc() throws IOException {
        Map<String, Object> doc =
                new OpenApiSpecGenerator("9.9.9")
                        .generate(
                                List.of(
                                        FixtureController.class,
                                        PublicFixtureController.class,
                                        OddController.class));
        return MAPPER.readTree(OpenApiSpecGenerator.toJson(doc));
    }

    private static JsonNode schemaEndingWith(JsonNode doc, String suffix) {
        JsonNode schemas = doc.path("components").path("schemas");
        List<String> hits = new ArrayList<>();
        for (Iterator<String> it = schemas.propertyNames().iterator(); it.hasNext(); ) {
            String name = it.next();
            if (name.endsWith(suffix)) {
                hits.add(name);
            }
        }
        assertThat(hits).as("component schemas ending with " + suffix).hasSize(1);
        return schemas.get(hits.get(0));
    }

    private static String refName(JsonNode node) {
        String ref = node.path("$ref").asString();
        return ref.substring(ref.lastIndexOf('/') + 1);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    private static List<String> tagNames(JsonNode doc) {
        List<String> out = new ArrayList<>();
        doc.path("tags").forEach(t -> out.add(t.path("name").asString()));
        return out;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }

    @Test
    void fixtureHeaderTagsAndPaths() throws IOException {
        JsonNode doc = fixtureDoc();
        assertThat(doc.path("openapi").asString()).isEqualTo("3.1.0");
        assertThat(tagNames(doc)).containsExactly("Fixture", "PublicFixture");
        assertThat(fieldNames(doc.path("paths")))
                .containsExactly(
                        "/api/fixture",
                        "/api/fixture/odd/{rest}",
                        "/api/fixture/{id}",
                        "/api/fixture/{id}/events",
                        "/api/signup");
        assertThat(fieldNames(doc.path("paths").path("/api/fixture/{id}")))
                .containsExactly("get", "delete");
    }

    @Test
    void fixtureCreateOperation() throws IOException {
        JsonNode doc = fixtureDoc();
        JsonNode op = doc.path("paths").path("/api/fixture").path("post");
        assertThat(op.path("operationId").asString()).isEqualTo("FixtureController_create");
        assertThat(texts(op.path("tags"))).containsExactly("Fixture");
        assertThat(op.path("requestBody").path("required").asBoolean()).isTrue();
        JsonNode bodySchema =
                op.path("requestBody").path("content").path("application/json").path("schema");
        assertThat(refName(bodySchema)).endsWith("CreateWidget");
        // 409/422: Idempotency-Key applies to authenticated JSON POSTs (IdempotencyKeyFilter).
        assertThat(fieldNames(op.path("responses")))
                .containsExactly("201", "400", "401", "403", "409", "422", "500");
        assertThat(
                        refName(
                                op.path("responses")
                                        .path("201")
                                        .path("content")
                                        .path("application/json")
                                        .path("schema")))
                .endsWith("Widget");
        assertThat(op.path("security").get(0).has(OpenApiSecurity.BEARER)).isTrue();
    }

    @Test
    void fixtureGetOperationParameters() throws IOException {
        JsonNode op = fixtureDoc().path("paths").path("/api/fixture/{id}").path("get");
        JsonNode params = op.path("parameters");
        assertThat(params.size()).isEqualTo(3);
        assertThat(params.get(0).path("name").asString()).isEqualTo("id");
        assertThat(params.get(0).path("in").asString()).isEqualTo("path");
        assertThat(params.get(0).path("required").asBoolean()).isTrue();
        assertThat(params.get(0).path("schema").path("format").asString()).isEqualTo("uuid");
        assertThat(params.get(1).path("name").asString()).isEqualTo("limit");
        assertThat(params.get(1).path("in").asString()).isEqualTo("query");
        assertThat(params.get(1).path("required").asBoolean()).isFalse();
        assertThat(params.get(1).path("schema").path("type").asString()).isEqualTo("integer");
        assertThat(params.get(2).path("name").asString()).isEqualTo("X-Trace");
        assertThat(params.get(2).path("in").asString()).isEqualTo("header");
        assertThat(params.get(2).path("required").asBoolean()).isTrue();
        assertThat(fieldNames(op.path("responses")))
                .containsExactly("200", "400", "401", "403", "404", "500");
    }

    @Test
    void fixtureStreamingDeleteAndRegexPath() throws IOException {
        JsonNode doc = fixtureDoc();
        JsonNode events = doc.path("paths").path("/api/fixture/{id}/events").path("get");
        assertThat(events.path("x-sse").asBoolean()).isTrue();
        assertThat(events.path("responses").path("200").path("content").has("text/event-stream"))
                .isTrue();

        JsonNode delete = doc.path("paths").path("/api/fixture/{id}").path("delete");
        assertThat(delete.path("responses").has("204")).isTrue();
        assertThat(delete.path("responses").path("204").has("content")).isFalse();

        JsonNode odd = doc.path("paths").path("/api/fixture/odd/{rest}").path("get");
        assertThat(odd.path("parameters").get(0).path("name").asString()).isEqualTo("rest");
        JsonNode oddSchema =
                odd.path("responses")
                        .path("200")
                        .path("content")
                        .path("application/json")
                        .path("schema");
        assertThat(oddSchema.path("type").asString()).isEqualTo("object");
        assertThat(oddSchema.path("additionalProperties").asBoolean()).isTrue();
    }

    @Test
    void fixturePublicRouteHasNoSecurityAndNoAuthErrors() throws IOException {
        JsonNode op = fixtureDoc().path("paths").path("/api/signup").path("post");
        assertThat(op.path("security").isArray()).isTrue();
        assertThat(op.path("security").size()).isZero();
        assertThat(op.path("responses").has("401")).isFalse();
        assertThat(op.path("responses").has("403")).isFalse();
        assertThat(op.path("description").asString()).contains("Public route");
    }

    @Test
    void fixtureSchemas() throws IOException {
        JsonNode doc = fixtureDoc();

        JsonNode widget = schemaEndingWith(doc, "_Widget");
        assertThat(fieldNames(widget.path("properties")))
                .containsExactly("id", "name", "count", "tags", "kind", "parent");
        assertThat(texts(widget.path("required"))).containsExactly("name", "count");
        assertThat(widget.path("properties").path("id").path("format").asString())
                .isEqualTo("uuid");
        assertThat(widget.path("properties").path("name").path("minLength").asInt()).isEqualTo(1);
        assertThat(widget.path("properties").path("name").path("maxLength").asInt()).isEqualTo(40);
        assertThat(widget.path("properties").path("count").path("default").asInt()).isZero();
        assertThat(widget.path("properties").path("tags").path("type").asString())
                .isEqualTo("array");
        assertThat(widget.path("properties").path("tags").path("items").path("type").asString())
                .isEqualTo("string");

        String kindName = refName(widget.path("properties").path("kind"));
        assertThat(kindName).endsWith("Kind");
        assertThat(texts(doc.path("components").path("schemas").path(kindName).path("enum")))
                .containsExactly("SMALL", "LARGE");

        // cycle guard: Widget.parent refers back to Widget itself
        String widgetName =
                refName(
                        doc.path("paths")
                                .path("/api/fixture")
                                .path("post")
                                .path("responses")
                                .path("201")
                                .path("content")
                                .path("application/json")
                                .path("schema"));
        assertThat(refName(widget.path("properties").path("parent"))).isEqualTo(widgetName);

        JsonNode create = schemaEndingWith(doc, "_CreateWidget");
        assertThat(fieldNames(create.path("properties")))
                .containsExactly("name", "quantity", "note_text");
        assertThat(texts(create.path("required"))).containsExactly("name");
        assertThat(create.path("properties").path("quantity").path("minimum").asInt()).isEqualTo(1);
    }

    @Test
    void fixtureRecordsSkippedHandlersInInfo() throws IOException {
        JsonNode skipped = fixtureDoc().path("info").path("x-skipped");
        List<String> all = texts(skipped);
        assertThat(all)
                .anyMatch(
                        s -> s.contains("OddController#anyMethod") && s.contains("no HTTP method"));
        assertThat(all)
                .anyMatch(s -> s.contains("OddController#wild") && s.contains("unsupported path"));
    }
}
