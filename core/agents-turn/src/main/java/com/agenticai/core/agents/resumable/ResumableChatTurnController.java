package com.agenticai.core.agents.resumable;

import com.agenticai.core.agents.resumable.ResumableChatStreamService.TurnSnapshot;
import com.agenticai.core.agents.resumable.ResumableTurnReplyService.PersistedReply;
import com.agenticai.core.security.TenantScope;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Resume endpoints for resumable chat streams ({@code app.chat.resumable-streams.enabled}). Thin by
 * convention: the tenant / owner / RUNNER checks and every status code live in
 * {@link ResumableChatStreamService}. With the flag off every route answers 404, so the feature has no
 * observable surface until it is turned on.
 */
@RestController
@RequestMapping("/api/chat/turns")
public class ResumableChatTurnController {

    private final ResumableChatStreamService service;
    private final ResumableTurnReplyService replies;

    public ResumableChatTurnController(ResumableChatStreamService service, ResumableTurnReplyService replies) {
        this.service = service;
        this.replies = replies;
    }

    /**
     * Replays the turn's buffered events with id greater than {@code after} (or the standard
     * {@code Last-Event-ID} header when the parameter is absent), then follows it live to completion.
     * 404 when expired, unknown or not yours; 410 when events after {@code after} were already dropped.
     */
    @GetMapping(value = "/{turnId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> stream(
        @PathVariable String turnId,
        @RequestParam(name = "after", required = false) Long after,
        @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
    ) {
        Long cursor = after != null ? after : parseLastEventId(lastEventId);
        // A turn that failed before producing text replays as one closing frame; give it the failure message
        // (read lazily, only when that frame is reached) so a reloaded page can say what happened.
        // The frame is reached on a reactor thread that has no tenant context, and the lookup is tenant-scoped
        // (authorisation + session store): capture it here on the request thread.
        TenantScope tenant = TenantScope.capture();
        Flux<ServerSentEvent<String>> events = service.resume(turnId, cursor).map(event -> {
            if (!ResumableChatStreamService.TURN_END_EVENT.equals(event.event()) || event.data() == null
                || !event.data().contains("\"error\"")) {
                return event;
            }
            try {
                return ResumableChatStreamService.withTurnEndError(
                    event, tenant.get(() -> replies.failureMessage(turnId)));
            } catch (RuntimeException e) {
                return event; // the frame is useful without the message; never fail the stream over it
            }
        });
        return ResponseEntity.ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-cache")
            .header("X-Accel-Buffering", "no")
            .body(events);
    }

    /** State plus the text produced so far - what a client reconciles a resumed stream against. */
    @GetMapping(value = "/{turnId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TurnSnapshot snapshot(@PathVariable String turnId) {
        TurnSnapshot snapshot = service.snapshot(turnId);
        if (!"error".equals(snapshot.status())) {
            return snapshot;
        }
        try {
            return snapshot.withError(replies.failureMessage(turnId));
        } catch (RuntimeException e) {
            return snapshot;
        }
    }

    /**
     * The assistant message core stored for this turn - the fallback when the stream buffer cannot serve a resume
     * (404 expired / elsewhere, 410 trimmed). 404 for a turn that is not yours or not known, like every route here.
     */
    @GetMapping(value = "/{turnId}/reply", produces = MediaType.APPLICATION_JSON_VALUE)
    public PersistedReply persistedReply(@PathVariable String turnId) {
        return replies.persistedReply(turnId);
    }

    private static Long parseLastEventId(String header) {
        if (!StringUtils.hasText(header)) {
            return null;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
