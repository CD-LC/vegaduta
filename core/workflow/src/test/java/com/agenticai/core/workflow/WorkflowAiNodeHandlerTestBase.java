package com.agenticai.core.workflow;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.web.HttpRequestExecutor;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.workflow.dto.WorkflowNodeDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Shared fixture of the {@link WorkflowAiNodeHandler} tests: a scripted fake model and node/config
 * builders.
 */
abstract class WorkflowAiNodeHandlerTestBase {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Pops scripted replies (a String, or a RuntimeException to throw) in order and records every
     * request.
     */
    static final class FakeModel implements WorkflowAiModel {
        final Deque<Object> script = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();

        FakeModel reply(String reply) {
            script.add(reply);
            return this;
        }

        FakeModel fail(RuntimeException e) {
            script.add(e);
            return this;
        }

        @Override
        public String complete(Request request) {
            requests.add(request);
            Object next = script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            return next == null ? "" : (String) next;
        }
    }

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    static WorkflowAiNodeHandler handler(
            WorkflowAiModel model,
            SidecarClient sidecar,
            FileStorageService storage,
            HttpRequestExecutor http,
            boolean enabled) {
        return new WorkflowAiNodeHandler(
                providerOf(model),
                providerOf(sidecar),
                providerOf(storage),
                providerOf(http),
                MAPPER,
                enabled,
                12_000,
                0.5,
                1_048_576,
                60_000,
                50,
                2_000);
    }

    static WorkflowAiNodeHandler handler(FakeModel model) {
        return handler(model, null, null, null, true);
    }

    static WorkflowNodeDto node(String type, Map<String, Object> config) {
        return new WorkflowNodeDto("n1", type, Map.of(), Map.of("label", "Node", "config", config));
    }

    static Map<String, Object> cls(String handle, String description) {
        return Map.of("handle", handle, "label", handle, "description", description);
    }

    static Map<String, Object> classifierConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put(
                "classes",
                List.of(
                        cls("billing", "invoices and payments"),
                        cls("support", "technical problems"),
                        cls("other", "anything else")));
        config.put("defaultClass", "other");
        return config;
    }

    static Map<String, Object> param(String name, String type, boolean required) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", name);
        p.put("type", type);
        p.put("description", "the " + name);
        p.put("required", required);
        return p;
    }

    static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }
}
