package com.agenticai.core.agents.resumable;

import com.agenticai.core.agents.resumable.ResumableStreamBuffer.Entry;
import org.springframework.http.codec.ServerSentEvent;

/** SSE framing of resumable-stream buffer entries; see {@link ResumableChatStreamService}. */
final class ResumableSseFrames {

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private ResumableSseFrames() {}

    /**
     * The closing {@code turn-end} frame with the failure message added ({@code "error":"..."}) for
     * a turn that ended in {@code error}: a client that reloads after a failed turn replays only
     * that frame (no text was ever produced) and would otherwise show nothing. Any other event, or
     * a null message, is returned unchanged.
     */
    static ServerSentEvent<String> withTurnEndError(
            ServerSentEvent<String> event, String message) {
        if (event == null
                || message == null
                || message.isBlank()
                || !ResumableChatStreamService.TURN_END_EVENT.equals(event.event())
                || event.data() == null) {
            return event;
        }
        try {
            com.fasterxml.jackson.databind.node.ObjectNode node =
                    (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(event.data());
            if (!ResumableChatStreamService.STATUS_ERROR.equals(node.path("status").asText())) {
                return event;
            }
            node.put("error", message);
            return ServerSentEvent.<String>builder()
                    .id(event.id())
                    .event(ResumableChatStreamService.TURN_END_EVENT)
                    .data(JSON.writeValueAsString(node))
                    .build();
        } catch (Exception e) {
            return event;
        }
    }

    static ServerSentEvent<String> toSse(Entry entry) {
        ServerSentEvent.Builder<String> builder =
                ServerSentEvent.<String>builder().id(String.valueOf(entry.id()));
        if (entry.terminal()) {
            return builder.event(ResumableChatStreamService.TURN_END_EVENT)
                    .data(
                            "{\"status\":\""
                                    + entry.payload()
                                    + "\",\"lastEventId\":"
                                    + entry.id()
                                    + "}")
                    .build();
        }
        return builder.data(entry.payload()).build();
    }
}
