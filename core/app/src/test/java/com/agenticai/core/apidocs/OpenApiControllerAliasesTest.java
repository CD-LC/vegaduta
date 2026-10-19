package com.agenticai.core.apidocs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/**
 * A controller split for size must keep its published tag and operationIds, because the SDKs group
 * methods by tag and name them by operationId. See {@link OpenApiControllerAliases}.
 */
class OpenApiControllerAliasesTest {

    @Test
    void everyAliasedClassIsAScannedRestController() throws ClassNotFoundException {
        List<String> stale = new ArrayList<>();
        for (String name : OpenApiControllerAliases.aliasedClassNames()) {
            Class<?> c = Class.forName(name);
            if (!c.isAnnotationPresent(RestController.class)) {
                stale.add(name);
            }
        }
        assertThat(stale).as("aliased classes that are not @RestController").isEmpty();
    }

    @Test
    void unaliasedControllerKeepsItsSimpleName() {
        assertThat(OpenApiControllerAliases.publishedName(String.class)).isEqualTo("String");
    }

    /** The operations moved out of WorkflowController keep the ids and tag the SDKs were built on. */
    @Test
    void splitWorkflowOperationsKeepTheirPublishedIdsAndTag() throws IOException {
        JsonNode paths =
                new ObjectMapper()
                        .readTree(new OpenApiSpecGenerator("9.9.9").generateJson())
                        .path("paths");
        String run = "/api/workflows/{workflowId}/runs/{runId}";
        String node = "/api/workflows/{workflowId}/nodes/{nodeId}";
        assertOperation(paths, run, "get", "WorkflowController_getRun");
        assertOperation(paths, run + "/cancel", "post", "WorkflowController_cancelRun");
        assertOperation(paths, run + "/llm-requests", "get", "WorkflowController_runLlmRequests");
        assertOperation(paths, node + "/webhook", "get", "WorkflowController_getWebhook");
        assertOperation(
                paths, node + "/trigger-channel", "get", "WorkflowController_getTriggerChannel");
    }

    private static void assertOperation(JsonNode paths, String path, String verb, String id) {
        JsonNode op = paths.path(path).path(verb);
        assertThat(op.isMissingNode()).as(verb + " " + path).isFalse();
        assertThat(op.path("operationId").asText()).as(verb + " " + path).isEqualTo(id);
        assertThat(op.path("tags").get(0).asText()).as(verb + " " + path).isEqualTo("Workflow");
    }
}
