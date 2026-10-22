package com.agenticai.core.omnichannel.matrix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Turns a {@code /sync} response into the three things the channel acts on: room invites addressed
 * to the bot, timeline events, and the joined-member count of each room (to tell a DM from a group
 * room). Pure: no I/O, no state, nothing is decided here.
 */
final class MatrixSyncParser {

    private MatrixSyncParser() {}

    /** One event from a joined room's timeline (or the encryption state event). */
    record RoomEvent(
            String roomId,
            String eventId,
            String sender,
            String type,
            JsonNode content,
            long originServerTs) {}

    /** A pending invite for the bot and who sent it. */
    record Invite(String roomId, String inviter) {}

    record Batch(
            String nextBatch,
            List<Invite> invites,
            List<RoomEvent> events,
            Map<String, Integer> joinedCounts) {}

    static Batch parse(JsonNode sync, String botUserId) {
        String next = sync.path("next_batch").asString(null);
        List<Invite> invites = new ArrayList<>();
        List<RoomEvent> events = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();

        JsonNode rooms = sync.path("rooms");
        Iterator<Map.Entry<String, JsonNode>> invited =
                rooms.path("invite").properties().iterator();
        while (invited.hasNext()) {
            Map.Entry<String, JsonNode> room = invited.next();
            String inviter = inviterOf(room.getValue(), botUserId);
            if (inviter != null) {
                invites.add(new Invite(room.getKey(), inviter));
            }
        }

        Iterator<Map.Entry<String, JsonNode>> joined = rooms.path("join").properties().iterator();
        while (joined.hasNext()) {
            Map.Entry<String, JsonNode> entry = joined.next();
            String roomId = entry.getKey();
            JsonNode room = entry.getValue();
            JsonNode count = room.path("summary").path("m.joined_member_count");
            if (count.isInt()) {
                counts.put(roomId, count.asInt());
            }
            for (JsonNode e : room.path("state").path("events")) {
                if ("m.room.encryption".equals(e.path("type").asString())) {
                    events.add(toEvent(roomId, e));
                }
            }
            for (JsonNode e : room.path("timeline").path("events")) {
                events.add(toEvent(roomId, e));
            }
        }
        return new Batch(next, invites, events, counts);
    }

    private static RoomEvent toEvent(String roomId, JsonNode e) {
        return new RoomEvent(
                roomId,
                e.path("event_id").asString(null),
                e.path("sender").asString(null),
                e.path("type").asString(""),
                e.path("content"),
                e.path("origin_server_ts").asLong(0L));
    }

    private static String inviterOf(JsonNode room, String botUserId) {
        for (JsonNode e : room.path("invite_state").path("events")) {
            if ("m.room.member".equals(e.path("type").asString())
                    && botUserId.equals(e.path("state_key").asString())
                    && "invite".equals(e.path("content").path("membership").asString())) {
                String sender = e.path("sender").asString(null);
                return sender == null || sender.isBlank() ? null : sender;
            }
        }
        return null;
    }
}
