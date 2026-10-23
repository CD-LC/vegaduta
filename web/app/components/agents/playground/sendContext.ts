import type { Dispatch, MutableRefObject, SetStateAction } from "react";
import type { useTranslations } from "../../../lib/i18n";
import type { RequestLifecycleController } from "../../../lib/requestLifecycle";
import type { StagedFrame } from "../CameraPanel";
import type { ChatAttachment, PlaygroundAgent, PlaygroundTurn, VisionFallbackOffer } from "./types";

/**
 * Everything the send pipeline (server turn, on-device turn, local frame
 * analysis, the composer's send handler) shares with the playground shell.
 * The shell builds one of these per render; each pipeline hook destructures
 * only what it reads, so the hooks keep the exact closure semantics the
 * single-file component had (always the latest render's values).
 */
export interface PlaygroundContext {
  agent: PlaygroundAgent | null;
  t: ReturnType<typeof useTranslations>;
  /** The agent the transcript currently belongs to; async continuations compare against it so a stale reply never lands on another agent's transcript. */
  activeAgentIdRef: MutableRefObject<string | undefined>;
  stickToBottomRef: MutableRefObject<boolean>;
  /** Request X-ray: snapshot ids already attributed to an earlier turn. */
  seenRequestIdsRef: MutableRefObject<Set<string>>;
  /** Client-minted stable id sequence per assistant turn (RL feedback keys on it). */
  turnSeq: MutableRefObject<number>;
  turns: PlaygroundTurn[];
  setTurns: Dispatch<SetStateAction<PlaygroundTurn[]>>;
  sessionId: string | null;
  setSessionId: (id: string | null) => void;
  sending: boolean;
  setSending: (busy: boolean) => void;
  setError: (message: string | null, opts?: { resumable?: boolean }) => void;
  input: string;
  setInput: Dispatch<SetStateAction<string>>;
  inputRef: MutableRefObject<HTMLTextAreaElement | null>;
  attachment: ChatAttachment | null;
  setAttachment: (a: ChatAttachment | null) => void;
  attaching: boolean;
  stagedFrames: StagedFrame[];
  setStagedFrames: (f: StagedFrame[]) => void;
  visionOnDevice: boolean;
  setVisionFallback: (offer: VisionFallbackOffer | null) => void;
  setFaceIntroNotice: (n: { tone: "success" | "info"; text: string } | null) => void;
  setChatController: (c: RequestLifecycleController<string | null> | null) => void;
  setEdgePhase: (p: "checking" | "warming" | "generating" | null) => void;
  setEdgeWarmingPercent: (p: number | null) => void;
  edgeAbortControllerRef: MutableRefObject<AbortController | null>;
  lastEdgeModelRef: MutableRefObject<string | null>;
  askToFallback: (message: string) => Promise<boolean>;
  edgeOptIn: boolean;
  matchRecognizedFace: (dataUrl: string) => Promise<string | null>;
  withRecognizedName: (baseMessage: string, personName: string | null) => string;
  tryEnrollFaceFromNameIntroduction: (userMessage: string, sentFrames: StagedFrame[]) => Promise<void>;
  /**
   * The run-scoped tool selection from the Run settings drawer, sent as `toolNames` with each hosted message: the
   * run may use only these of the agent's tools. `null`/absent = the agent's own tools (nothing is sent).
   */
  runToolNames?: string[] | null;
}
