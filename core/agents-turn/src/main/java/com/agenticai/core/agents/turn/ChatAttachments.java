package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.ChatInlineAttachment;
import com.agenticai.core.agents.modality.ChatAttachmentCategory;
import com.agenticai.core.agents.modality.UnsupportedAttachmentTypeException;
import com.agenticai.core.agents.tools.e2b.SandboxFileRegistry;
import com.agenticai.core.storage.FileStorageService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

/**
 * Attachment-key authorization, stored/inline attachment resolution and the attachment-aware prompt
 * helpers, extracted verbatim from {@link AgentChatService} (extract-only split).
 */
final class ChatAttachments {

    private static final Logger log = LoggerFactory.getLogger(AgentChatService.class);

    private final FileStorageService fileStorageService;
    private final InlineAttachmentProcessor inlineAttachmentProcessor;
    private final AgentService agentService;

    ChatAttachments(
            FileStorageService fileStorageService,
            InlineAttachmentProcessor inlineAttachmentProcessor,
            AgentService agentService) {
        this.fileStorageService = fileStorageService;
        this.inlineAttachmentProcessor = inlineAttachmentProcessor;
        this.agentService = agentService;
    }

    /**
     * The attachment-key authorization boundary (cross-tenant object-read fix, security audit
     * 2026-07-29 §2 item 3).
     *
     * <p><b>What was wrong.</b> {@code attachmentKey} is a raw string off the request body ({@code
     * ChatRequest.attachmentKey}) that went straight into {@code FileStorageService.get()}. {@code
     * S3FileStorageService} prefixes the caller's tenant onto a key it is given, but when that
     * prefixed lookup misses it retries the BARE key - a global, un-scoped namespace shared by
     * every tenant. So a caller in tenant A asking for {@code "tenant-exports/<tenant B's
     * uuid>/<millis>.json"} got a miss at {@code tenant/A/tenant-exports/...} and then a HIT,
     * unprefixed, on tenant B's full JSON data export - which was then read into the prompt and
     * summarized back to the attacker by the model itself. The only cross-tenant check that existed
     * ({@code verifyTenantOwnership}) fires exclusively on keys that already carry a {@code
     * tenant/<id>/} prefix, which an attacker simply would not send.
     *
     * <p><b>The rule.</b> The only keys this platform ever mints for a chat turn are the ones
     * {@link ChatAttachmentService#upload} writes: {@code chat-attachments/<agentId>/<uuid>}. So
     * rather than sanitizing whatever the client sent, the accepted key is REBUILT here out of
     * server-side values - {@link ChatAttachmentService#KEY_PREFIX}, the {@code agentId} this turn
     * is already scoped to (resolved from the tenant's own schema by {@code
     * AgentService.getEntity}), and the canonical {@code toString()} of a successfully PARSED
     * {@link UUID}. Not one byte of the caller's string survives into the key handed to storage.
     * That makes the whole class of path tricks structurally impossible rather than blocklisted:
     * {@code ..} segments, absolute paths, {@code s3://}/{@code http://} scheme forms, URL-encoded
     * separators, a hand-written {@code tenant/<other>/...} prefix, and any other object namespace
     * ({@code tenant-exports/}, {@code avatars/}, {@code knowledge-documents/}, ...) all fail the
     * {@code startsWith} or the {@code UUID.fromString} and never reach S3.
     *
     * <p><b>Same-agent, deliberately.</b> An attachment uploaded against agent X cannot be replayed
     * on a chat turn with agent Y, even inside one tenant. That matches the documented contract on
     * both sides ({@code POST /api/agents/{agentId}/chat/attachments} is "for use in a subsequent
     * chat call on this same agent", and the web client's {@code uploadChatAttachment}/{@code
     * streamChat} pair always uses one agent id) and it preserves the upload-time model-capability
     * check, which was validated against agent X's model and means nothing for agent Y's.
     *
     * <p>Blank is treated as absent (a client sending {@code ""} gets a plain text turn, not a
     * 400). Everything else that isn't an exact match is refused with a 400 via {@link
     * MalformedAttachmentException}, logged with the offending key at WARN because a rejection here
     * is either a broken client or somebody probing the bucket - both worth seeing. The refusal
     * message deliberately does NOT echo the submitted key back.
     *
     * @return the canonical, server-derived key, or {@code null} for a turn with no stored
     *     attachment
     */
    static String requireOwnAttachmentKey(UUID agentId, String attachmentKey) {
        if (attachmentKey == null || attachmentKey.isBlank()) {
            return null;
        }
        String expectedPrefix = ChatAttachmentService.KEY_PREFIX + agentId + "/";
        if (attachmentKey.startsWith(expectedPrefix)) {
            try {
                // Re-serializing the PARSED uuid (rather than reusing the
                // caller's substring) is what makes the returned key wholly
                // server-derived - see this method's javadoc.
                return expectedPrefix
                        + UUID.fromString(attachmentKey.substring(expectedPrefix.length()));
            } catch (IllegalArgumentException notAUuid) {
                // Falls through to the shared rejection below - an
                // unparseable suffix is not meaningfully different from a
                // wrong prefix, and both deserve the same log line.
            }
        }
        log.warn(
                "Rejected chat attachmentKey '{}' for agent {} - not a '{}<agentId>/<uuid>' key minted by this "
                        + "platform for this agent. Refusing rather than passing it to object storage.",
                attachmentKey,
                agentId,
                ChatAttachmentService.KEY_PREFIX);
        throw new MalformedAttachmentException(
                "attachmentKey is not a valid attachment reference for this agent. Upload the file via "
                        + "POST /api/agents/"
                        + agentId
                        + "/chat/attachments and send back the key it returns.");
    }

    /**
     * Stage 4 (Chat file upload) - resolves an optional attachment reference into the actual bytes
     * + {@link MimeType} the Spring AI prompt builder needs. Returns {@code null} for a plain text
     * turn (attachmentKey null). Reading the stored bytes here is a small, bounded synchronous read
     * from this app's own object storage (not a third-party/sidecar round-trip), so doing it on the
     * request thread is fine - unlike the sidecar transcription path this deliberately avoids, see
     * {@code ChatAttachmentService}'s javadoc.
     */
    private ChatAttachmentPayload loadAttachment(
            UUID agentId, String attachmentKey, String attachmentMimeType) {
        // Deliberately re-validated here rather than trusting the caller to
        // have done it: this is the exact line that reaches object storage,
        // and the whole 2026-07-29 finding was that NOTHING between the
        // request body and this call checked the key. Idempotent - a key
        // already normalized by chat() passes through unchanged - so the
        // cost of the double check is one startsWith + one UUID parse.
        String verifiedKey = requireOwnAttachmentKey(agentId, attachmentKey);
        if (verifiedKey == null) {
            return null;
        }
        try (InputStream in = fileStorageService.get(verifiedKey)) {
            byte[] bytes = in.readAllBytes();
            MimeType mimeType = MimeTypeUtils.parseMimeType(attachmentMimeType);
            return new ChatAttachmentPayload(mimeType, new ByteArrayResource(bytes), null);
        } catch (IOException e) {
            throw new UnsupportedAttachmentTypeException(
                    "Could not read the stored attachment: " + e.getMessage());
        }
    }

    /**
     * This turn's full media list: the optional Stage-4 STORED attachment (attachmentKey,
     * pre-validated at upload time by {@code ChatAttachmentService}) plus any inline base64
     * attachments from the multimodal playground contract, validated/decoded RIGHT HERE by {@link
     * InlineAttachmentProcessor} (413/415/400 - thrown synchronously, before session memory sees
     * the turn).
     *
     * <p>Guardrails note: the turn's TEXT still goes through {@link #moderateInbound} above exactly
     * as before - attachments never bypass it. Content-level moderation of the attachment BYTES
     * (image/audio classification) is deliberately out of scope for this stage; this method is the
     * seam to add it, since it's the one place the decoded bytes exist before the model call.
     */
    List<ChatAttachmentPayload> resolveAttachments(
            Agent agent,
            UUID agentId,
            String attachmentKey,
            String attachmentMimeType,
            List<ChatInlineAttachment> inlineAttachments) {
        List<ChatAttachmentPayload> payloads = new ArrayList<>();
        ChatAttachmentPayload stored = loadAttachment(agentId, attachmentKey, attachmentMimeType);
        if (stored != null) {
            payloads.add(stored);
        }
        if (inlineAttachments != null && !inlineAttachments.isEmpty()) {
            List<InlineAttachmentProcessor.ProcessedAttachment> processed =
                    inlineAttachmentProcessor.process(
                            inlineAttachments,
                            agent.getProviderName(),
                            agentService.resolveEffectiveModel(agent));
            for (InlineAttachmentProcessor.ProcessedAttachment p : processed) {
                payloads.add(new ChatAttachmentPayload(p.mimeType(), p.resource(), p.fileName()));
            }
        }
        return payloads;
    }

    /**
     * How many of this turn's inline attachments are live-vision frames (source "camera"/"screen" -
     * values already validated against the closed set by {@link InlineAttachmentProcessor}, so
     * anything else here is upload/null and deliberately NOT metered as vision).
     */
    static int countVisionFrames(List<ChatInlineAttachment> inlineAttachments) {
        if (inlineAttachments == null || inlineAttachments.isEmpty()) {
            return 0;
        }
        int frames = 0;
        for (ChatInlineAttachment attachment : inlineAttachments) {
            if (attachment != null
                    && ("camera".equals(attachment.source())
                            || "screen".equals(attachment.source()))) {
                frames++;
            }
        }
        return frames;
    }

    /**
     * Data-analysis sandbox (gap-analysis item 9): offers this turn's data files (CSV/XLSX/JSON/
     * PDF/TXT) to the {@code run_code} tool by registering them under agent + session. Best-effort
     * and never throws - a file that cannot be read or registered is simply not mounted.
     */
    static void mountForSandbox(
            SandboxFileRegistry registry,
            UUID agentId,
            String sessionId,
            List<ChatAttachmentPayload> attachments) {
        if (registry == null || attachments == null) {
            return;
        }
        for (ChatAttachmentPayload payload : attachments) {
            if (payload == null
                    || payload.mimeType() == null
                    || !SandboxFileRegistry.isMountable(payload.mimeType().toString())) {
                continue;
            }
            try {
                // tryRegister records a refusal reason as a warning the next run_code result
                // carries.
                registry.tryRegister(
                        agentId,
                        sessionId,
                        payload.fileName(),
                        payload.mimeType().toString(),
                        payload.resource().getContentAsByteArray());
            } catch (IOException | RuntimeException e) {
                log.warn("Could not mount attachment for the code sandbox: {}", e.getMessage());
                registry.noteWarning(
                        agentId,
                        sessionId,
                        "Not mounted: "
                                + (payload.fileName() == null
                                        ? "an attached file"
                                        : "'" + payload.fileName() + "'")
                                + " could not be read.");
            }
        }
    }

    /**
     * The attachments the MODEL may see: drops sandbox-only types (xlsx), which no chat model
     * accepts as input.
     */
    static List<ChatAttachmentPayload> withoutSandboxOnly(List<ChatAttachmentPayload> attachments) {
        return withoutSandboxOnly(attachments, false);
    }

    /**
     * As {@link #withoutSandboxOnly(List)}; with {@code dropDocuments} (the model has no native
     * document input but the sandbox took the file) PDF/TXT/CSV/JSON payloads are withheld too.
     */
    static List<ChatAttachmentPayload> withoutSandboxOnly(
            List<ChatAttachmentPayload> attachments, boolean dropDocuments) {
        if (attachments.stream().noneMatch(a -> sandboxOnly(a, dropDocuments))) {
            return attachments;
        }
        List<ChatAttachmentPayload> kept = new ArrayList<>();
        for (ChatAttachmentPayload a : attachments) {
            if (!sandboxOnly(a, dropDocuments)) {
                kept.add(a);
            }
        }
        return kept;
    }

    private static boolean sandboxOnly(ChatAttachmentPayload a, boolean dropDocuments) {
        String mime = a.mimeType().toString();
        return ChatAttachmentCategory.isSandboxOnly(mime)
                || (dropDocuments && ChatAttachmentCategory.isDocument(mime));
    }

    /**
     * Session-memory placeholder for this turn's attachments - "[attached: photo.jpg (image/jpeg)]"
     * appended after the user's text. History replay (and every downstream reader of session
     * history) stays coherent about what was shared, without persisting or re-sending the payload
     * bytes on every later turn.
     */
    static String withAttachmentNote(String userMessage, List<ChatAttachmentPayload> attachments) {
        if (attachments.isEmpty()) {
            return userMessage;
        }
        StringBuilder sb = new StringBuilder(userMessage);
        for (ChatAttachmentPayload attachment : attachments) {
            sb.append("\n[attached: ");
            if (attachment.fileName() != null) {
                sb.append(attachment.fileName())
                        .append(" (")
                        .append(attachment.mimeType())
                        .append(')');
            } else {
                sb.append(attachment.mimeType());
            }
            sb.append(']');
        }
        return sb.toString();
    }

    /**
     * Builds the {@code .user(...)} spec for one turn - plain text, or text plus this turn's {@link
     * ChatAttachmentPayload} media (Spring AI's fluent multimodal builder, see {@code
     * AnthropicAgentChatClientFactory} javadoc history / Stage 4 build notes for why the fluent
     * form is used over constructing {@code Media} directly). Repeated {@code spec.media(...)}
     * calls accumulate, one per attachment.
     */
    static Consumer<ChatClient.PromptUserSpec> userSpec(
            String userMessage, List<ChatAttachmentPayload> attachments) {
        return spec -> {
            spec.text(userMessage);
            for (ChatAttachmentPayload attachment : attachments) {
                spec.media(attachment.mimeType(), attachment.resource());
            }
        };
    }
}
