package com.agenticai.core.omnichannel.imessage;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * Turns a BlueBubbles message into the few fields the router needs. The same shape arrives two
 * ways: wrapped in a webhook event ({@code {"type":"new-message","data":{...}}}) and bare, as an
 * element of a {@code /message/query} answer (polling).
 *
 * <p>Everything that must never reach an agent is dropped here: the Mac's own outgoing messages
 * ({@code isFromMe} - without this every reply the platform sends comes back as a new message and
 * the agent talks to itself), reactions/tapbacks, group rename and member events, and events that
 * are not new messages (read receipts, typing, edits).
 */
final class ImessageEventParser {

    /** BlueBubbles/Messages chat style code for a group chat; 45 is a direct chat. */
    private static final int STYLE_GROUP = 43;

    /**
     * {@code channel_session.external_id} and {@code channel_message_log.external_id} are
     * VARCHAR(64) and the chat GUID is the session key. A longer GUID (a direct chat with a very
     * long Apple-ID email) would fail the session insert on every message, so it is dropped here
     * instead of reaching the database.
     */
    static final int MAX_CHAT_GUID_CHARS = 64;

    private ImessageEventParser() {}

    /**
     * @param guid the message's unique id, the dedup key
     * @param chatGuid the conversation, the session's external id
     * @param sender the other party, normalised ({@link ImessageAddress#normalize}); never blank
     * @param text the message text, never blank
     * @param group true for a group chat
     * @param dateMs BlueBubbles {@code dateCreated}, 0 when absent
     */
    record Inbound(
            String guid,
            String chatGuid,
            String sender,
            String text,
            boolean group,
            boolean hasAttachments,
            long dateMs) {}

    static Optional<Inbound> parse(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return Optional.empty();
        }
        JsonNode message = root;
        if (root.has("type")) {
            if (!"new-message".equals(root.path("type").asText())) {
                return Optional.empty();
            }
            message = root.path("data");
        }
        if (message.isMissingNode() || !message.isObject()) {
            return Optional.empty();
        }
        if (message.path("isFromMe").asBoolean(false)) {
            return Optional.empty();
        }
        if (!message.path("associatedMessageGuid").asText("").isBlank()
                || message.path("itemType").asInt(0) != 0
                || message.path("groupActionType").asInt(0) != 0) {
            return Optional.empty();
        }
        String guid = message.path("guid").asText("");
        JsonNode chat = message.path("chats").path(0);
        String chatGuid = chat.path("guid").asText("");
        if (guid.isBlank() || chatGuid.isBlank() || chatGuid.length() > MAX_CHAT_GUID_CHARS) {
            return Optional.empty();
        }
        String text = message.path("text").asText("");
        boolean attachments =
                message.path("attachments").isArray() && !message.path("attachments").isEmpty();
        if (text.isBlank()) {
            return Optional.empty();
        }
        boolean group = chat.path("style").asInt(0) == STYLE_GROUP || chatGuid.contains(";+;");
        String sender =
                ImessageAddress.normalize(message.path("handle").path("address").asText(null));
        if (sender == null && !group) {
            // The handle is the chat identifier for a direct chat ("iMessage;-;+15550100100").
            int cut = chatGuid.lastIndexOf(';');
            sender = ImessageAddress.normalize(cut >= 0 ? chatGuid.substring(cut + 1) : null);
        }
        if (sender == null) {
            return Optional.empty();
        }
        return Optional.of(
                new Inbound(
                        guid,
                        chatGuid,
                        sender,
                        text,
                        group,
                        attachments,
                        message.path("dateCreated").asLong(0)));
    }
}
