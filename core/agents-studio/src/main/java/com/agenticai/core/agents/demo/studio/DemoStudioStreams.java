package com.agenticai.core.agents.demo.studio;

import com.agenticai.core.agents.demo.studio.DemoStudioController.RunView;
import com.agenticai.core.agents.demo.studio.DemoStudioService.StudioException;
import com.agenticai.core.agents.tools.AgentStepEventEmitter;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * The wire half of {@link DemoStudioController}'s streams: turns mission chunks and workflow run
 * events into the {@code text/event-stream} JSON objects the /try page reads, and the stored run
 * rows into {@link RunView}s. Not a bean; the controller owns one. Split out of the controller to
 * keep it under the file-size cap. The timeout timers stay in the controller.
 */
final class DemoStudioStreams {

    private static final Logger log = LoggerFactory.getLogger(DemoStudioController.class);

    private final ObjectMapper objectMapper;
    private final DemoStudioFairUse fairUse;

    DemoStudioStreams(ObjectMapper objectMapper, DemoStudioFairUse fairUse) {
        this.objectMapper = objectMapper;
        this.fairUse = fairUse;
    }

    /**
     * Mission chunks as wire events: a step event passes through (a guardrail refusing this
     * sandbox's request is a strike), reply text is wrapped as a {@code token} event, and a failure
     * becomes one {@code error} event instead of an HTTP error.
     */
    Flux<String> missionEvents(
            Flux<String> body, DemoSession session, UUID agentId, AtomicBoolean anyToken) {
        return body.map(
                        chunk -> {
                            if (chunk.startsWith(AgentStepEventEmitter.STEP_EVENT_MARKER)) {
                                String event =
                                        chunk.substring(
                                                AgentStepEventEmitter.STEP_EVENT_MARKER.length());
                                // A guardrail refusing this sandbox's request is a strike;
                                // enough of them cools the sandbox down.
                                if (event.contains("\"type\":\"guardrail\"")) {
                                    fairUse.strike(session, "guardrail");
                                }
                                return event;
                            }
                            anyToken.set(true);
                            return json(Map.of("type", "token", "text", chunk));
                        })
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "Studio mission failed for sandbox {} agent {} (tokens"
                                            + " already sent: {})",
                                    session.getId(),
                                    agentId,
                                    anyToken.get(),
                                    e);
                            String message =
                                    e instanceof StudioException se
                                            ? se.getMessage()
                                            : "The agent couldn't finish this mission - the"
                                                    + " model or a tool was unavailable. Try"
                                                    + " again in a moment.";
                            return Flux.just(json(Map.of("type", "error", "message", message)));
                        });
    }

    /** The budget event, the mission's events, then the time-cap notice when the cap fired. */
    Flux<String> missionStream(
            String chatSessionId,
            int used,
            int limit,
            Flux<String> body,
            AtomicBoolean timedOut,
            int timeoutSeconds) {
        return Flux.concat(
                Flux.just(
                        json(
                                Map.of(
                                        "type",
                                        "budget",
                                        "used",
                                        used,
                                        "limit",
                                        limit,
                                        "sessionId",
                                        chatSessionId))),
                body,
                Flux.defer(
                        () ->
                                timedOut.get()
                                        ? Flux.just(
                                                json(
                                                        Map.of(
                                                                "type",
                                                                "error",
                                                                "message",
                                                                "This mission hit the"
                                                                        + " studio's "
                                                                        + timeoutSeconds
                                                                        + "-second time cap"
                                                                        + " and was"
                                                                        + " stopped. Ask"
                                                                        + " for a smaller"
                                                                        + " step, or chain"
                                                                        + " agents in a"
                                                                        + " workflow.")))
                                        : Flux.<String>empty()));
    }

    /** One {@code error} event when the workflow's whole-run cap fired, else nothing. */
    Flux<String> workflowCapNotice(AtomicBoolean capped, Duration timeout) {
        return Flux.defer(
                () ->
                        capped.get()
                                ? Flux.just(
                                        json(
                                                Map.of(
                                                        "type",
                                                        "error",
                                                        "message",
                                                        "This run hit the"
                                                                + " studio's "
                                                                + timeout.toMinutes()
                                                                + "-minute"
                                                                + " time"
                                                                + " cap and"
                                                                + " was stopped.")))
                                : Flux.<String>empty());
    }

    /** A workflow stream that failed, as one {@code error} event. */
    Flux<String> workflowFailure(
            Throwable e, DemoSession session, UUID workflowId, Duration timeout) {
        log.warn(
                "Studio workflow stream failed for sandbox {} workflow" + " {}",
                session.getId(),
                workflowId,
                e);
        String message =
                e instanceof java.util.concurrent.TimeoutException
                        ? "This run hit the studio's "
                                + timeout.toMinutes()
                                + "-minute time cap and was stopped."
                        : "The run stopped unexpectedly. Try again in a" + " moment.";
        return Flux.just(json(Map.of("type", "error", "message", message)));
    }

    RunView toView(DemoStudioRun r) {
        Object trace;
        try {
            trace = objectMapper.readValue(r.getTraceJson(), Object.class);
        } catch (JsonProcessingException e) {
            trace = List.of();
        }
        return new RunView(
                r.getId(),
                r.getStatus(),
                r.getInputText(),
                r.getOutputText(),
                trace,
                r.getStartedAt(),
                r.getFinishedAt());
    }

    String json(Map<String, ?> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"error\",\"message\":\"unserialisable event\"}";
        }
    }
}
