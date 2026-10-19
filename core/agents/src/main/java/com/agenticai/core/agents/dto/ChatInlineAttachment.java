package com.agenticai.core.agents.dto;

/**
 * One inline attachment on a chat turn (multimodal playground contract):
 * {@code dataBase64} is the raw file content, base64-encoded (a {@code
 * data:...;base64,} prefix is tolerated and stripped). Validated and decoded
 * by {@code InlineAttachmentProcessor} - at most 3 per message, 10 MB total
 * decoded, and the MIME type must be one the agent's model actually accepts
 * (see {@code ModelModalitiesService}).
 *
 * <p>{@code source} (live-vision contract) is the OPTIONAL origin
 * of the bytes: {@code null}/{@code "upload"} (a file the user picked -
 * unchanged pre-existing behavior), {@code "camera"} (a webcam frame) or
 * {@code "screen"} (a screen-share frame). Camera/screen frames are counted
 * against the per-user daily vision frame quota ({@code VisionUsageService})
 * before the model is invoked; anything outside the allowed set is rejected
 * with the same {@code MalformedAttachmentException} 400 path as every other
 * malformed field here. The 3-arg constructor keeps every pre-contract
 * caller (and test) compiling unchanged - absent means "upload".
 */
public record ChatInlineAttachment(
    String fileName,
    String mimeType,
    String dataBase64,
    String source
) {
    public ChatInlineAttachment(String fileName, String mimeType, String dataBase64) {
        this(fileName, mimeType, dataBase64, null);
    }
}
