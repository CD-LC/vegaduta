package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.agents.modality.ModelModalities;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.providers.ProviderName;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.InvalidMimeTypeException;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

/**
 * Validates and decodes the OPTIONAL {@code attachments} array on a chat
 * request (see {@code ChatRequest}) into the {@link Resource}s Spring AI's
 * multimodal prompt builder needs. Enforces the shared Playground contract:
 *
 * <ul>
 *   <li>at most {@link ModelModalities#MAX_ATTACHMENTS} items and {@link
 *       ModelModalities#MAX_ATTACHMENT_TOTAL_BYTES} decoded bytes total -
 *       {@link AttachmentPayloadTooLargeException} (HTTP 413) otherwise;</li>
 *   <li>each MIME type must map to a modality the agent's model accepts
 *       ({@link ModelModalitiesService}) - {@link
 *       AttachmentModalityNotSupportedException} (HTTP 415, naming the model
 *       and what it does support) otherwise;</li>
 *   <li>missing mimeType/data, invalid base64, an empty decoded payload, or
 *       an unparseable MIME string - {@link MalformedAttachmentException}
 *       (HTTP 400).</li>
 * </ul>
 *
 * <p>Runs synchronously on the request thread BEFORE anything is written to
 * session memory or any LLM client is invoked (see the call site in {@code
 * AgentChatService#chat}) - a rejected attachment leaves no trace of the
 * turn, matching every other pre-flight validation there.
 */
@Service
public class InlineAttachmentProcessor {

    /**
     * Live-vision contract - the closed set of legal {@code
     * ChatInlineAttachment.source} values. Absent/null is treated as
     * "upload"; anything else is a 400, same {@link
     * MalformedAttachmentException} path as a missing mimeType, so a typo'd
     * client can't silently dodge the camera/screen frame quota.
     */
    private static final Set<String> ALLOWED_SOURCES = Set.of("upload", "camera", "screen");

    private final ModelModalitiesService modelModalitiesService;

    public InlineAttachmentProcessor(ModelModalitiesService modelModalitiesService) {
        this.modelModalitiesService = modelModalitiesService;
    }

    /** A validated, decoded attachment ready to become Spring AI {@code Media}. */
    public record ProcessedAttachment(String fileName, MimeType mimeType, Resource resource) {
    }

    public List<ProcessedAttachment> process(List<ChatInlineAttachment> attachments, ProviderName provider, String model) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        if (attachments.size() > ModelModalities.MAX_ATTACHMENTS) {
            throw new AttachmentPayloadTooLargeException(
                "A chat message accepts at most " + ModelModalities.MAX_ATTACHMENTS
                    + " attachments (got " + attachments.size() + "). Remove some and try again."
            );
        }

        ModelModalities modalities = modelModalitiesService.resolve(provider, model);
        List<ProcessedAttachment> processed = new ArrayList<>(attachments.size());
        long totalDecodedBytes = 0;

        for (ChatInlineAttachment attachment : attachments) {
            if (attachment == null || !StringUtils.hasText(attachment.mimeType()) || !StringUtils.hasText(attachment.dataBase64())) {
                throw new MalformedAttachmentException(
                    "Each attachment needs both a mimeType and base64-encoded data (dataBase64)."
                );
            }
            String fileName = StringUtils.hasText(attachment.fileName()) ? attachment.fileName() : "attachment";

            if (attachment.source() != null && !ALLOWED_SOURCES.contains(attachment.source())) {
                throw new MalformedAttachmentException(
                    "Attachment '" + fileName + "' has an unknown source ('" + attachment.source()
                        + "') - expected one of: upload, camera, screen (or omit it)."
                );
            }

            assertModalitySupported(attachment.mimeType(), modalities, model, fileName);

            byte[] bytes = decode(attachment.dataBase64(), fileName);
            totalDecodedBytes += bytes.length;
            if (totalDecodedBytes > ModelModalities.MAX_ATTACHMENT_TOTAL_BYTES) {
                throw new AttachmentPayloadTooLargeException(
                    "Attachments are limited to " + (ModelModalities.MAX_ATTACHMENT_TOTAL_BYTES / 1024 / 1024)
                        + "MB total per message (decoded) - this message exceeds that at '" + fileName + "'."
                );
            }

            MimeType mimeType;
            try {
                mimeType = MimeTypeUtils.parseMimeType(attachment.mimeType());
            } catch (InvalidMimeTypeException e) {
                throw new MalformedAttachmentException(
                    "Attachment '" + fileName + "' has an unparseable mimeType (" + attachment.mimeType() + ")."
                );
            }

            // Anonymous subclass only to carry the filename - some providers
            // surface it in errors/logs, and ByteArrayResource itself returns null.
            processed.add(new ProcessedAttachment(fileName, mimeType, new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            }));
        }
        return processed;
    }

    private static byte[] decode(String dataBase64, String fileName) {
        // Tolerate a data-URI wrapper ("data:image/png;base64,....") and
        // whitespace/newlines (some clients chunk long base64), then decode
        // STRICTLY so genuinely corrupt input still 400s instead of silently
        // producing garbage bytes.
        String payload = dataBase64;
        if (payload.startsWith("data:")) {
            int comma = payload.indexOf(',');
            if (comma < 0) {
                throw new MalformedAttachmentException(
                    "Attachment '" + fileName + "' looks like a data: URI but has no ',' payload separator."
                );
            }
            payload = payload.substring(comma + 1);
        }
        payload = payload.replaceAll("\\s", "");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new MalformedAttachmentException(
                "Attachment '" + fileName + "' is not valid base64: " + e.getMessage()
            );
        }
        if (bytes.length == 0) {
            throw new MalformedAttachmentException("Attachment '" + fileName + "' decoded to zero bytes.");
        }
        return bytes;
    }

    private static void assertModalitySupported(String rawMimeType, ModelModalities modalities, String model, String fileName) {
        String mime = rawMimeType.toLowerCase(Locale.ROOT).trim();
        int semicolon = mime.indexOf(';');
        if (semicolon >= 0) {
            mime = mime.substring(0, semicolon).trim();
        }

        boolean supported;
        String kind;
        if (mime.startsWith("image/")) {
            supported = modalities.image();
            kind = "image";
        } else if (mime.startsWith("audio/")) {
            supported = modalities.audio();
            kind = "audio";
        } else if (mime.startsWith("video/")) {
            supported = modalities.video();
            kind = "video";
        } else if (mime.equals("application/pdf")) {
            supported = modalities.document();
            kind = "PDF";
        } else {
            supported = false;
            kind = mime;
        }

        if (!supported) {
            throw new AttachmentModalityNotSupportedException(
                "This agent's model (" + model + ") doesn't accept " + kind + " attachments in chat"
                    + " ('" + fileName + "'). It supports: " + supportedSummary(modalities)
                    + ". Remove the attachment, or switch the agent to a model that supports it."
            );
        }
    }

    private static String supportedSummary(ModelModalities modalities) {
        if (!modalities.any()) {
            return "text only";
        }
        List<String> kinds = new ArrayList<>(4);
        if (modalities.image()) {
            kinds.add("images");
        }
        if (modalities.audio()) {
            kinds.add("audio");
        }
        if (modalities.video()) {
            kinds.add("video");
        }
        if (modalities.document()) {
            kinds.add("PDF documents");
        }
        return String.join(", ", kinds);
    }
}
