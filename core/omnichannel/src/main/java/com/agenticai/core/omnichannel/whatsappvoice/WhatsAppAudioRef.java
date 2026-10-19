package com.agenticai.core.omnichannel.whatsappvoice;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The part of an inbound Meta WhatsApp Cloud API {@code messages[]} entry of {@code type: "audio"}
 * this feature needs. Shape per Meta's webhook reference
 * (https://developers.facebook.com/documentation/business-messaging/whatsapp/webhooks/reference/messages/audio.md):
 * <pre>
 * "type": "audio",
 * "audio": { "id": "1908647269898587", "mime_type": "audio/ogg; codecs=opus",
 *            "sha256": "...base64...", "voice": true }
 * </pre>
 * {@code voice=true} marks a recording made with the WhatsApp client's microphone button;
 * {@code false} is an ordinary audio file. Both are accepted here (the same speech-to-text works on
 * either), the flag is kept only for diagnostics.
 *
 * @param mediaId  Meta media id; validated because it ends up in a Graph URL path
 * @param mimeType as announced by the webhook (may carry a {@code ; codecs=...} suffix); may be null
 * @param sha256   announced hash of the media bytes, base64 as in Meta's example; may be null
 */
public record WhatsAppAudioRef(String mediaId, String mimeType, String sha256, boolean voice) {

    private static final Pattern MEDIA_ID = Pattern.compile("[0-9A-Za-z_-]{1,64}");

    /** Empty when the entry is not an audio message or has no usable media id. Never throws. */
    public static Optional<WhatsAppAudioRef> parse(JsonNode message) {
        if (message == null || !"audio".equals(message.path("type").asText(""))) {
            return Optional.empty();
        }
        JsonNode audio = message.path("audio");
        String id = audio.path("id").asText("");
        if (!MEDIA_ID.matcher(id).matches()) {
            return Optional.empty();
        }
        String mime = audio.path("mime_type").asText(null);
        String sha = audio.path("sha256").asText(null);
        return Optional.of(new WhatsAppAudioRef(id, mime, sha, audio.path("voice").asBoolean(false)));
    }
}
