package com.agenticai.core.agents.tools.apiguide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class ApiGuideToolCallbackTest {

    private static final String SPEC =
            """
            {"openapi":"3.1.0","paths":{
              "/api/agents":{
                "get":{"tags":["Agents"],"summary":"List agents","operationId":"AgentController_list",
                       "parameters":[{"name":"page","in":"query","schema":{"type":"integer"}}],
                       "responses":{"200":{"content":{"application/json":{"schema":{"type":"array","items":{"$ref":"#/components/schemas/Agent"}}}}}}},
                "post":{"tags":["Agents"],"summary":"Create agent","operationId":"AgentController_create",
                        "requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Agent"}}}},
                        "responses":{"201":{"description":"ok"}}}},
              "/api/knowledge/{id}":{"delete":{"tags":["Knowledge"],"summary":"Delete knowledge base","responses":{"204":{"description":"ok"}}}}
            },"components":{"schemas":{"Agent":{"type":"object","required":["name"],"properties":{"name":{"type":"string"},"id":{"type":"string"}}}}}}
            """;

    private final ApiGuideToolCallback tool = new ApiGuideToolCallback(() -> SPEC);

    private JsonNode call(String json) throws Exception {
        return JsonMappers.plain().readTree(tool.call(json));
    }

    @Test
    void searchRanksByPathAndSummary() throws Exception {
        JsonNode r = call("{\"action\":\"search\",\"query\":\"list agents\"}");
        assertEquals("/api/agents", r.at("/results/0/path").asText());
        assertEquals("GET", r.at("/results/0/method").asText());
    }

    @Test
    void searchCanFilterByMethod() throws Exception {
        JsonNode r = call("{\"action\":\"search\",\"query\":\"agents\",\"method\":\"POST\"}");
        assertEquals(1, r.path("results").size());
        assertEquals("POST", r.at("/results/0/method").asText());
    }

    @Test
    void describeResolvesSchemasAndOffersTheLiveBlock() throws Exception {
        JsonNode r = call("{\"action\":\"describe\",\"method\":\"POST\",\"path\":\"/api/agents\"}");
        assertEquals("string", r.at("/body/schema/properties/name/type").asText());
        assertEquals("name", r.at("/body/schema/required/0").asText());
        assertTrue(r.path("showItLive").asText().contains("\"type\":\"apiCall\""));
        JsonNode get = call("{\"action\":\"describe\",\"method\":\"GET\",\"path\":\"/api/agents\"}");
        assertEquals("array", get.at("/response/type").asText());
        assertEquals("page", get.at("/parameters/0/name").asText());
    }

    @Test
    void describeOfAnUnknownEndpointSaysSo() throws Exception {
        assertTrue(call("{\"action\":\"describe\",\"method\":\"GET\",\"path\":\"/api/nope\"}").has("error"));
    }
}
