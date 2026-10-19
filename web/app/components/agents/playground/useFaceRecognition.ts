"use client";

import { useRef, useState } from "react";
import { api } from "../../../lib/api";
import type { StagedFrame } from "../CameraPanel";
// Face recognition (2026-08-06) - "agent should remember who's in view if we
// add a photo in the knowledge base with that person's name." Gated on
// agent.biometricConsentEnabled.
import { matchFaceFromDataUrl, detectNameIntroduction, type FaceMatchCandidate } from "../../../lib/edge/face";
import type { PlaygroundAgent } from "./types";

/** On-device face recognition for camera turns: budget-capped matching, name splicing and automatic enrollment from "my name is ...". */
export function useFaceRecognition(agent: PlaygroundAgent | null) {
  // Face recognition (2026-08-06) - the enrolled-faces list for the current
  // agent/collection, fetched ONCE per session (not per-frame - the whole
  // point of doing matching client-side is that a live camera turn never
  // makes a network call for it) and cached here. Keyed so a change of
  // agent or resolved collection re-fetches rather than serving stale data.
  const faceCandidatesRef = useRef<{ key: string; candidates: FaceMatchCandidate[] } | null>(null);

  // Face recognition (2026-08-07) - "say my name, it remembers my face"
  // (Show-mode only for v1, see faceIntroEnrollFromShow's own comment for
  // why). Best-effort confirmation banner, cleared on the next send.
  const [faceIntroNotice, setFaceIntroNotice] = useState<{ tone: "success" | "info"; text: string } | null>(null);

  // Face recognition (2026-08-06) - resolves which knowledge collection this
  // agent's enrolled faces live in. No collection-picker UI in v1 (scope
  // decision, matches KnowledgePanel.tsx's own v1 cut): the agent's own
  // allowedKnowledgeCollectionIds restriction wins if set, otherwise falls
  // back to the tenant's first/default collection. Never throws.
  async function resolveFaceCollectionId() {
    if (Array.isArray(agent?.allowedKnowledgeCollectionIds) && agent.allowedKnowledgeCollectionIds.length > 0) {
      return agent.allowedKnowledgeCollectionIds[0];
    }
    try {
      const collections = await api.listCollections();
      return collections?.[0]?.id || null;
    } catch {
      return null;
    }
  }

  // Budget-capped, session-cached on-device face match. Returns the
  // recognized person's name, or null for "no match / not enrolled / not
  // opted in / matching took too long / anything failed" - every one of
  // those is the same "don't enrich this turn" outcome to the caller, never
  // an error surfaced to the user (this is a silent enhancement layered on
  // top of a camera turn, never a reason a camera turn can fail or stall -
  // same posture as vision.ts's own "never throw" contract). The underlying
  // matchFaceFromDataUrl call is NOT cancelled past the budget (face.ts
  // serializes on-device analysis one at a time, same as vision.ts's
  // analysisChain) - it simply stops being awaited.
  const FACE_MATCH_BUDGET_MS = 400;
  async function matchRecognizedFace(dataUrl: string): Promise<string | null> {
    if (agent?.biometricConsentEnabled !== true) return null;
    try {
      const collectionId = await resolveFaceCollectionId();
      if (!collectionId) return null;
      const key = `${agent.id}:${collectionId}`;
      let candidates = faceCandidatesRef.current?.key === key ? faceCandidatesRef.current.candidates : null;
      if (!candidates) {
        candidates = await api.listFaceEnrollments(agent.id, collectionId);
        faceCandidatesRef.current = { key, candidates };
      }
      if (!candidates || candidates.length === 0) return null;
      const result = await Promise.race<Awaited<ReturnType<typeof matchFaceFromDataUrl>> | null>([
        matchFaceFromDataUrl(dataUrl, candidates),
        new Promise<null>((resolve) => setTimeout(() => resolve(null), FACE_MATCH_BUDGET_MS)),
      ]);
      // `=== true`/`=== false` (not truthiness): this repo's strict:false
      // tsconfig only narrows a discriminated union on a literal comparison
      // (same gotcha documented in face.ts/vision.ts/tierRouter.ts).
      if (result && result.success === true && result.matched === true) {
        return result.personName;
      }
      return null;
    } catch {
      return null;
    }
  }

  /** Splices a recognized name into a camera-turn instruction/message, both
   * for the LLM call and (deliberately, for transparency) the transcript
   * display - so a reply that mentions someone by name is never a mystery
   * to the person watching. No-op when nobody was recognized. */
  function withRecognizedName(baseMessage: string, personName: string | null) {
    return personName ? `[Recognized: ${personName}] ${baseMessage}` : baseMessage;
  }

  /**
   * Face recognition (2026-08-07) - "if I say my name is Alex, it should
   * take a picture and remember my face; next time it should remember by
   * face." Fires from handleSend when a Show-mode message both (a) names a
   * person (detectNameIntroduction) and (b) has at least one staged frame
   * to enroll against.
   *
   * v1 scope, deliberately narrow - Show mode ONLY, not Watch mode, push-
   * to-talk, or a live LiveKit call: those paths don't currently have a
   * live camera frame available at the same point the user's words are
   * available (Watch's "instruction" is a standing text set once, not live
   * speech per turn; push-to-talk and LiveKit calls each acquire their own
   * camera/mic independently of CameraPanel today, with no shared frame to
   * enroll against). Extending this to those paths is real follow-up work,
   * not a small addition - flagged, not silently skipped.
   *
   * Consent: Agent.biometricConsentEnabled being on is treated as the
   * tenant-admin's already-given informed consent for this agent's
   * automatic enrollment (that flag's own gate already shows the privacy
   * explainer before it can be turned on) - re-confirming per utterance
   * would defeat "automatic," which is what was explicitly asked for.
   *
   * Fire-and-forget, best-effort, NEVER blocks or delays the actual chat
   * send this rides alongside - same "bonus step layered on top" posture
   * as KnowledgePanel's own enrollment integration.
   */
  async function tryEnrollFaceFromNameIntroduction(userMessage: string, sentFrames: StagedFrame[]) {
    if (agent?.biometricConsentEnabled !== true) return;
    if (!sentFrames || sentFrames.length === 0) return;
    const personName = detectNameIntroduction(userMessage);
    if (!personName) return;
    try {
      const { enrollFaceFromDataUrl, ensureFaceModel, isFaceModelCached } = await import("../../../lib/edge/face");
      if (!isFaceModelCached()) {
        await ensureFaceModel();
      }
      const result = await enrollFaceFromDataUrl(sentFrames[0].dataUrl);
      if (result.success !== true) return;
      const collectionId = await resolveFaceCollectionId();
      if (!collectionId) return;
      await api.enrollFace(agent.id, collectionId, null, personName, result.embedding, true);
      // Invalidate the session-cached candidate list (matchRecognizedFace's
      // own cache, keyed by agentId+collectionId) so the very next camera
      // turn in this session can recognize the face just enrolled, without
      // needing a reload.
      faceCandidatesRef.current = null;
      setFaceIntroNotice({ tone: "success", text: `Got it — I'll remember ${personName}'s face.` });
    } catch {
      // Best-effort only - never surfaces an error over this; the actual
      // chat send this rides alongside is what the user is waiting on.
    }
  }
  return { faceIntroNotice, setFaceIntroNotice, matchRecognizedFace, withRecognizedName, tryEnrollFaceFromNameIntroduction };
}
