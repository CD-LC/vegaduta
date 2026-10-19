package com.agenticai.core.agents.resumable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.resumable.ResumableChatStreamService.TurnSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * Prod 2026-10-05 (bug 13): after a failed turn the page-load restore replays ONE closing frame (no text was ever
 * produced) and showed nothing. That frame, the snapshot and the reply must now carry the failure.
 */
class ResumableChatTurnControllerFailedTurnTest {

    private final ResumableChatStreamService streams = mock(ResumableChatStreamService.class);
    private final ResumableTurnReplyService replies = mock(ResumableTurnReplyService.class);
    private final ResumableChatTurnController controller = new ResumableChatTurnController(streams, replies);

    private static ServerSentEvent<String> turnEnd(String status) {
        return ServerSentEvent.<String>builder().id("1").event("turn-end")
            .data("{\"status\":\"" + status + "\",\"lastEventId\":1}").build();
    }

    @Test
    void theClosingFrameOfAFailedTurnCarriesTheMessageTheUserWasShown() {
        when(streams.resume("turn-1", 0L)).thenReturn(Flux.just(turnEnd("error")));
        when(replies.failureMessage("turn-1")).thenReturn("The AI provider rejected our credentials.");

        List<ServerSentEvent<String>> events = controller.stream("turn-1", 0L, null).getBody().collectList().block();

        assertEquals(1, events.size());
        assertEquals("turn-end", events.get(0).event());
        assertTrue(events.get(0).data().contains("\"status\":\"error\""), events.get(0).data());
        assertTrue(events.get(0).data().contains("\"error\":\"The AI provider rejected our credentials.\""),
            events.get(0).data());
    }

    @Test
    void aTurnThatCompletedOrWhoseFailureIsUnknownIsLeftAsItWas() {
        when(streams.resume("turn-ok", 0L)).thenReturn(Flux.just(turnEnd("complete")));
        when(streams.resume("turn-x", 0L)).thenReturn(Flux.just(turnEnd("error")));
        when(replies.failureMessage("turn-x")).thenReturn(null);

        assertEquals(turnEnd("complete").data(), controller.stream("turn-ok", 0L, null).getBody().blockFirst().data());
        assertEquals(turnEnd("error").data(), controller.stream("turn-x", 0L, null).getBody().blockFirst().data());
    }

    @Test
    void aFailingLookupNeverBreaksTheStream() {
        when(streams.resume("turn-1", 0L)).thenReturn(Flux.just(turnEnd("error")));
        when(replies.failureMessage("turn-1")).thenThrow(new IllegalStateException("redis down"));

        assertEquals(turnEnd("error").data(), controller.stream("turn-1", 0L, null).getBody().blockFirst().data());
    }

    @Test
    void theSnapshotOfAFailedTurnCarriesTheMessage() {
        when(streams.snapshot("turn-1")).thenReturn(new TurnSnapshot("turn-1", "error", 1L, ""));
        when(replies.failureMessage("turn-1")).thenReturn("Rejected.");

        TurnSnapshot snapshot = controller.snapshot("turn-1");

        assertEquals("error", snapshot.status());
        assertEquals("Rejected.", snapshot.error());
    }

    @Test
    void theSnapshotOfARunningOrCompleteTurnHasNoError() {
        when(streams.snapshot(any())).thenReturn(new TurnSnapshot("turn-1", "complete", 3L, "hi"));

        assertNull(controller.snapshot("turn-1").error());
    }

    @Test
    void withTurnEndErrorLeavesOtherFramesAndNullMessagesAlone() {
        ServerSentEvent<String> token = ServerSentEvent.<String>builder().id("2").data("hi").build();

        assertEquals(token, ResumableChatStreamService.withTurnEndError(token, "x"));
        assertEquals(turnEnd("error"), ResumableChatStreamService.withTurnEndError(turnEnd("error"), null));
        assertEquals(turnEnd("complete").data(),
            ResumableChatStreamService.withTurnEndError(turnEnd("complete"), "x").data());
    }
}
