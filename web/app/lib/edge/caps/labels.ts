// Human-readable labels for routing reasons + voice mime type. Split out of
// ../capabilities.ts, which re-exports every name.

/** Human-readable label for a tierRouter HostedFallthroughReason — shared by
 * every chat surface (AgentPlayground, ChatPanel) so "why did this run on
 * the platform despite on-device being selected" reads identically wherever
 * it's shown, instead of each surface inventing its own copy. Plain English,
 * not t() — same precedent as the rest of the edge UI. */
export function edgeFallbackLabel(reason: string | undefined): string {
  switch (reason) {
    case "edge-disabled":
      return "On-device is off for this deployment";
    case "user-hosted-override":
      return "You're set to always use the platform model";
    case "awaiting-user-choice":
      return "On-device hasn't been turned on yet";
    case "no-local-tier":
      return "This device/browser can't run a local model";
    case "aborted":
      return "On-device attempt was cancelled";
    // Wave 2 additions — EDGE-B/EDGE-C/EDGE-A (tierRouter's new reasons):
    case "policy-hosted":
      return "This agent is configured to always use the platform";
    case "edge-only-refused":
      return "This agent is configured to stay on-device, and this device can't serve the turn right now";
    case "complexity-hosted":
      return "This request looked too complex for the on-device model, so the platform answered";
    case "guardrails-hosted":
      return "This agent's guardrails need the platform's full safety checks";
    // D-9 (EDGE-CONTEXT-BUDGET-DESIGN.md): the irreducible prompt (system +
    // tools + current message) doesn't fit the on-device model's context
    // window even with all history trimmed — an input-shaped limit, not a
    // model-health failure.
    case "context-window-exceeded":
      return "This request is too large for the on-device model's context window, so the platform answered";
    // Live vision (S-edge) reason codes — analyzeFramesLocally's (vision.ts)
    // own kebab-case vocabulary, shared with AgentPlayground's separate
    // visionFallbackLabel (kept deliberately independent there — see that
    // function's own comment) so CallPanel's edge-sight failure banner can
    // also read a plain sentence instead of interpolating a raw code.
    case "worker-unsupported":
      return "This browser can't run on-device vision";
    case "no-frames":
      return "No usable frames were captured";
    case "model-not-ready":
      return "The on-device vision model isn't downloaded yet";
    case "warmup-failed":
      return "The on-device vision model failed to load";
    case "analysis-timeout":
      return "On-device analysis took too long";
    case "empty-vision-reply":
      return "The on-device model didn't return an answer";
    case "worker-failed":
      return "The on-device vision worker crashed";
    case "vision-failed":
    case "analysis-failed":
      return "On-device analysis hit an unexpected error";
    case "all-tiers-failed":
    default:
      return "On-device attempt failed, used the platform model instead";
  }
}

/** Compact (≤3-word) counterpart to edgeFallbackLabel above — shown inline,
 * always visible, next to the "Platform (edge skipped)" pill in ChatPanel and
 * AgentPlayground so the reason doesn't require a hover to see. The pill's
 * `title=` keeps the full sentence from edgeFallbackLabel for anyone who does
 * hover; this is just the compact always-on version of the same reason. */
export function edgeFallbackShortLabel(reason: string | undefined): string {
  switch (reason) {
    case "edge-disabled":
      return "edge off";
    case "user-hosted-override":
      return "you chose hosted";
    case "awaiting-user-choice":
      return "awaiting choice";
    case "no-local-tier":
      return "no local model";
    case "aborted":
      return "cancelled";
    // Wave 2 additions — see edgeFallbackLabel above for the full sentences.
    case "policy-hosted":
      return "agent policy: hosted";
    case "edge-only-refused":
      return "on-device only";
    case "complexity-hosted":
      return "needed a bigger model";
    case "guardrails-hosted":
      return "guardrails need hosted";
    // D-9 — see edgeFallbackLabel above for the full sentence.
    case "context-window-exceeded":
      return "too long for on-device";
    case "all-tiers-failed":
    default:
      return "local attempt failed";
  }
}

/** Voice-turn/speak audio's data-URI MIME type, keyed off the backend's own
 * "method" field (VoiceTurnAudioChunkEvent / SpeakResponse in core/voice —
 * both already carry it). "sarvam" audio is WAV; "elevenlabs" and any
 * future/unrecognized method (defensive default, so an older backend that
 * hasn't sent the field yet still plays back correctly) are treated as MP3.
 * Shared by CallPanel and AgentPlayground so both surfaces stop hardcoding
 * audio/mpeg regardless of which provider actually synthesized the clip. */
export function voiceAudioMimeType(method: string | undefined | null): string {
  return method === "sarvam" ? "audio/wav" : "audio/mpeg";
}
