// Tier-1 session creation and engine prewarm. Split out of ../tier1.ts.

import {
  beginEdgeActivity,
  edgeEnabled,
  endEdgeActivity,
  type EdgeCapabilities,
  type ManifestModel,
} from "../capabilities";
import {
  ENGINE_ACQUIRE_TIMEOUT_MS,
  acquireEngine,
  resolveTier1ContextWindow,
  withEngineLoadDeadline,
} from "./engineAcquire";
import { scheduleTier1IdleTeardown, tier1Engine } from "./engineState";
import { Tier1Error, toTier1Error } from "./errors";
import { logTier1Event } from "./log";
import { getTier1RunModel } from "./readiness";
import type { CreateTier1SessionOptions, Tier1Session, Tier1SessionState } from "./sessionTypes";
import { runTurn } from "./turn";

/**
 * PREWARM (latency audit fix #1, 2026-08-06): warm an ALREADY-downloaded
 * tier-1 engine the moment an edge-enabled agent is opened/selected — idle
 * time — instead of paying the full engine-load cost synchronously in front
 * of the person's first message (the previous only call site for this same
 * load path was inside createTier1Session below, reached only from
 * sendViaEdge's first send). Reuses acquireEngine + withEngineLoadDeadline
 * unchanged — this function is a thin, bounded, fire-and-forget wrapper
 * around the exact same load path createTier1Session already uses, now also
 * benefiting from the Service-Worker-first path above.
 *
 * Fire-and-forget by contract: returns void, never a Promise a caller could
 * be tempted to await on a render path. Never triggers a download —
 * getTier1RunModel (see its own doc comment) only ever resolves to a model
 * whose weights are already verified present on this device; a device with
 * no ready tier-1 model resolves null and this is a complete no-op. Never
 * throws past this function — any failure here is silently absorbed and the
 * normal first-message load (still bounded, still typed-error) is what the
 * user-visible turn actually depends on.
 *
 * Warm-up problem fix (2026-08-07): call sites expanded beyond the original
 * agent-mount effect — IntelligenceSourceControl now calls this right after
 * both selectOnDevice() and the installed-model row's onClick (previously
 * those only flipped routing/model state and left the engine cold until the
 * first send), AgentPlayground's mount effect now also re-fires on
 * EDGE_PREFS_EVENT (a preference change from another surface, e.g. the /edge
 * marketplace page), and ChatPanel gained a mount-time effect it never had.
 * `logContext` is opt-in, log-correlation only (never gates behavior) — it
 * lets the structured log line below attribute a warm-up to the
 * agent/session that triggered it, matching reportEdgeTurn's agentId usage
 * in tierRouter.ts, without adding a hard dependency on any caller passing it.
 */
export function prewarmTier1Engine(caps: EdgeCapabilities, logContext?: string | null): void {
  if (typeof window === "undefined" || !edgeEnabled()) return;
  // Verify-pass fix (critical finding, precision fix on second verify pass):
  // a selection/preference change (e.g. pinning a different model in
  // IntelligenceSourceControl) can fire this while a turn is actively
  // streaming on the CURRENT engine. Warming a *different* model right now
  // would mean reloading the live engine mid-generation. Checks
  // tier1Engine.readerCount (precise: only nonzero mid-stream) rather than the
  // broader per-turn getEdgeActivity() — this is purely a cheap early-exit
  // to avoid wasted work while a stream is genuinely in flight;
  // acquireEngine's own waitForEdgeActivityIdle is the real, independent
  // safeguard for the reload path itself either way.
  if (tier1Engine.readerCount > 0) {
    logTier1Event({ phase: "prewarm", outcome: "skipped-active-turn", agentId: logContext ?? null });
    return;
  }
  const startedAt = Date.now();
  void (async () => {
    // Hoisted above the try (rather than `const model` scoped inside it) so
    // the catch block below can still attribute a failure to the model that
    // was being warmed — 2026-08-10 audit finding #13's telemetry fix needs
    // this; it was previously out of scope for the catch entirely.
    let model: ManifestModel | null = null;
    try {
      model = await getTier1RunModel(caps);
      if (!model) {
        logTier1Event({
          phase: "prewarm",
          outcome: "no-ready-model",
          agentId: logContext ?? null,
          durationMs: Date.now() - startedAt,
        });
        return; // no ready tier-1 model on this device — no-op, no download
      }
      const alreadyWarm = tier1Engine.instance !== null && tier1Engine.modelId === model.id;
      await withEngineLoadDeadline(acquireEngine(model), ENGINE_ACQUIRE_TIMEOUT_MS);
      logTier1Event({
        phase: "prewarm",
        outcome: alreadyWarm ? "already-warm" : "warmed",
        modelId: model.id,
        agentId: logContext ?? null,
        durationMs: Date.now() - startedAt,
      });
    } catch (err) {
      // Best-effort only — the real load attempt at first-message time
      // (createTier1Session below) goes through the normal typed-error path
      // regardless of whether this prewarm succeeded, failed, or never ran.
      //
      // 2026-08-10 audit finding #13: the pre-existing shape here already had
      // outcome/duration/(agent)id — what it was missing for any future
      // fleet-wide telemetry pipeline was WHICH model failed and WHAT kind of
      // failure it was. modelId was available (via the now-hoisted `model`
      // variable above) but out of scope for this catch; `code` reuses
      // Tier1Error's own existing classification (the same
      // Tier1ErrorCode toTier1Error already assigns everywhere else in this
      // file — acquireEngine wraps its own failures in a Tier1Error, and
      // withEngineLoadDeadline's timeout constructs one directly) rather than
      // inventing a second taxonomy.
      logTier1Event({
        phase: "prewarm",
        outcome: "error",
        modelId: model?.id ?? null,
        code: err instanceof Tier1Error ? err.code : null,
        agentId: logContext ?? null,
        durationMs: Date.now() - startedAt,
        detail: err instanceof Error ? err.message : String(err),
      });
    }
  })();
}

/**
 * Create a tier-1 session bound to the best READY model. Throws Tier1Error
 * ("unavailable") when no downloaded model can serve — the router treats
 * that as fall-through, exactly like tier-0's typed failures.
 */
export async function createTier1Session(
  caps: EdgeCapabilities,
  options: CreateTier1SessionOptions = {}
): Promise<Tier1Session> {
  if (typeof window === "undefined" || !edgeEnabled()) {
    throw new Tier1Error("unsupported", "On-device mode only runs in a browser.");
  }
  // Same signal the router already used to decide which model it EXPECTED to
  // run (see CreateTier1SessionOptions.turnComplexity) — passing it here is
  // what makes the two selections agree, and what makes selectBestModel's
  // complexity branches reachable from the session that actually serves the
  // turn. The selection logic itself is untouched.
  const model = await getTier1RunModel(caps, options.turnComplexity);
  if (!model) {
    throw new Tier1Error("unavailable", "No on-device model is ready on this device.");
  }
  // Load (from cache) up front so the first prompt doesn't pay the warm-up
  // silently — progress shows as the chip's "warming up" state. Bounded
  // (see withEngineLoadDeadline above) so a stalled WebGPU load fails fast
  // instead of hanging this call — and every caller above it — forever.
  await withEngineLoadDeadline(
    acquireEngine(model),
    options.engineAcquireTimeoutMs ?? ENGINE_ACQUIRE_TIMEOUT_MS
  );

  const state: Tier1SessionState = {
    model,
    disableTools: options.disableTools === true,
    messages: [],
    baseSystemPrompt: options.systemPrompt || "",
    serverToolsDisabled: false,
    sessionId: options.sessionKey || null,
    agentId: options.sessionKey || null,
    caps,
    // Resolved AFTER acquireEngine above so the installed-record map (filled
    // at the dynamic-import sites) is already populated on the cold path.
    contextWindowSize: resolveTier1ContextWindow(model),
  };

  return {
    async prompt(text, onToken, signal, onToolCall) {
      // The "running on-device" indicator (capabilities.ts observable) —
      // active for the whole turn, cleared on success AND failure alike.
      beginEdgeActivity(1, state.model.id);
      try {
        if (signal?.aborted) throw new Tier1Error("aborted", "On-device reply was stopped.");
        return await runTurn(state, text, onToken, signal, onToolCall);
      } catch (err) {
        throw toTier1Error(err, "prompt-failed");
      } finally {
        endEdgeActivity();
        // A completed turn counts as "used" — pushes the idle-teardown clock
        // out from the load time to the last-activity time (see the timer's
        // own doc comment above).
        scheduleTier1IdleTeardown();
      }
    },
    destroy() {
      // Drop the conversation only — the engine (and its loaded weights)
      // stays for the next session; unloading would force a full re-warm.
      state.messages = [];
    },
  };
}
