package com.agenticai.core.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.common.security.PublicEndpointRateLimitFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The cross-cutting API conventions the generated document must state over the REAL controller
 * set: RFC 9457 errors, the tenant API-key alternative, 429 on throttled routes, and the paging
 * and correlation headers.
 */
class OpenApiConventionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode doc;

    private static synchronized JsonNode doc() throws IOException {
        if (doc == null) {
            doc = MAPPER.readTree(new OpenApiSpecGenerator("9.9.9").generateJson());
        }
        return doc;
    }

    private static JsonNode op(String path, String verb) throws IOException {
        JsonNode op = doc().path("paths").path(path).path(verb);
        assertThat(op.isMissingNode()).as(verb + " " + path).isFalse();
        return op;
    }

    private static List<String> schemes(JsonNode op) {
        List<String> out = new ArrayList<>();
        for (JsonNode alt : op.path("security")) {
            alt.fieldNames().forEachRemaining(out::add);
        }
        return out;
    }

    @Test
    void errorSchemaIsProblemDetailsWithLegacyMembers() throws IOException {
        JsonNode error = doc().path("components").path("schemas").path("Error");
        assertThat(error.path("properties").has("type")).isTrue();
        assertThat(error.path("properties").has("title")).isTrue();
        assertThat(error.path("properties").has("detail")).isTrue();
        assertThat(error.path("properties").has("instance")).isTrue();
        assertThat(error.path("properties").has("error")).isTrue();
        assertThat(error.path("properties").has("requestId")).isTrue();
        JsonNode notFound = doc().path("components").path("responses").path("NotFound");
        assertThat(notFound.path("content").has("application/problem+json")).isTrue();
        assertThat(notFound.path("content").has("application/json")).isTrue();
        assertThat(notFound.path("headers").path("X-Request-Id").path("$ref").asText())
                .isEqualTo("#/components/headers/X-Request-Id");
    }

    @Test
    void authenticatedApiRoutesAcceptTenantApiKey() throws IOException {
        assertThat(doc().path("components").path("securitySchemes").has("tenantApiKey")).isTrue();
        assertThat(schemes(op("/api/agents", "get")))
                .containsExactly(OpenApiSecurity.BEARER, OpenApiSecurity.TENANT_API_KEY);
    }

    @Test
    void throttledPublicRoutesDocument429WithRetryAfter() throws IOException {
        assertThat(op("/api/public/status", "get").path("responses").path("429").path("$ref").asText())
                .isEqualTo("#/components/responses/TooManyRequests");
        assertThat(op("/api/agents", "get").path("responses").has("429")).isFalse();
        assertThat(
                        doc().path("components")
                                .path("responses")
                                .path("TooManyRequests")
                                .path("headers")
                                .has("Retry-After"))
                .isTrue();
        // Every 429 in the document is a route the filter really throttles, and vice versa.
        int documented = 0;
        for (Iterator<Map.Entry<String, JsonNode>> paths = doc().path("paths").fields();
                paths.hasNext(); ) {
            Map.Entry<String, JsonNode> p = paths.next();
            for (Iterator<Map.Entry<String, JsonNode>> ops = p.getValue().fields();
                    ops.hasNext(); ) {
                Map.Entry<String, JsonNode> o = ops.next();
                boolean has429 = o.getValue().path("responses").has("429");
                assertThat(has429)
                        .as(o.getKey() + " " + p.getKey())
                        .isEqualTo(PublicEndpointRateLimitFilter.throttles(o.getKey(), p.getKey()));
                documented += has429 ? 1 : 0;
            }
        }
        assertThat(documented).isGreaterThan(3);
    }

    @Test
    void pagedListEndpointsDocumentPagingHeaders() throws IOException {
        JsonNode headers = op("/api/agents", "get").path("responses").path("200").path("headers");
        assertThat(headers.path("X-Total-Count").path("$ref").asText())
                .isEqualTo("#/components/headers/X-Total-Count");
        assertThat(headers.has("X-Has-More")).isTrue();
        assertThat(
                        op("/api/agents/{agentId}", "get")
                                .path("responses")
                                .path("200")
                                .path("headers")
                                .has("X-Total-Count"))
                .isFalse();
    }

    @Test
    void webhooksDescribeTheSignedWorkflowEventDelivery() throws IOException {
        JsonNode post = doc().path("webhooks").path("workflowEvent").path("post");
        assertThat(post.path("requestBody").path("content").path("application/json").path("schema").path("$ref").asText())
                .endsWith("/WorkflowEvent");
        List<String> headers = new ArrayList<>();
        post.path("parameters").forEach(p -> headers.add(p.path("name").asText()));
        assertThat(headers).contains("X-VegaDuta-Signature", "X-VegaDuta-Delivery", "X-VegaDuta-Event");
    }

    @Test
    void permissionsComeFromTheSecurityRuleSnapshot() throws IOException {
        JsonNode audit = op("/api/audit-log", "get");
        assertThat(audit.path("x-permissions").path("anyOf").get(0).asText()).isEqualTo("PERM_VIEW_AUDIT_LOG");
        assertThat(audit.path("x-roles").get(0).asText()).isEqualTo("tenant-admin");
        assertThat(audit.path("description").asText()).contains("Requires PERM_VIEW_AUDIT_LOG");
        assertThat(op("/api/agents", "get").has("x-permissions")).isFalse();
    }

    @Test
    void writesDocumentIdempotencyKeyAndConditionalRequests() throws IOException {
        JsonNode create = op("/api/agents", "post");
        assertThat(create.path("parameters").toString()).contains("#/components/parameters/IdempotencyKey");
        assertThat(create.path("responses").has("409")).isTrue();
        assertThat(create.path("responses").has("422")).isTrue();
        assertThat(op("/api/workflows/{workflowId}/run", "post").path("parameters").toString())
                .doesNotContain("#/components/parameters/IdempotencyKey");

        JsonNode put = op("/api/workflows/{workflowId}", "put");
        assertThat(put.path("parameters").toString()).contains("#/components/parameters/IfMatch");
        assertThat(put.path("responses").has("412")).isTrue();
        assertThat(op("/api/workflows/{workflowId}", "get").path("responses").path("200").path("headers").has("ETag"))
                .isTrue();
    }

    @Test
    void keysetFeedsDocumentTheNextCursor() throws IOException {
        for (String path : List.of("/api/audit-log", "/api/workflows/{workflowId}/runs")) {
            JsonNode get = op(path, "get");
            assertThat(get.path("parameters").toString()).as(path).contains("\"cursor\"");
            assertThat(get.path("responses").path("200").path("headers").has("X-Next-Cursor")).as(path).isTrue();
        }
    }

    @Test
    void versionedCollectionsMirrorEveryResourceVersionSource() {
        List<String> actual =
                List.of(
                        new com.agenticai.core.agents.core.AgentResourceVersions(null).collectionPath(),
                        new com.agenticai.core.common.apicontract.WorkflowResourceVersions(null).collectionPath());
        assertThat(OpenApiOperationConventions.VERSIONED_COLLECTIONS).containsExactlyInAnyOrderElementsOf(actual);
        // A new ResourceVersionSource must be listed here and in VERSIONED_COLLECTIONS.
        org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider scanner =
                new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(
                new org.springframework.core.type.filter.AssignableTypeFilter(
                        com.agenticai.core.security.http.ResourceVersionSource.class));
        assertThat(scanner.findCandidateComponents("com.agenticai.core")).hasSize(actual.size());
    }
}
