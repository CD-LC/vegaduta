package com.agenticai.core.omnichannel.signal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The part of a signal-cli envelope the agent acts on: a plain text message from a person (direct
 * or in a group). Receipts, typing indicators, reactions, deletions, stories, sync transcripts of
 * the account's own other devices and anything without text or attachment are dropped by {@link
 * #parse}, so the rest of the pipeline only ever sees something a human wrote to the agent.
 *
 * @param senderNumber E.164 number, null when the sender hides it
 * @param senderUuid the sender's ACI, null on old servers
 * @param groupId signal-cli's internal group id, null for a direct message
 * @param addressedToAccount a mention of the account, or a quote of one of its own messages
 */
public record SignalEnvelope(
        String senderNumber,
        String senderUuid,
        long timestamp,
        String text,
        String groupId,
        boolean addressedToAccount) {

    /** Longest text handed to the agent; a Signal message is far shorter, this is an abuse cap. */
    static final int MAX_TEXT_CHARS = 8_000;

    public boolean isGroup() {
        return groupId != null;
    }

    /** The stable id of the person: number when known, else UUID. */
    public String senderId() {
        return senderNumber != null ? senderNumber : senderUuid;
    }

    /** The conversation the reply goes to and the session is keyed on. */
    public String conversationId() {
        return isGroup() ? SignalAddress.groupExternalId(groupId) : senderId();
    }

    /** Idempotency key: signal-cli redelivers on reconnect, the same sender never reuses a timestamp. */
    public String dedupKey() {
        return conversationId() + ":" + senderId() + ":" + timestamp;
    }

    /**
     * @param wrapper an element of {@code GET /v1/receive} or a websocket frame:
     *     {@code {"envelope":{...},"account":"+..."}} (a bare envelope and a JSON-RPC style {@code
     *     params} wrapper are accepted too)
     * @param accountNumber the connected number, to drop the account's own echoes
     */
    public static Optional<SignalEnvelope> parse(JsonNode wrapper, String accountNumber) {
        if (wrapper == null || !wrapper.isObject()) {
            return Optional.empty();
        }
        JsonNode root = wrapper.has("params") ? wrapper.path("params") : wrapper;
        JsonNode envelope = root.has("envelope") ? root.path("envelope") : root;
        JsonNode data = envelope.path("dataMessage");
        if (!data.isObject()) {
            return Optional.empty();
        }
        if (data.hasNonNull("reaction") || data.hasNonNull("remoteDelete") || data.hasNonNull("storyContext")) {
            return Optional.empty();
        }
        String number = blankToNull(firstText(envelope, "sourceNumber", "source"));
        if (number != null && !number.startsWith("+")) {
            // "source" is a UUID on servers that hide numbers.
            number = null;
        }
        String uuid = blankToNull(firstText(envelope, "sourceUuid", "sourceAci"));
        if (uuid == null && number == null) {
            String source = blankToNull(envelope.path("source").asText(null));
            uuid = source;
        }
        // Both end up as a session key and a send recipient: only well-formed values are routed.
        if (number != null && !SignalAddress.isAddressable(number)) {
            number = null;
        }
        if (uuid != null && !SignalAddress.isAddressable(uuid)) {
            uuid = null;
        }
        if (number == null && uuid == null) {
            return Optional.empty();
        }
        if (number != null && number.equals(accountNumber)) {
            return Optional.empty();
        }

        String text = sanitize(data.path("message").asText(""));
        List<String> notes = new ArrayList<>();
        for (JsonNode attachment : data.path("attachments")) {
            if (notes.size() >= 10) {
                break;
            }
            // Both come from the sender: no line breaks, and short.
            String type = sanitize(attachment.path("contentType").asText("file")).replace('\n', ' ');
            type = type.isEmpty() ? "file" : cap(type, 100);
            String name = cap(sanitize(attachment.path("filename").asText("")).replace('\n', ' '), 100);
            notes.add("[attachment: " + type + (name.isEmpty() ? "" : " " + name) + "]");
        }
        if (!notes.isEmpty()) {
            text = text.isEmpty() ? String.join("\n", notes) : text + "\n" + String.join("\n", notes);
        }
        if (text.isBlank()) {
            return Optional.empty();
        }
        if (text.length() > MAX_TEXT_CHARS) {
            text = text.substring(0, MAX_TEXT_CHARS);
        }

        String groupId = null;
        JsonNode groupInfo = data.path("groupInfo");
        if (groupInfo.isObject()) {
            groupId = blankToNull(groupInfo.path("groupId").asText(null));
            if (!SignalAddress.validGroupInternalId(groupId)) {
                // A group id that cannot be stored or addressed is not routed at all.
                return Optional.empty();
            }
        }

        long ts = data.path("timestamp").asLong(envelope.path("timestamp").asLong(0));
        return Optional.of(
                new SignalEnvelope(
                        number, uuid, ts, text, groupId, addressedTo(data, accountNumber)));
    }

    private static boolean addressedTo(JsonNode data, String accountNumber) {
        for (JsonNode mention : data.path("mentions")) {
            if (accountNumber.equals(mention.path("number").asText(null))) {
                return true;
            }
        }
        return accountNumber.equals(data.path("quote").path("authorNumber").asText(null))
                || accountNumber.equals(data.path("quote").path("author").asText(null));
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText(null);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String cap(String value, int max) {
        return value.length() > max ? value.substring(0, max) : value;
    }

    /** Drops control characters other than newline and tab (a sender controls this text). */
    private static String sanitize(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) {
                out.append(c);
            }
        }
        return out.toString().strip();
    }
}
