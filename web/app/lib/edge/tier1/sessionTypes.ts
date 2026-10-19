// Public session/tool-event types for the tier-1 runtime. Split out of
// ../tier1.ts. Type-only - nothing here exists at runtime.

import type { ChatCompletionMessageParam } from "@mlc-ai/web-llm";
import type { EdgeCapabilities, ManifestModel, TurnComplexity, TurnComplexitySignal } from "../capabilities";

// AG-UI-inspired visibility (2026-09-09 — competitive research finding: an
// emerging 2026 pattern, standardized across LangGraph/CrewAI/Google ADK as
// the "Agent-User Interaction Protocol", streams an agent's intermediate
// tool activity to the frontend instead of leaving the user staring at a
// generic "thinking…" indicator through an entire tool round. Tier 1 already
// HAS this information (runTurn's tool loop below) — it just wasn't surfaced
// past routeTurn/createTier1Session's return value. This is purely additive:
// a new optional callback parameter alongside the existing onToken, ignored
// by any caller that doesn't pass one (Tier0Session.prompt below accepts and
// ignores it too, for structural parity — tier 0 never has tool access, see
// tierRouter.ts's tryTier0 comment on why).
export interface Tier1ToolCallEvent {
  name: string;
  phase: "calling" | "done" | "error";
  /** The arguments the model actually proposed, exactly as parseToolCall
   * produced them (unvalidated — `unknown`, because the envelope is model
   * output and this event is an observation of it, not a contract with it).
   * Added 2026-09-12: name+phase alone can't tell a person WHAT the model is
   * about to do, which is the whole point of showing a tool round at all —
   * "calling local_calculator" says nothing; the expression says everything.
   *
   * Optional, like the two fields below it, and deliberately so: this event
   * type is constructed in test and component code outside this module
   * (AgentActivityTrace.test.tsx builds bare {name, phase} events), and the
   * brief for this change is purely additive — a required field here would
   * be a compile break in files this wave does not own. Every event runTurn
   * emits sets all three. */
  arguments?: unknown;
  /** Zero-based tool round inside this turn (the same `round` the loop in
   * runTurn counts against MAX_TOOL_ROUNDS), so a UI can distinguish a first
   * attempt from a retry instead of rendering identical rows. */
  round?: number;
  /** Where the call was executed. "local" means an in-page handler ran and
   * nothing left the device; "server" means POST /api/edge/tools/execute.
   * Surfaced because the privacy difference between the two is the single
   * most load-bearing fact about a tool round, and until now the UI had no
   * way to tell them apart. */
  source?: "local" | "server";
}

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

/** Same caller-facing shape as Tier0Session. */
export interface Tier1Session {
  /** Stream a reply. onToken receives DELTAS; the resolved value is the full
   * reply. Abort via the signal → Tier1Error with code "aborted". onToolCall
   * (additive, optional — see Tier1ToolCallEvent's doc comment) fires once
   * per tool round with "calling", then "done"/"error" once the server-side
   * execute resolves; never fires at all for a turn with no tool call. */
  prompt(
    text: string,
    onToken?: (delta: string) => void,
    signal?: AbortSignal,
    onToolCall?: (event: Tier1ToolCallEvent) => void
  ): Promise<string>;
  destroy(): void;
}

export interface CreateTier1SessionOptions {
  /** Conversation identity — used as the agentId for both the edge tool list
   * (GET /api/edge/tools?agentId=…) and tool executions (the execute body's
   * top-level agentId field). The server generates its own session marker. */
  sessionKey?: string;
  systemPrompt?: string;
  /** Warm-up fix (2026-08-10 edge/WebLLM audit, finding #5): overrides the
   * default ENGINE_ACQUIRE_TIMEOUT_MS for this session's initial acquire
   * only. tierRouter passes a short bound when isTier1EngineWarm() already
   * shows the engine cold for this turn — a stalled load then fails over to
   * tier 0/hosted quickly instead of blocking the whole 60s ceiling on a
   * turn that was never going to be warm. Background prewarmTier1Engine is
   * untouched — it always uses the full ceiling. */
  engineAcquireTimeoutMs?: number;
  /** The turn-complexity signal for the turn that is causing this session to
   * be created (2026-09-12). Before this existed, routeTurn asked
   * getTier1RunModel WITH the signal to decide/report which model would run,
   * and then createTier1Session asked getTier1RunModel again with NO signal
   * to decide which model actually runs — so on a device with more than one
   * cached model the two answers could legitimately differ, and every
   * signal-dependent branch in selectBestModel (including the tool-tuned
   * preference isToolTunedModelId feeds) was unreachable from the path that
   * picks the model the person really talks to. Optional: omitted reads as
   * "simple", exactly the pre-existing behaviour. */
  turnComplexity?: TurnComplexity | TurnComplexitySignal;
  /** Plain-generation session (agentkit contract, 2026-09-19). When true,
   * every turn skips listLocalTools, fetchEdgeTools and fetchEdgeMemory, so:
   * no tool block (and no memory-facts block) enters the system prompt, no
   * structural_tag response_format is set on the request, and parseToolCall is
   * never applied to the reply — text that happens to look like a tool-call
   * envelope is streamed and returned verbatim. For callers that drive their
   * own tool protocol on top of this session and need the raw model text. */
  disableTools?: boolean;
}

export interface Tier1SessionState {
  model: ManifestModel;
  /** CreateTier1SessionOptions.disableTools, fixed for the session's life. */
  disableTools: boolean;
  messages: ChatCompletionMessageParam[];
  baseSystemPrompt: string;
  /** Set once the SERVER tool surface proved unusable for this session
   * (endpoint 404, 401, repeated failures). Renamed from `toolsDisabled`
   * 2026-09-12: it was only ever set by the server-execute "unavailable"
   * branch, and once local tools exist, killing the whole tool surface
   * because the SERVER is unreachable would take away the one set of tools
   * that never needed the server in the first place. Local tools are
   * deliberately not gated on it. */
  serverToolsDisabled: boolean;
  sessionId: string | null;
  agentId: string | null;
  /** The capabilities this session was created against — kept because
   * listLocalTools(caps) is per-turn (the tool block is rebuilt every round,
   * see runTurn) and runTurn has no other route to them. */
  caps: EdgeCapabilities;
  /** D-9: the window this session budgets against, resolved once at session
   * creation (manifest override → installed engine record → 4096). */
  contextWindowSize: number;
}
