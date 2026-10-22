package com.agenticai.core.agents.demo.studio;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.FluxSink;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * The JSON side of a studio workflow run: each event as one {@code data:} line on the run's sink,
 * the step trace stored on the run row, and the step id stamped onto forwarded tool-trace events.
 * Not a bean; {@link DemoStudioWorkflowRunner} owns one. Split out of the runner to keep it under
 * the file-size cap.
 */
final class DemoStudioRunEvents {

    private static final Logger log = LoggerFactory.getLogger(DemoStudioWorkflowRunner.class);

    private final ObjectMapper objectMapper;

    DemoStudioRunEvents(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void finishStep(
            FluxSink<String> sink,
            Map<String, Object> entry,
            long stepStart,
            List<Map<String, Object>> trace) {
        long ms = System.currentTimeMillis() - stepStart;
        entry.put("ms", ms);
        trace.add(entry);
        emit(
                sink,
                Map.of(
                        "type",
                        "step_finished",
                        "stepId",
                        entry.get("stepId"),
                        "status",
                        entry.get("status"),
                        "ms",
                        ms,
                        "summary",
                        entry.getOrDefault("summary", "")));
    }

    String withStepId(String json, String stepId) {
        try {
            Map<String, Object> event =
                    objectMapper.readValue(
                            json,
                            new tools.jackson.core.type.TypeReference<Map<String, Object>>() {});
            event.put("stepId", stepId);
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException e) {
            return json;
        }
    }

    void emit(FluxSink<String> sink, Map<String, ?> event) {
        try {
            sink.next(objectMapper.writeValueAsString(event));
        } catch (JacksonException e) {
            log.warn("Unserialisable studio event {}", event.get("type"), e);
        }
    }

    String toJson(List<Map<String, Object>> trace) {
        try {
            return objectMapper.writeValueAsString(trace);
        } catch (JacksonException e) {
            return "[]";
        }
    }
}
