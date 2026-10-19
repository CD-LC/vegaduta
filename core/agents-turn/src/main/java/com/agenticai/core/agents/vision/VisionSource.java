package com.agenticai.core.agents.vision;

/**
 * What a vision session is looking at (shared live-vision contract): the
 * user's webcam or a shared screen/tab. Mirrors the lowercase per-frame
 * {@code ChatInlineAttachment.source} values ("camera"/"screen") at the
 * session level - the session records which capture surface the user
 * consented to.
 */
public enum VisionSource {
    CAMERA,
    SCREEN
}
