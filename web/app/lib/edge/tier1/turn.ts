// Turn execution - the propose/execute tool loop. Split out of ../tier1.ts.

import type { ChatCompletionMessageParam } from "@mlc-ai/web-llm";
import { fetchEdgeMemory, memoryFactsBlock } from "../edgeMemory";
import { findLocalTool, listLocalTools, runLocalTool, type LocalTool } from "../localTools";
import {
  canUseServerTools,
  edgeToolsUnavailable,
  executeEdgeTool,
  fetchEdgeTools,
  type EdgeToolDefinition,
  type EdgeToolExecuteResult,
} from "../toolBridge";
import { edgePrompt, edgePromptOrNull } from "../edgePrompts";
import { acquireEngine } from "./engineAcquire";
import { Tier1Error } from "./errors";
import { TIER1_TURN_CEILING_MS, streamCompletion } from "./generation";
import { logTier1Event } from "./log";
import {
  TIER1_OUTPUT_RESERVE_TOKENS,
  TOKEN_ESTIMATE_CHARS_PER_TOKEN,
  assembleTier1Prompt,
  assembleTier1PromptWithToolRelief,
  type Tier1PromptAssembly,
} from "./promptBudget";
import type { Tier1SessionState, Tier1ToolCallEvent } from "./sessionTypes";
import { parseToolCall, toolProtocolInstructions } from "./toolProtocol";

const MAX_TOOL_ROUNDS = 3;
/** History cap per session (excludes the system message) — bounds prompt
 * growth on long conversations. */
const MAX_HISTORY_MESSAGES = 24;

/**
 * Quality pass (2026-08-06): small local models (1B-3B class) get NO chain-of-thought scaffolding without a short,
 * honest nudge (never a fake "thinking" tag, never claiming a reasoning mode the model does not have). It costs a
 * handful of tokens and measurably helps small instruct models avoid one-line non-answers on anything multi-step.
 *
 * The wording is not here: the nudge and the "no system prompt" fallback are the registry keys
 * `edge.reasoning_nudge` and `edge.default_system_prompt` (edgePrompts.ts), so an admin can change them for edge
 * turns on the instructions console, with the shipped text as the offline default.
 *
 * Exported so router/attempts.ts applies the exact same nudge to tier 0 (Chrome built-in) session creation - one
 * nudge, one source of truth, rather than two copies drifting apart.
 */
export function withReasoningNudge(basePrompt: string | undefined): string {
  const nudge = edgePromptOrNull("edge.reasoning_nudge");
  return (basePrompt || edgePrompt("edge.default_system_prompt")) + (nudge ? `\n\n${nudge}` : "");
}

/**
 * Ceiling on how much of ONE tool result is allowed back into the prompt
 * (2026-09-12). A server tool result was previously spliced in whole: a
 * single file read or search result can be tens of kilobytes, and it lands
 * in a context window this file's own D-9 budget sizes at a few thousand
 * tokens — so one fat result could push the assembled prompt past
 * `assembly.fits` and turn a working tool round into a
 * "context-window-exceeded" failure of the whole turn.
 *
 * Derived from constants already in this file rather than picked: one tool
 * result may claim at most what the file already holds back for the entire
 * reply (TIER1_OUTPUT_RESERVE_TOKENS), converted through the same
 * chars-per-token estimator the budget uses. That is 1024 × 3.5 = 3584
 * characters today; it moves automatically if either constant does. It is a
 * ceiling, not a target — most results are far shorter and pass through
 * untouched.
 */
const MAX_TOOL_RESULT_CHARS = Math.floor(TIER1_OUTPUT_RESERVE_TOKENS * TOKEN_ESTIMATE_CHARS_PER_TOKEN);

/** Trim one tool result to MAX_TOOL_RESULT_CHARS, telling the MODEL that the
 * trim happened (silently truncating would let it reason confidently about a
 * result it only saw the front of). The registry caps its own local results
 * as well; this is the cap for everything that reaches the prompt, so a
 * server result — which nothing capped before — is covered too. */
function capToolResultForPrompt(result: string): string {
  if (!result || result.length <= MAX_TOOL_RESULT_CHARS) return result;
  return (
    `${result.slice(0, MAX_TOOL_RESULT_CHARS)}\n\n[Result truncated here — it was ` +
    `${result.length} characters, and only the first ${MAX_TOOL_RESULT_CHARS} fit this ` +
    `model's context. Say so if the answer depends on the part you cannot see.]`
  );
}

/** Identity of a proposed tool call, for the repeat check in runTurn. Two
 * calls are "the same" when the model asked for the same tool with arguments
 * that serialize identically; JSON.stringify is key-order sensitive, which
 * makes this conservative in the safe direction (a re-ordered re-ask reads as
 * different and is allowed to run). Never throws — an unserializable argument
 * object yields a marker that simply never matches. */
function toolCallSignature(name: string, args: unknown): string {
  try {
    return JSON.stringify([name, args ?? {}]);
  } catch {
    return JSON.stringify([name, `<unserializable-${Math.random()}>`]);
  }
}

/** A broken onToolCall listener must never break the turn it's only
 * observing — same "never let a UI concern affect the turn" rule
 * toolBridge.ts's memoryWriteListeners already follows. */
function safeNotifyToolCall(
  onToolCall: ((event: Tier1ToolCallEvent) => void) | undefined,
  event: Tier1ToolCallEvent
): void {
  if (!onToolCall) return;
  try {
    onToolCall(event);
  } catch {
    // Best-effort — the turn continues regardless.
  }
}

export async function runTurn(
  state: Tier1SessionState,
  text: string,
  onToken?: (delta: string) => void,
  signal?: AbortSignal,
  onToolCall?: (event: Tier1ToolCallEvent) => void
): Promise<string> {
  const engine = await acquireEngine(state.model);
  // Wall-clock ceiling for the WHOLE turn (see TIER1_TURN_CEILING_MS) — spans
  // every tool round below, not just one streamCompletion call.
  const turnStartedAt = Date.now();

  // ---- Tool definitions: local-first union (2026-09-12) ------------------
  //
  // Before this, the whole tool list was whatever fetchEdgeTools returned,
  // and fetchEdgeTools needs a bearer token — so an account-less visitor
  // running a real model on their own GPU got an empty list on every turn,
  // forever. Local tools are in-page handlers with no server, no account and
  // no quota behind them, so they are offered unconditionally; the server
  // list is fetched ONLY when canUseServerTools says this caller could
  // actually reach POST /api/edge/tools/execute, because offering names that
  // will 401 on use teaches the model tools it cannot have.
  //
  // Locals go first in the union so a server tool can never displace one in
  // the prompt's tool block; the registry's mandatory "local_" prefix is what
  // makes the two namespaces unable to collide in the first place (same
  // posture webmcp.ts already uses with "vegaduta_").
  //
  // disableTools (CreateTier1SessionOptions): none of the three lookups run.
  // `tools` stays null, and every tool behaviour below already keys off that
  // — toolsActive is false on every round, so streamCompletion sets no
  // response_format and streams without hold-back, and parseToolCall is never
  // reached.
  const localTools: LocalTool[] = state.disableTools ? [] : listLocalTools(state.caps);
  let serverTools: EdgeToolDefinition[] | null = null;
  if (
    !state.disableTools &&
    !state.serverToolsDisabled &&
    !edgeToolsUnavailable() &&
    canUseServerTools(state.agentId)
  ) {
    serverTools = await fetchEdgeTools(state.agentId);
    if (!serverTools || serverTools.length === 0) serverTools = null;
  }
  // Null, not an empty array, when the union is empty — every downstream
  // branch below already keys off `!!tools`, so an empty union keeps exactly
  // today's behaviour: no tool block in the prompt, no tool parsing, plain
  // chat. That is the honest state for a device with no local tools available
  // and no server reachable.
  const tools: EdgeToolDefinition[] | null =
    localTools.length > 0 || serverTools !== null ? [...localTools, ...(serverTools || [])] : null;

  // E-6 (EDGE-AI-STRATEGY.md): the agent's remembered facts, so an edge turn
  // is not answering with less context than the identical hosted turn would
  // have. Fetched once per session and cached (see edgeMemory.ts for why it is
  // deliberately NOT per-turn/relevance-ranked), awaited alongside the tool
  // fetch above rather than before it so a slow memory endpoint cannot delay
  // the turn any more than the tool list already does.
  //
  // Ordering inside the prompt matters: facts go BEFORE the tool protocol
  // block. toolProtocolInstructions ends with the strict output-format
  // contract the parser depends on, and a small model follows the LAST
  // instruction it read most reliably - putting prose after it is how the
  // envelope starts getting malformed.
  const memoryFacts = state.disableTools ? null : await fetchEdgeMemory(state.agentId);

  // (Re)build the system message every turn: the tool list can appear when
  // the server deploys mid-session, and vanish when it 404s.
  const systemContent =
    withReasoningNudge(state.baseSystemPrompt) +
    memoryFactsBlock(memoryFacts) +
    (tools ? toolProtocolInstructions(tools) : "");
  const system: ChatCompletionMessageParam = { role: "system", content: systemContent };

  state.messages.push({ role: "user", content: text });
  // D-9: everything from here on in state.messages belongs to the CURRENT
  // turn (the user message now, tool envelopes/results as rounds append
  // them) — irreducible for budgeting; only messages BEFORE this index are
  // trimmable history.
  const turnStartIndex = state.messages.length - 1;

  let finalText = "";
  /** Signature of the tool call the PREVIOUS round dispatched — the repeat
   * guard below compares against it. Per-turn, not per-session: asking the
   * same tool the same thing again in a later turn is legitimate. */
  let lastToolCallSignature: string | null = null;
  /** The last successful tool result, already capped for the prompt — reused
   * verbatim by the repeat guard's message so a person who hits it still sees
   * what the tool actually returned. */
  let lastToolResultForPrompt: string | null = null;
  for (let round = 0; round <= MAX_TOOL_ROUNDS; round++) {
    if (signal?.aborted) throw new Tier1Error("aborted", "On-device reply was stopped.");
    if (Date.now() - turnStartedAt >= TIER1_TURN_CEILING_MS) {
      throw new Tier1Error(
        "turn-ceiling-exceeded",
        "This reply is taking much longer than expected and was stopped.",
        "turn-ceiling-exceeded"
      );
    }
    // Tool calls are only honoured while rounds remain; the last pass runs
    // with the plain system prompt so the model must answer in text.
    // The server half going unavailable mid-turn now only ends tool use when
    // there are no local tools to carry on with — dispatch below refuses
    // server names from that point regardless.
    let toolsActive =
      !!tools && !(state.serverToolsDisabled && localTools.length === 0) && round < MAX_TOOL_ROUNDS;
    const plainSystem: ChatCompletionMessageParam = {
      role: "system",
      content: withReasoningNudge(state.baseSystemPrompt),
    };
    // D-9 windowed assembly (replaces the old flat slice(-MAX_HISTORY_MESSAGES),
    // which is kept as the outer bound before token budgeting): system + the
    // current turn are irreducible, prior history trims oldest-first in whole
    // turns. Re-assembled every round because tool envelopes/results grow the
    // current turn. state.messages itself is never mutated here.
    const historyWindow = state.messages.slice(0, turnStartIndex).slice(-MAX_HISTORY_MESSAGES);
    const currentTurnMessages = state.messages.slice(turnStartIndex);
    let assembly: Tier1PromptAssembly;
    if (toolsActive) {
      // Tool relief (see assembleTier1PromptWithToolRelief): an overflow
      // caused by the tool protocol block alone degrades to a tool-less
      // on-device turn instead of a hosted fallthrough.
      const relief = assembleTier1PromptWithToolRelief(
        system,
        plainSystem,
        historyWindow,
        currentTurnMessages,
        state.contextWindowSize
      );
      if (relief.toolsDropped) {
        logTier1Event({
          phase: "context-budget-tools-dropped",
          modelId: state.model.id,
          agentId: state.agentId,
          budgetTokens: relief.assembly.budgetTokens,
          contextWindowSize: state.contextWindowSize,
        });
        toolsActive = false;
      }
      assembly = relief.assembly;
    } else {
      assembly = assembleTier1Prompt(
        plainSystem,
        historyWindow,
        currentTurnMessages,
        state.contextWindowSize
      );
    }
    if (!assembly.fits) {
      // Irreducible prompt over budget: NEVER attempt local inference (the
      // engine would throw after burning latency). The router maps this code
      // to the honest "context-window-exceeded" fallthrough (or the edge-only
      // refusal) and does NOT trip the circuit breaker for it.
      logTier1Event({
        phase: "context-budget-exceeded",
        modelId: state.model.id,
        agentId: state.agentId,
        estimatedPromptTokens: assembly.estimatedPromptTokens,
        budgetTokens: assembly.budgetTokens,
        contextWindowSize: state.contextWindowSize,
      });
      throw new Tier1Error(
        "context-window-exceeded",
        "This request is too large for the on-device model's context window.",
        `irreducible prompt ~${assembly.estimatedPromptTokens} tokens over budget ` +
          `${assembly.budgetTokens} (window ${state.contextWindowSize})`
      );
    }
    const messages = assembly.messages;
    const remainingBudgetMs = Math.max(1, TIER1_TURN_CEILING_MS - (Date.now() - turnStartedAt));
    let reply: string;
    try {
      reply = await streamCompletion(engine, messages, toolsActive, onToken, signal, remainingBudgetMs);
    } catch (err) {
      // A timeout that fired because the ceiling-derived budget (not the
      // normal idle window) was the binding constraint should surface as
      // "turn-ceiling-exceeded", not "generation-timeout" — determined here
      // by checking whether the ceiling has actually elapsed, since
      // streamCompletion only ever throws "generation-timeout" for both
      // causes (it has no ceiling awareness of its own).
      if (
        err instanceof Tier1Error &&
        err.code === "generation-timeout" &&
        Date.now() - turnStartedAt >= TIER1_TURN_CEILING_MS
      ) {
        throw new Tier1Error(
          "turn-ceiling-exceeded",
          "This reply is taking much longer than expected and was stopped.",
          "turn-ceiling-exceeded"
        );
      }
      throw err;
    }
    const call = toolsActive ? parseToolCall(reply) : null;
    if (!call) {
      finalText = reply;
      break;
    }
    // The model proposed a tool call — record it, execute server-side, and
    // feed the result back for the next round. The envelope text never
    // reaches the UI (streamCompletion held it back) — onToolCall is what
    // fills that silence now (AG-UI-inspired visibility, see
    // Tier1ToolCallEvent's doc comment above).
    state.messages.push({ role: "assistant", content: reply });

    // Repeat guard: a small model that gets an unhelpful result often re-emits
    // the byte-identical envelope, which under the old loop just burned every
    // remaining round on the same call before failing with nothing to show.
    // Checked BEFORE dispatch, so the duplicate is never executed at all.
    const signature = toolCallSignature(call.name, call.arguments);
    if (signature === lastToolCallSignature) {
      logTier1Event({
        phase: "tool-call-repeat",
        modelId: state.model.id,
        agentId: state.agentId,
        toolName: call.name,
        round,
      });
      finalText =
        `I kept asking for the same "${call.name}" call with identical arguments, so I stopped ` +
        `instead of repeating it.` +
        (lastToolResultForPrompt
          ? `\n\nHere is what it returned the first time:\n\n${lastToolResultForPrompt}`
          : "");
      break;
    }
    lastToolCallSignature = signature;

    // DISPATCH. Local first, by name: a local tool runs in this page and
    // nothing leaves the device. Only a name that is NOT local reaches
    // executeEdgeTool, and only when a server tool list was actually fetched
    // for this turn — so a hallucinated name from an account-less turn costs
    // no HTTP request, it takes the synthesized not-found branch below, which
    // is the same `ok:false` shape the server's own not-allowlisted rejection
    // produces and lands in the same existing failure path.
    //
    // Resolved against the list this TURN actually offered, not the whole
    // registry: listLocalTools applies the registry's capability gates and
    // findLocalTool deliberately does not (its own doc comment says so and
    // tells the loop to do exactly this), so a gated-off tool must not become
    // runnable because the model guessed its name. findLocalTool is still
    // consulted — only to tell "this device can't do that" apart from "no such
    // tool", which are different honest answers.
    const local = localTools.find((t) => t.name === call.name) || null;
    const gatedOffLocal = local === null && findLocalTool(call.name) !== null;
    // "local" covers the gated-off case too: nothing was sent anywhere for it
    // either. The remaining "server" label is therefore the only value that
    // ever means bytes left this device.
    const source: Tier1ToolCallEvent["source"] =
      local !== null || gatedOffLocal || serverTools === null ? "local" : "server";
    safeNotifyToolCall(onToolCall, {
      name: call.name,
      phase: "calling",
      arguments: call.arguments,
      round,
      source,
    });
    // Both branches resolve the SAME EdgeToolExecuteResult union (runLocalTool
    // is typed to it by the registry contract; executeEdgeTool has always
    // returned it), which is why every consumer below this line — the ok
    // check, the `failure` cast, the two message shapes, the logging — is
    // unchanged by local dispatch.
    let outcome: EdgeToolExecuteResult;
    if (local !== null) {
      outcome = await runLocalTool(local, call.arguments);
    } else if (gatedOffLocal) {
      outcome = {
        ok: false,
        code: "rejected",
        detail: `"${call.name}" is a local tool this browser or device cannot run, so it was not offered this turn`,
      };
    } else if (serverTools !== null && !state.serverToolsDisabled) {
      outcome = await executeEdgeTool(call.name, call.arguments, state.agentId, state.sessionId, "chat");
    } else {
      outcome = {
        ok: false,
        code: "rejected",
        detail: `there is no tool named "${call.name}" available here`,
      };
    }
    if (signal?.aborted) throw new Tier1Error("aborted", "On-device reply was stopped.");
    if (outcome.ok === true) {
      safeNotifyToolCall(onToolCall, {
        name: call.name,
        phase: "done",
        arguments: call.arguments,
        round,
        source,
      });
      const resultForPrompt = capToolResultForPrompt(outcome.result) || "(empty result)";
      lastToolResultForPrompt = resultForPrompt;
      state.messages.push({
        role: "user",
        content:
          `[Tool "${call.name}" result]\n${resultForPrompt}\n\n` +
          `Use this result to answer my previous message. Reply in plain text.`,
      });
    } else {
      // Explicit cast: with this tsconfig's strict:false, TS doesn't narrow
      // the discriminated union through the ok check above.
      const failure = outcome as Extract<EdgeToolExecuteResult, { ok: false }>;
      safeNotifyToolCall(onToolCall, {
        name: call.name,
        phase: "error",
        arguments: call.arguments,
        round,
        source,
      });
      // Observability (issue 9): a failed edge tool call previously left no
      // trace anywhere client-side — the model just silently got told to try
      // again. Console-only, same posture as the rest of this file's
      // logTier1Event calls (never a network call, never gates behavior).
      logTier1Event({
        phase: "tool-call-failed",
        modelId: state.model.id,
        agentId: state.agentId,
        toolName: call.name,
        code: failure.code,
        detail: failure.detail,
      });
      if (failure.code === "unavailable") {
        // Server-surface-only kill (see the field's doc comment) — local
        // tools are unaffected and stay offered for the rest of the session.
        state.serverToolsDisabled = true;
        state.messages.push({
          role: "user",
          content: `[Tool "${call.name}" is not available] Answer my previous message directly, without tools.`,
        });
      } else {
        state.messages.push({
          role: "user",
          content:
            `[Tool "${call.name}" failed: ${failure.detail}] ` +
            `Answer my previous message as best you can — retry a different tool only if it clearly helps.`,
        });
      }
    }
  }

  if (!finalText.trim()) {
    // Loop exhausted without a text answer — the router treats empty as
    // failure and falls through to hosted; make that explicit.
    throw new Tier1Error("prompt-failed", "The on-device model could not finish this reply.");
  }
  state.messages.push({ role: "assistant", content: finalText });
  // Bound stored history (the slice above bounds the prompt; this bounds
  // memory growth on very long sessions).
  if (state.messages.length > MAX_HISTORY_MESSAGES * 2) {
    state.messages = state.messages.slice(-MAX_HISTORY_MESSAGES);
  }
  return finalText;
}
