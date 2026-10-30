package com.agenticai.core.agents.tools.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.agenticai.core.agents.tools.openapi.OpenApiSpecParser.ParsedOperation;
import org.junit.jupiter.api.Test;

/**
 * The persisted form of one parsed operation: {@link OpenApiTool#applyOperation} on create and
 * refresh.
 */
class OpenApiToolTest {

    private static final String SPEC =
            "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
                    + "\"paths\":{\"/pets/{id}\":{\"get\":{\"operationId\":\"getPet\","
                    + "\"summary\":\"Get a pet\",\"deprecated\":true,"
                    + "\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true,"
                    + "\"schema\":{\"type\":\"string\"}}]},"
                    + "\"post\":{\"operationId\":\"updatePet\","
                    + "\"requestBody\":{\"required\":true,\"content\":{\"application/json\":{"
                    + "\"schema\":{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}}}}}}}}";

    private static ParsedOperation op(int index) {
        return new OpenApiSpecParser().parse(SPEC, "p").operations().get(index);
    }

    @Test
    void constructionMaterialisesEveryFieldTheExecutorNeeds() {
        OpenApiToolSource source = mock(OpenApiToolSource.class);
        ParsedOperation parsed = op(0);
        OpenApiTool tool = new OpenApiTool(source, "p_get_pet", parsed, true);

        assertSame(source, tool.getSource());
        assertEquals("p_get_pet", tool.getToolName());
        assertEquals("GET /pets/{id}", tool.getOperationKey());
        assertEquals("getPet", tool.getOperationId());
        assertEquals("GET", tool.getHttpMethod());
        assertEquals("/pets/{id}", tool.getPathTemplate());
        assertEquals("Get a pet", tool.getSummary());
        assertNull(tool.getDescription());
        assertEquals(parsed.parametersJson(), tool.getParametersJson());
        assertEquals(OpenApiBodyMode.NONE, tool.getBodyMode());
        assertNull(tool.getBodyContentType());
        assertEquals(parsed.inputSchemaJson(), tool.getInputSchemaJson());
        assertTrue(tool.isEnabled());
        assertTrue(tool.isDeprecated());
        assertNotNull(tool.getCreatedAt());
        assertNotNull(tool.getUpdatedAt());
    }

    @Test
    void applyOperationRefreshesSpecFieldsButKeepsNameAndEnabled() throws Exception {
        OpenApiTool tool =
                new OpenApiTool(mock(OpenApiToolSource.class), "p_update_pet", op(1), false);
        assertEquals(OpenApiBodyMode.FLATTENED, tool.getBodyMode());
        assertEquals("application/json", tool.getBodyContentType());
        assertFalse(tool.isEnabled());

        ParsedOperation refreshed = op(0);
        tool.applyOperation(refreshed);

        assertEquals("GET /pets/{id}", tool.getOperationKey());
        assertEquals("GET", tool.getHttpMethod());
        assertEquals("p_update_pet", tool.getToolName(), "the tool name never changes on refresh");
        assertFalse(tool.isEnabled(), "the enabled flag is preserved");
    }

    @Test
    void setEnabledTogglesAndBumpsUpdatedAt() throws Exception {
        OpenApiTool tool = new OpenApiTool(mock(OpenApiToolSource.class), "t", op(0), true);
        java.time.Instant before = tool.getUpdatedAt();
        tool.setEnabled(false);
        assertFalse(tool.isEnabled());
        assertTrue(!tool.getUpdatedAt().isBefore(before));
    }
}
