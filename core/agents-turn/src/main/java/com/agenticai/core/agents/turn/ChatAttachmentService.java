package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.ChatAttachmentResponse;
import com.agenticai.core.agents.modality.ChatAttachmentCategory;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.agents.modality.UnsupportedAttachmentTypeException;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.storage.FileStorageService;
import java.io.IOException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stage 4 (Chat file upload) - backs {@code POST /api/agents/{agentId}/chat/attachments}. Validates
 * a single upload against three things before ever touching storage: it's a genuine
 * image/audio/video file ({@link ChatAttachmentCategory#fromContentType}), it's under this
 * category's size cap, and - the reason this is agent-scoped rather than a generic upload endpoint
 * - the target agent's currently-resolved provider + model actually accepts that modality as chat
 * input (see {@link com.agenticai.core.providers.ProviderModelCatalogEntry}'s javadoc: "the
 * chat/playground upload control reads these... before offering an attach button"). Rejecting an
 * unsupported-for-this-model attachment here, at upload time, gives the person immediate, specific
 * feedback instead of a confusing failure only surfacing later when they hit send.
 *
 * <p>Deliberately does NOT fall back to OCR/transcription for a model that lacks native support for
 * a modality (unlike Knowledge Stage F3b/F3c, which always extract/transcribe regardless of the
 * target). {@code SidecarClient.transcribeAudio}'s own javadoc documents it as "only ever called by
 * a background poller... can legitimately block for minutes" - exactly the kind of call this class
 * must NOT make, since {@link #upload} runs on a live HTTP request thread. Scoping this feature to
 * "true multimodal input on models that natively accept it" sidesteps that constraint entirely: the
 * raw bytes are handed to the model itself (see {@code AgentChatService}), no synchronous sidecar
 * round-trip involved.
 */
@Service
public class ChatAttachmentService {

    /**
     * The object-key namespace every chat attachment is stored under - {@code
     * chat-attachments/<agentId>/<uuid>}, and nothing else.
     *
     * <p>Package-visible rather than private on purpose: {@code AgentChatService} re-derives this
     * exact shape to validate the client-supplied {@code attachmentKey} it is handed back on the
     * next chat turn (cross-tenant object-read fix, security audit 2026-07-29). The minting side
     * and the validating side MUST agree on the shape, so they share one constant instead of two
     * literals that can drift apart - a drift here would either 400 every legitimate attachment or
     * silently re-open the hole.
     */
    static final String KEY_PREFIX = "chat-attachments/";

    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_AUDIO_BYTES = 15L * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 25L * 1024 * 1024;
    // Matches the inline path's total-attachment ceiling (ModelModalities
    // .MAX_ATTACHMENT_TOTAL_BYTES) so a PDF that would be accepted on the
    // /playground page is accepted here too.
    private static final long MAX_DOCUMENT_BYTES = 10L * 1024 * 1024;

    private final AgentService agentService;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final ModelModalitiesService modelModalitiesService;
    private final FileStorageService fileStorageService;

    public ChatAttachmentService(
            AgentService agentService,
            ProviderModelCatalogService providerModelCatalogService,
            ModelModalitiesService modelModalitiesService,
            FileStorageService fileStorageService) {
        this.agentService = agentService;
        this.providerModelCatalogService = providerModelCatalogService;
        this.modelModalitiesService = modelModalitiesService;
        this.fileStorageService = fileStorageService;
    }

    private volatile org.springframework.beans.factory.ObjectProvider<
                    com.agenticai.core.agents.tools.e2b.SandboxFileRegistry>
            sandboxFileRegistry;

    /** Setter-injected; absent or disabled = no sandbox-only uploads. */
    @org.springframework.beans.factory.annotation.Autowired
    public void setSandboxFileRegistry(
            org.springframework.beans.factory.ObjectProvider<
                            com.agenticai.core.agents.tools.e2b.SandboxFileRegistry>
                    provider) {
        this.sandboxFileRegistry = provider;
    }

    private com.agenticai.core.agents.tools.e2b.SandboxFileRegistry sandboxRegistry() {
        var provider = sandboxFileRegistry;
        return provider == null ? null : provider.getIfAvailable();
    }

    private boolean sandboxDataEnabled() {
        var registry = sandboxRegistry();
        return registry != null && registry.isEnabled();
    }

    public ChatAttachmentResponse upload(UUID agentId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new UnsupportedAttachmentTypeException("Uploaded file is empty");
        }

        ChatAttachmentCategory category =
                ChatAttachmentCategory.fromContentType(file.getContentType());

        long maxBytes =
                switch (category) {
                    case IMAGE -> MAX_IMAGE_BYTES;
                    case AUDIO -> MAX_AUDIO_BYTES;
                    case VIDEO -> MAX_VIDEO_BYTES;
                    case DOCUMENT, SPREADSHEET -> MAX_DOCUMENT_BYTES;
                };
        if (file.getSize() > maxBytes) {
            throw new AttachmentTooLargeException(
                    category.name().toLowerCase(java.util.Locale.ROOT)
                            + " attachments are limited to "
                            + (maxBytes / 1024 / 1024)
                            + "MB (uploaded file was "
                            + (file.getSize() / 1024 / 1024)
                            + "MB)");
        }

        // Cross-user resource sharing retrofit (Round 2) - RUNNER: this endpoint
        // is human/API-authenticated only (POST /api/agents/{id}/chat/attachments,
        // unlike the shared AgentChatService#chat entry point that's also reached
        // by omnichannel webhooks) - see AgentController#chat's own comment for
        // why the RUNNER bar belongs at a real-subject-only call site.
        Agent agent = agentService.requireAccess(agentId, PermissionLevel.RUNNER);
        // #1086 - agent.getModel() can be null for a handful of agents
        // persisted before AgentService#create's null-model fix; without
        // this, findModel(provider, null) always misses, and the rejection
        // message below would show "model (null)" - a confusing message for
        // something this call can just self-heal instead.
        String effectiveModel = agentService.resolveEffectiveModel(agent);
        // Same predicate the chat turn uses to mount files (SandboxFileRegistry#availableFor): flag
        // +
        // agent Code execution + this tenant's E2B key and template, so nothing is accepted that
        // could never reach the sandbox.
        var sandboxRegistry = sandboxRegistry();
        boolean sandboxData =
                sandboxRegistry != null && sandboxRegistry.availableFor(agent.isE2bEnabled());
        // IMAGE/AUDIO/VIDEO come from the provider model catalog's own
        // supports*Input flags. DOCUMENT (PDF-native chat input) is not a
        // catalog flag - it is resolved from ModelModalitiesService, the SAME
        // source the frontend's AgentModalitiesView.document advertises, so the
        // attach button offering documents and this check accepting them can
        // never drift apart.
        boolean supportedByModel =
                switch (category) {
                    case IMAGE, AUDIO, VIDEO ->
                            providerModelCatalogService
                                    .findModel(agent.getProviderName(), effectiveModel)
                                    .map(model -> supportsCategory(model, category))
                                    .orElse(false);
                    // With the data-analysis sandbox on, a data file is accepted even when the
                    // model has no
                    // native document input: the code-execution tool reads it and the model never
                    // sees the
                    // bytes (AgentChatService drops it from the model payload in that case).
                    case DOCUMENT ->
                            sandboxData
                                    || modelModalitiesService
                                            .resolve(agent.getProviderName(), effectiveModel)
                                            .document();
                    // Sandbox-only input: the model never sees the bytes, the code-execution tool
                    // does.
                    case SPREADSHEET -> true;
                };
        if (category == ChatAttachmentCategory.SPREADSHEET && !sandboxData) {
            // Platform flag off: xlsx is as unsupported as it was before the sandbox existed, so
            // say that - a user cannot switch the flag on. Flag on: the agent setting is the fix.
            throw new UnsupportedAttachmentTypeException(
                    sandboxDataEnabled()
                            ? "Spreadsheets are analysed in the code sandbox. "
                                    + sandboxRegistry.unavailableReason(agent.isE2bEnabled())
                                    + " Or remove the attachment."
                            : "Unsupported attachment type '"
                                    + file.getContentType()
                                    + "' - chat attachments must be an image, audio, video, or supported document (PDF, TXT, CSV, JSON) file");
        }
        if (!supportedByModel) {
            throw new UnsupportedAttachmentTypeException(
                    "This agent's model ("
                            + effectiveModel
                            + ") doesn't accept "
                            + category.name().toLowerCase(java.util.Locale.ROOT)
                            + " attachments in chat. Switch the agent to a model that supports it, or remove the attachment.");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UnsupportedAttachmentTypeException(
                    "Could not read uploaded file: " + e.getMessage());
        }

        // Shape is load-bearing, not cosmetic: AgentChatService only accepts
        // an attachmentKey back that matches KEY_PREFIX + <this agent's id> +
        // "/" + <a parseable UUID>. Changing this line without changing
        // AgentChatService#requireOwnAttachmentKey breaks every upload.
        String key = KEY_PREFIX + agentId + "/" + UUID.randomUUID();
        fileStorageService.put(key, content, file.getContentType());

        return new ChatAttachmentResponse(
                key, category, file.getContentType(), file.getOriginalFilename(), content.length);
    }

    private static boolean supportsCategory(
            ProviderModelResponse model, ChatAttachmentCategory category) {
        return switch (category) {
            case IMAGE -> model.supportsImageInput();
            case AUDIO -> model.supportsAudioInput();
            case VIDEO -> model.supportsVideoInput();
            // DOCUMENT never reaches this method - upload() routes it to
            // ModelModalitiesService instead (ProviderModelResponse has no
            // document flag). Present only to keep the switch exhaustive.
            case DOCUMENT, SPREADSHEET -> false;
        };
    }
}
