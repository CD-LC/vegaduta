package com.agenticai.core.agents.modality;

/**
 * Multimodal-input profile for one provider+model pair: which attachment
 * kinds the model natively accepts on a chat turn. Resolved by {@link
 * ModelModalitiesService} (config override &gt; provider catalog &gt;
 * name-pattern heuristics - see that class for the honest limits of each
 * source). {@code document} today means "application/pdf as native chat
 * input" - the one document type mainstream multimodal chat APIs accept
 * directly.
 *
 * <p>The two limits are the platform-wide inline-attachment caps enforced by
 * {@code InlineAttachmentProcessor} and advertised verbatim to the web
 * Playground via {@code AgentModalitiesView} - a single source of truth so
 * the frontend's pre-flight checks and the backend's hard rejections can
 * never drift apart.
 */
public record ModelModalities(boolean image, boolean audio, boolean video, boolean document) {

    /** Text-only - the safe default for any model nothing recognizes. */
    public static final ModelModalities NONE = new ModelModalities(false, false, false, false);

    /** Max inline attachments per chat message (shared API contract with the web Playground). */
    public static final int MAX_ATTACHMENTS = 3;

    /** Max TOTAL decoded bytes across one message's inline attachments: 10 MB. */
    public static final long MAX_ATTACHMENT_TOTAL_BYTES = 10L * 1024 * 1024;

    public boolean any() {
        return image || audio || video || document;
    }
}
