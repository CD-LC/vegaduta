/**
 * Keyframe generation for Clip Studio "Motion+" (tier V1).
 *
 * Generates 3–5 prompt-chained keyframes through the EXISTING server image
 * pipeline — no new backend surface. There is deliberately no direct
 * "generate an image from a prompt" REST endpoint for tenants (verified
 * 2026-08-05: core/imagegen exposes only the GET list/download routes plus the
 * anonymous /api/demo/image path): the platform's one authenticated,
 * quota-metered image-generation path is an agent chat turn whose model calls
 * the generate_image tool (GenerateImageToolCallback →
 * ImageGenerationOrchestratorService), which stores the result and replies
 * with an `/api/imagegen/images/<uuid>` link — the exact URL shape
 * MediaCard.tsx sniffs out of assistant prose today. This module drives that
 * same path once per keyframe over ONE chat session (the shared session
 * context is itself a coherence aid: the model sees its previous prompts),
 * extracts each newly-minted image id from the reply, and downloads the bytes
 * through the existing authenticated GET (api.downloadGeneratedImage).
 *
 * Every keyframe consumes one unit of the user's normal image quota
 * (ImageGenerationQuotaService) — the Motion+ UI states this plainly before
 * anything is generated ("Uses N image generations from your quota").
 *
 * Prompt chaining per the plan: the same style suffix on every frame,
 * progressive camera directions ("same scene, the camera has moved slightly
 * to the right…"), and explicit keep-everything-else-identical hints. Seed
 * hints are NOT sent: none of the orchestrator's clients (OpenAI BYOK /
 * Vertex BYOK / Gemini pool) exposes a seed parameter through this path, so
 * textual consistency hints are the supported mechanism.
 *
 * Failure posture: everything here throws descriptive
 * errors for the Motion+ surface to show calmly — nothing in this module
 * touches or degrades the hosted chat path or the V0 Ken Burns mode.
 */

import { api } from "../../api";

// ---------------------------------------------------------------------------
// Prompt chaining
// ---------------------------------------------------------------------------

export type CameraPath = "drift-right" | "push-in" | "pull-back";

export const CAMERA_PATHS: Array<{ id: CameraPath; label: string; description: string }> = [
  { id: "drift-right", label: "Drift right", description: "The camera slides across the scene" },
  { id: "push-in", label: "Push in", description: "The camera moves steadily closer" },
  { id: "pull-back", label: "Pull back", description: "The camera widens to reveal the scene" },
];

export const MIN_KEYFRAMES = 3;
export const MAX_KEYFRAMES = 5;

/** Applied to every frame so the set reads as one continuous shot. */
const STYLE_SUFFIX = "cinematic film still, rich lighting, consistent art style";

/** Progressive per-frame camera directions (index 0 is the establishing
 * frame and carries no direction). Kept gentle on purpose — small camera
 * steps interpolate cleanly; big jumps produce ghosting. */
const CAMERA_DIRECTIONS: Record<CameraPath, string[]> = {
  "drift-right": [
    "",
    "The camera has moved slightly to the right",
    "The camera has drifted a little further to the right",
    "The camera keeps drifting right, revealing a bit more of the scene",
    "The camera has drifted right once more",
  ],
  "push-in": [
    "",
    "The camera has pushed in slightly closer to the subject",
    "The camera is noticeably closer to the subject now",
    "The camera keeps pushing in toward the subject",
    "The camera is very close to the subject now",
  ],
  "pull-back": [
    "",
    "The camera has pulled back slightly",
    "The camera has pulled back further, revealing more of the surroundings",
    "The camera keeps pulling back from the scene",
    "The camera is wide now, showing the whole scene",
  ],
};

export function clampKeyframeCount(count: number): number {
  if (!Number.isFinite(count)) return MIN_KEYFRAMES;
  return Math.min(MAX_KEYFRAMES, Math.max(MIN_KEYFRAMES, Math.round(count)));
}

/**
 * The chat message sent for each keyframe. Exported for the UI's details
 * panel (honesty: the user can see exactly what will be asked) and for tests.
 */
export function buildKeyframeMessages(scenePrompt: string, count: number, path: CameraPath): string[] {
  const n = clampKeyframeCount(count);
  const scene = scenePrompt.trim();
  const directions = CAMERA_DIRECTIONS[path] || CAMERA_DIRECTIONS["drift-right"];
  const messages: string[] = [];
  for (let i = 0; i < n; i++) {
    if (i === 0) {
      messages.push(
        `Use your generate_image tool exactly once, with exactly this prompt: ` +
          `"${scene}, ${STYLE_SUFFIX}". ` +
          `Reply with only the generated image link — no commentary, no questions.`
      );
    } else {
      messages.push(
        `Generate the next keyframe of the same scene. Use your generate_image tool exactly once, ` +
          `with exactly this prompt: "Same scene as the previous image: ${scene}. ${directions[i]}. ` +
          `Everything else — style, palette, characters, lighting, composition — stays identical. ` +
          `${STYLE_SUFFIX}". Reply with only the new image link.`
      );
    }
  }
  return messages;
}

// ---------------------------------------------------------------------------
// Generation
// ---------------------------------------------------------------------------

export interface GeneratedKeyframe {
  /** The server-side generated-image id (GeneratedImageController). */
  imageId: string;
  /** The downloaded image bytes, ready for decode. */
  blob: Blob;
}

export interface GenerateKeyframesOptions {
  /** Agent whose chat (and generate_image tool) does the generating. */
  agentId: string | number;
  scenePrompt: string;
  /** 3..5 — clamped. */
  frameCount: number;
  cameraPath: CameraPath;
  /** Called as each frame STARTS: (frameNumber 1-based, total). */
  onProgress?: (frameNumber: number, total: number) => void;
  /** Checked between chat turns — an in-flight turn cannot be interrupted
   * (streamChat carries no abort signal), so cancellation lands at the next
   * frame boundary. */
  signal?: AbortSignal;
}

/** Matches the artifact-download URL shape GenerateImageToolCallback embeds
 * in replies (same shape MediaCard.tsx's sniffer recognises). */
const IMAGE_URL_RE = /\/api\/imagegen\/images\/([0-9a-fA-F][0-9a-fA-F-]{7,})/g;

function extractNewImageId(replyText: string, seen: Set<string>): string | null {
  for (const match of replyText.matchAll(IMAGE_URL_RE)) {
    const id = match[1];
    // Skip ids we already collected — session context makes the model prone
    // to echoing an earlier frame's link alongside the new one.
    if (!seen.has(id)) return id;
  }
  return null;
}

function throwIfAborted(signal?: AbortSignal): void {
  if (signal?.aborted) {
    const err = new Error("Keyframe generation was cancelled.");
    err.name = "AbortError";
    throw err;
  }
}

/**
 * Generate the keyframe set. Sequential on purpose: each turn shares one chat
 * session so the model keeps its own previous prompt in context, and the
 * server pipeline is not fanned out. Throws on the first frame that fails —
 * quota exhaustion (surfaced by the chat path's error contract), a reply with
 * no image link, or a failed download — with a message written for the UI.
 */
export async function generateKeyframes(options: GenerateKeyframesOptions): Promise<GeneratedKeyframe[]> {
  const { agentId, scenePrompt, frameCount, cameraPath, onProgress, signal } = options;
  const messages = buildKeyframeMessages(scenePrompt, frameCount, cameraPath);
  const total = messages.length;
  const seen = new Set<string>();
  const frames: GeneratedKeyframe[] = [];
  let sessionId: string | null = null;

  for (let i = 0; i < total; i++) {
    throwIfAborted(signal);
    onProgress?.(i + 1, total);

    let replyText = "";
    let newSessionId: string | null = null;
    try {
      // Threading `signal` through as streamChat's new `lifecycle.signal`
      // closes a real gap: previously this loop's own cancellation only
      // took effect BETWEEN frames (the throwIfAborted calls above/below) -
      // a hang or a cancel DURING one streamChat call had no way to reach
      // it at all. Now it aborts the in-flight request immediately, same as
      // every other lifecycle-covered failure mode (see requestLifecycle.ts).
      newSessionId = await api.streamChat(
        agentId,
        messages[i],
        sessionId,
        (chunk: string) => {
          replyText += chunk;
        },
        undefined,
        undefined,
        undefined,
        undefined,
        { signal }
      );
    } catch (err: any) {
      // Chat-path errors already arrive user-worded (quota, provider down…).
      throw new Error(
        `Keyframe ${i + 1} of ${total} failed: ${err?.message || "the image request didn't go through."}`
      );
    }
    if (newSessionId) sessionId = newSessionId;
    throwIfAborted(signal);

    const imageId = extractNewImageId(replyText, seen);
    if (!imageId) {
      throw new Error(
        `The agent didn't return an image for keyframe ${i + 1} of ${total}. ` +
          `Make sure the selected agent has the generate_image tool enabled, then try again.`
      );
    }
    seen.add(imageId);

    let blob: Blob;
    try {
      const downloaded = await api.downloadGeneratedImage(imageId);
      blob = downloaded.blob;
    } catch (err: any) {
      throw new Error(
        `Keyframe ${i + 1} of ${total} was generated but couldn't be downloaded: ` +
          `${err?.message || "download failed."}`
      );
    }
    frames.push({ imageId, blob });
  }

  return frames;
}
