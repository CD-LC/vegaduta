package com.agenticai.core.agents.a2a;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Renders A2A protocol objects (Task, Message, the two streaming events) as plain {@code Map}s in
 * whichever dialect ({@link A2aVersion}) the request asked for, and parses the inbound {@code
 * Message} both dialects send. Maps rather than typed DTOs on purpose: the two dialects differ in
 * field names AND envelope nesting, and a single set of records with {@code @JsonInclude}
 * gymnastics would obscure exactly the differences a reader of this class most needs to see side by
 * side.
 *
 * <p>Wire differences, in one place:
 *
 * <table>
 *   <tr><th></th><th>0.3</th><th>1.0</th></tr>
 *   <tr><td>discriminator</td><td>{@code kind: "task"|"message"|"status-update"|"artifact-update"}, parts {@code kind:"text"}</td><td>none (oneof envelope instead)</td></tr>
 *   <tr><td>state</td><td>{@code "completed"}</td><td>{@code "TASK_STATE_COMPLETED"}</td></tr>
 *   <tr><td>role</td><td>{@code "user"|"agent"}</td><td>{@code "ROLE_USER"|"ROLE_AGENT"}</td></tr>
 *   <tr><td>SendMessage result</td><td>Task</td><td>{@code {"task": Task}}</td></tr>
 *   <tr><td>stream result</td><td>Task | status-update | artifact-update</td><td>{@code {"task"|"statusUpdate"|"artifactUpdate": ...}}</td></tr>
 * </table>
 *
 * Only text parts exist on either side of this server today - see {@link #extractText}.
 */
final class A2aWire {

    private A2aWire() {}

    // ---------------------------------------------------------------- Task

    static Map<String, Object> task(A2aTask task, A2aVersion version, Integer historyLength) {
        A2aTask.Snapshot snap = task.snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        if (version == A2aVersion.V0_3) {
            out.put("kind", "task");
        }
        out.put("id", task.id());
        out.put("contextId", task.contextId());
        out.put("status", status(snap, version));

        List<Object> artifacts = new ArrayList<>();
        // An artifact exists once there is something in it, OR the turn completed
        // with an (unusual but legal) empty reply - a COMPLETED task with no
        // artifact at all reads to a client as "the agent produced nothing".
        if (!snap.reply().isEmpty() || snap.state() == A2aTaskState.COMPLETED) {
            artifacts.add(artifact(task.artifactId(), snap.reply(), version));
        }
        out.put("artifacts", artifacts);

        List<Object> history = new ArrayList<>();
        history.add(
                message(
                        task.userMessageId(),
                        "user",
                        task.userText(),
                        task.contextId(),
                        task.id(),
                        version));
        if (snap.state() == A2aTaskState.COMPLETED) {
            history.add(
                    message(
                            task.id() + "-reply",
                            "agent",
                            snap.reply(),
                            task.contextId(),
                            task.id(),
                            version));
        }
        if (historyLength != null && historyLength >= 0 && history.size() > historyLength) {
            // Spec: "the most recent N messages".
            history =
                    new ArrayList<>(
                            history.subList(history.size() - historyLength, history.size()));
        }
        out.put("history", history);
        out.put("metadata", Map.of("agentId", task.agentId().toString()));
        return out;
    }

    static Map<String, Object> status(A2aTask.Snapshot snap, A2aVersion version) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", snap.state().wire(version));
        status.put("timestamp", snap.timestamp().toString());
        if (snap.state() == A2aTaskState.FAILED && snap.failureMessage() != null) {
            // The failure reason travels as a status message from the agent, the
            // one place the spec gives a human-readable "why" on a failed task.
            status.put(
                    "message", message(null, "agent", snap.failureMessage(), null, null, version));
        }
        return status;
    }

    static Map<String, Object> artifact(String artifactId, String text, A2aVersion version) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("artifactId", artifactId);
        artifact.put("name", "response");
        artifact.put("parts", List.of(textPart(text, version)));
        return artifact;
    }

    static Map<String, Object> message(
            String messageId,
            String role,
            String text,
            String contextId,
            String taskId,
            A2aVersion version) {
        Map<String, Object> message = new LinkedHashMap<>();
        if (version == A2aVersion.V0_3) {
            message.put("kind", "message");
        }
        if (messageId != null) {
            message.put("messageId", messageId);
        }
        message.put(
                "role",
                version == A2aVersion.V1_0
                        ? "ROLE_" + role.toUpperCase(java.util.Locale.ROOT)
                        : role);
        message.put("parts", List.of(textPart(text, version)));
        if (contextId != null) {
            message.put("contextId", contextId);
        }
        if (taskId != null) {
            message.put("taskId", taskId);
        }
        return message;
    }

    static Map<String, Object> textPart(String text, A2aVersion version) {
        Map<String, Object> part = new LinkedHashMap<>();
        if (version == A2aVersion.V0_3) {
            part.put("kind", "text");
        }
        part.put("text", text == null ? "" : text);
        return part;
    }

    // ---------------------------------------------------------- Streaming

    /**
     * @param snap the status to render - usually {@link A2aTask#snapshot()}, but the controller
     *     passes a synthetic WORKING snapshot for the second frame: a turn whose token Flux
     *     completed synchronously is already COMPLETED by then, and the wire order still owes the
     *     client a WORKING frame before any artifact chunk.
     */
    static Map<String, Object> statusUpdate(
            A2aTask task, A2aTask.Snapshot snap, A2aVersion version, boolean isFinal) {
        Map<String, Object> event = new LinkedHashMap<>();
        if (version == A2aVersion.V0_3) {
            event.put("kind", "status-update");
        }
        event.put("taskId", task.id());
        event.put("contextId", task.contextId());
        event.put("status", status(snap, version));
        event.put("final", isFinal);
        return event;
    }

    static Map<String, Object> artifactUpdate(
            A2aTask task, String delta, boolean append, boolean lastChunk, A2aVersion version) {
        Map<String, Object> event = new LinkedHashMap<>();
        if (version == A2aVersion.V0_3) {
            event.put("kind", "artifact-update");
        }
        event.put("taskId", task.id());
        event.put("contextId", task.contextId());
        event.put("artifact", artifact(task.artifactId(), delta, version));
        event.put("append", append);
        event.put("lastChunk", lastChunk);
        return event;
    }

    /**
     * Wraps a streamed payload in the 1.0 oneof envelope; 0.3 sends the object bare (its {@code
     * kind} discriminates).
     */
    static Object streamResult(String v1Member, Map<String, Object> payload, A2aVersion version) {
        return version == A2aVersion.V1_0 ? Map.of(v1Member, payload) : payload;
    }

    /** Same envelope rule for the unary {@code SendMessage} result. */
    static Object sendMessageResult(Map<String, Object> task, A2aVersion version) {
        return version == A2aVersion.V1_0 ? Map.of("task", task) : task;
    }

    // ------------------------------------------------------------ Parsing

    /**
     * Concatenates the text of every part of an inbound message. Both dialects are accepted ({@code
     * {"kind":"text","text":..}} and the bare 1.0 {@code {"text":..}}); a file or data part -
     * {@code kind:"file"|"data"}, or a {@code file}/{@code data}/{@code raw}/{@code url} member -
     * is refused with {@code -32005 ContentTypeNotSupported}, which is the spec's own signal for
     * "this modality isn't accepted", rather than silently dropping an attachment the caller
     * believed the agent saw.
     */
    static String extractText(JsonNode message) {
        JsonNode parts = message.get("parts");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            throw A2aError.invalidParams("message.parts must be a non-empty array");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            if (!part.isObject()) {
                throw A2aError.invalidParams("message.parts entries must be objects");
            }
            String kind = part.path("kind").asString(null);
            boolean isText = "text".equals(kind) || (kind == null && part.hasNonNull("text"));
            if (isText && part.hasNonNull("text")) {
                text.append(part.get("text").asString());
                continue;
            }
            if ("file".equals(kind)
                    || "data".equals(kind)
                    || part.has("file")
                    || part.has("data")
                    || part.has("raw")
                    || part.has("url")) {
                throw A2aError.contentTypeNotSupported(
                        "only text parts are accepted (defaultInputModes: text/plain); file/data parts are not supported");
            }
            throw A2aError.invalidParams("unrecognized message part: " + part);
        }
        if (text.toString().isBlank()) {
            throw A2aError.invalidParams("message must contain non-empty text");
        }
        return text.toString();
    }
}
