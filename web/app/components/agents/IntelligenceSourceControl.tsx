"use client";

// "What drives this agent's intelligence" 3-way override.
//
// Flag-gated (edgeEnabled() — NEXT_PUBLIC_EDGE_AGENTS=1) the same way every
// other edge surface is (EdgeSetupChip, AgentPlayground's opt-in
// banner) — with the flag unset this renders nothing and nothing it touches
// runs.
//
// This does NOT invent a new backend concept. It is a single, honest, 3-way
// front-end onto two mechanisms that already exist and are already wired:
//   - Platform pool vs. your own provider is the EXISTING tenant-wide
//     `usePlatformLlm` toggle (ProvidersPanel.tsx's "Use platform LLM", and
//     AgentWorkbench's own `handleSwitchToByok`/`onPlatformLlm`) — this
//     control just gives it a symmetric "switch TO the platform pool"
//     counterpart and a clearly-labeled home next to the model picker.
//   - On-device is the EXISTING edge.optIn + edge.modelOverride pair
//     (capabilities.ts) that AgentPlayground's routeTurn() already honors —
//     this control is the same toggle EdgeSetupChip exposes globally,
//     surfaced here in agent-authoring context instead of only in the
//     header chip.
//
// A3 (2026-08-05) — this panel is now the in-context on-device MODEL surface:
//   - every manifest tier-1 model renders as an actionable row: downloaded
//     rows are immediately switchable (setModelOverride), not-yet-downloaded
//     rows are greyed with their size and a Download affordance that drives
//     the SHARED download pipeline (startTier1Download → downloadManager),
//     with inline progress; plus a link to the /edge marketplace page.
//   - the Chrome built-in model (tier 0) is a row too, with an explicit
//     "Set up" affordance when Chrome reports it downloadable (session
//     creation triggers Chrome's own download, progress via the monitor).
//   - "unavailable" is never a dead end any more: a device with WebGPU but
//     no downloaded model says exactly that, with the download CTA.
//   - hosted context: when the agent's replies are served hosted, the panel
//     shows the CURRENT hosted model (hostedModelLabel) and, for a BYOK
//     tenant, a Change affordance (onChangeHostedModel → the existing model
//     editor). On-device mode instead lists downloaded models as switchable.
//   - EDGE-FIRST DEFAULT: with no stored user choice, on-device is the
//     default whenever a text model is genuinely READY on this device (the
//     exported helpers below; AgentPlayground consumes the same helpers so
//     the panel and the send path always agree). A stored user choice —
//     either way — always wins and persists. RECONCILED (verify pass): the
//     helpers now consult A1's routing-preference module
//     (getEdgeRoutingPreference, capabilities.ts) alongside the edge.optIn
//     tri-state ("1"/"0"/absent), so the panel, the chip and routeTurn all
//     read the same preference chain.
//
// Honest framing (2026-08 WebLLM check, revised for A3): the missing
// @mlc-ai/web-llm dependency — the root cause of the permanent
// "On-device AI - unavailable" state in prod-like builds — is installed now,
// so tier 1 (WebLLM) models are presented as real, downloadable and
// runnable. Tool-calling on tier 1 rides tier1.ts's own JSON envelope loop.
//
// Because "platform pool" / "your own provider" is a TENANT-WIDE setting
// (not per-agent — every agent on this tenant that isn't itself opted into
// BYOK follows it), switching between them from here says so inline rather
// than implying a scope it doesn't have.
//
// Strings: pre-A3 copy stays as plain literals/EDGE_BRANDING (unchanged
// behavior); NEW user-facing strings go through t("edgeIntelligence.*") per
// the parallel-agent i18n rule — keys live in the scratchpad fragment
// i18n-a3.json until the merge agent folds them into en.json.

import SourceOption from "./intelligence/SourceOption";
import type { IntelligenceSourceControlProps } from "./intelligence/types";
import OnDeviceFooter from "./intelligence/OnDeviceFooter";
import OnDeviceModelList from "./intelligence/OnDeviceModelList";
import OnDeviceUtilities from "./intelligence/OnDeviceUtilities";
import { useOnDeviceInventory } from "./intelligence/useOnDeviceInventory";
import { useEffect, useRef, useState } from "react";
import { poolLabel } from "../../lib/personalInstall";
import { Cloud, Cpu, KeyRound, Pencil } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { detectCapabilities, edgeEnabled, setEdgeLearningOptIn } from "../../lib/edge/capabilities";
// Phase 3 (reliability blueprint, Part 4) unification: routing-MODE reads
// and writes (the 3-way "what powers this agent" selection, and the
// on-device model-override picker) now funnel through routingState.ts
// instead of calling capabilities.ts's storage getters/setters directly —
// see that module's header for why. getEdgeRoutingPreference stays imported
// from capabilities.ts above as one documented exception (used only inside
// getEffectiveEdgeOptIn below — see that function's comment for why).
import { setRoutingMode, subscribeRoutingMode, type RoutingMode, type TenantProviderState } from "../../lib/edge/routingState";
import { TIER0_ASSET_ID } from "../../lib/edge/tier0";
import { isTier1ModelCached, prewarmTier1Engine } from "../../lib/edge/tier1";
import { UpgradeLock } from "../ui/upgrade-lock";
import { Button } from "../ui/button";

export { getStoredEdgeOptInChoice, edgeTextModelReady, getEffectiveEdgeOptIn } from "./intelligence/optIn";


export default function IntelligenceSourceControl({
  isFreePlan,
  onPlatformLlm,
  activeProviderCount,
  switchToByokBusy,
  switchToPoolBusy,
  onSwitchToByok,
  onSwitchToPool,
  hostedModelLabel = null,
  onChangeHostedModel = null,
  onSourceSelected,
  agentId = null,
  routingPreview = null,
}: IntelligenceSourceControlProps) {
  const enabled = edgeEnabled();
  const t = useTranslations("edgeIntelligence");
  // Reuse of existing keys where they fit (i18n rule): "Change" already
  // exists under the agentWorkbench namespace.
  const tw = useTranslations("agentWorkbench");
  // Phase 3 unification: the panel's 3-way selection reads routingState.ts's
  // canonical 4-mode answer (subscribeRoutingMode below) instead of
  // re-deriving preference logic from the raw tri-state locally. Defaults
  // to "hosted" (same conservative-until-corrected pattern the other state
  // below uses) until the subscription's immediate first fire replaces it.
  const [mode, setMode] = useState<RoutingMode>("hosted");
  // Race-guard refs (2026-08-08 bug fix — see selectPlatform/selectOnDevice/
  // selectOwnProvider and the mode-subscription effect below): selectionSeqRef
  // increments on every explicit selection the user makes among the three
  // SourceOptions; pendingTenantSwitchSeqRef captures that counter's value
  // when an async onSwitchToByok/onSwitchToPool call is kicked off, so the
  // effect below can tell a STALE resolution of that call (one or more newer
  // selections happened since) from a live one, and skip re-applying tenant
  // state from a stale resolution instead of silently clobbering the user's
  // newer choice.
  const selectionSeqRef = useRef(0);
  const pendingTenantSwitchSeqRef = useRef<number | null>(null);
  // Device inventory, prefs and the explicit-gesture setup/download handlers (see the hook).
  const {
    tier0Status,
    caps,
    tier1Models,
    assets,
    override,
    tier0Busy,
    learningOptIn,
    setLearningOptInState,
    utilityAvailable,
    utilityBusy,
    liveModelId,
    memoryWriteStatus,
    handleTier0Setup,
    handleTier1Download,
    handleUtilitySetup,
  } = useOnDeviceInventory(enabled);

  // Phase 3 unification: mode subscription lives in its own effect,
  // separate from the sync effect below — subscribeRoutingMode already
  // fires immediately + on every EDGE_PREFS_EVENT internally, so this isn't
  // a second event system, just routingState.ts's own subscription
  // primitive. Depends on the tenant-shape props so a change to either
  // (e.g. a provider getting connected) re-derives the mode too, not just a
  // storage event.
  useEffect(() => {
    if (!enabled || typeof window === "undefined") return undefined;
    const tenant: TenantProviderState = { usePlatformLlm: onPlatformLlm, activeProviderCount };
    // Race guard: this effect re-runs whenever onPlatformLlm/activeProviderCount
    // change, which includes a STALE async tenant-switch resolution landing
    // after the user already made a newer local selection (selectPlatform/
    // selectOnDevice/selectOwnProvider below). getRoutingMode() checks
    // tenant.usePlatformLlm before the edge-first preference, so applying
    // that stale recompute unconditionally would silently revert the user's
    // newer choice with no error shown. Only the very first, synchronous
    // fire of a freshly (re)subscribed listener can be such a stale
    // recompute — later fires in this same effect instance are live
    // EDGE_PREFS_EVENT reactions to real, current actions, so only that
    // first fire is gated.
    const pendingSeq = pendingTenantSwitchSeqRef.current;
    const staleOnEntry = pendingSeq !== null && pendingSeq !== selectionSeqRef.current;
    pendingTenantSwitchSeqRef.current = null;
    let firstFire = true;
    return subscribeRoutingMode(tenant, (m) => {
      if (firstFire) {
        firstFire = false;
        if (staleOnEntry) return;
      }
      setMode(m);
    });
  }, [enabled, onPlatformLlm, activeProviderCount]);

  // Downloaded = the tier-1 completion marker is set. This is the panel's
  // (cheap, sync) view; the routing path additionally verifies the weights
  // are still in the Cache API (tier1Ready) and falls back gracefully if the
  // browser evicted them — an asset event after any (re)download re-renders
  // this list, so the two views converge.
  const installedModels = tier1Models.filter((m) => isTier1ModelCached(m.id));
  // Bug A fix: `caps` starts null until detectCapabilities() resolves, so
  // this is a strict tri-state (null = still checking) rather than a
  // premature false. Only `=== true` may be treated as "can actually run a
  // tier-1 model" — a downloaded model with WebGPU unavailable (or still
  // unknown) can never execute, so nothing below may call it "Active"/
  // "running" on the strength of a download alone.
  const webgpuReady = caps ? caps.webgpu.available : null;

  // Phase 3 unification: the 3-way selection is now routingState.ts's own
  // canonical mode (getRoutingMode via the subscription effect above),
  // mapped onto this component's existing "platform" | "on-device" |
  // "own-provider" vocabulary — "hybrid" reads as "on-device" (RoutingMode's
  // now-removed "edge" member used to map here too; see routingState.ts's
  // header comment for why it was dead code), "byok" reads as
  // "own-provider", anything else (including the brief window before the
  // subscription's first fire replaces the "hosted" default above) reads as
  // "platform".
  const selected: "platform" | "on-device" | "own-provider" =
    mode === "hybrid" ? "on-device" : mode === "byok" ? "own-provider" : "platform";

  // Wave 2 fix (audit finding P0): does the live, policy-aware preview
  // (routingPreview, from AgentWorkbench's previewRouting(tenant, agentId))
  // actually agree with the raw device-preference selection above? Only
  // meaningful once there's a saved agentId (a server policy can't exist
  // for a draft) and a preview to compare against. "refused" always counts
  // as a divergence — the selector never has a "refused" option to
  // legitimately match against.
  const previewSource: "platform" | "on-device" | "own-provider" | "refused" | null = !routingPreview
    ? null
    : routingPreview.mode === "refused"
      ? "refused"
      : routingPreview.mode === "hosted"
        ? "platform"
        : routingPreview.mode === "byok"
          ? "own-provider"
          : "on-device";
  // Bug fix (confirmed by independent verification): a mismatch between
  // previewSource and the raw local `selected` is NOT by itself evidence of
  // a server policy — routingState.ts's previewOnDeviceTier() also falls
  // back to hosted for the ORDINARY "On-device is selected but this device
  // has no model ready yet" case, which has nothing to do with any
  // server-declared policy and happens on every agent, every day. Gating
  // on routingPreview.policyForced (set true ONLY for a real HOSTED_ONLY/
  // EDGE_ONLY policy or an unrepresentable-guardrails-forced outcome — see
  // routingState.ts's RoutingPreview doc) is what makes this banner say
  // "server routing policy" only when one actually exists.
  const policyDivergence =
    !!agentId && !!previewSource && previewSource !== selected && routingPreview?.policyForced === true;

  // Bug B fix: surface this panel's own computed selection to the parent —
  // AgentWorkbench has no other signal that on-device is active (previously
  // `selected` never left this component), so it couldn't grey its own
  // hosted-model header while on-device is selected. Kept above the
  // `!enabled` early return so hook order stays stable across renders (this
  // component is only ever mounted by the parent when edgeEnabled() is
  // already true, so the effect firing pre-return is a formality, not a
  // behavior change).
  useEffect(() => {
    onSourceSelected?.(selected);
  }, [selected, onSourceSelected]);

  if (!enabled) return null;

  const busy = switchToByokBusy || switchToPoolBusy;
  const ownProviderLocked = isFreePlan || activeProviderCount === 0;

  // Phase 3 unification: all three handlers below now go through
  // routingState.ts's setRoutingMode — "the ONE way to switch modes" per
  // that module's own contract — instead of poking setEdgeOptIn/
  // setModelOverride individually. Each still drives the existing
  // tenant-level onSwitchToPool/onSwitchToByok handlers for the
  // usePlatformLlm server round-trip, which setRoutingMode deliberately
  // does not touch (that stays owned by AgentWorkbench/ProvidersPanel).
  // Note: setRoutingMode("hosted")/("hybrid") both pin the model override
  // (to "hosted", resp. reset it to "auto") as part of one atomic write —
  // a previously-pinned tier-1 model no longer survives a round trip
  // through another source tab the way it did with the old ad-hoc
  // partial-write handlers; re-pin it from that model's own row below.

  function selectPlatform() {
    if (busy) return;
    selectionSeqRef.current += 1;
    setRoutingMode("hosted");
    if (!onPlatformLlm) {
      pendingTenantSwitchSeqRef.current = selectionSeqRef.current;
      onSwitchToPool();
    }
  }

  function selectOnDevice() {
    // Bug fix (2026-08-08): this was the only one of the three options not
    // guarded — see selectPlatform/selectOwnProvider above, and this
    // component's mode-subscription effect for the second half of the fix
    // (the narrow busy===false-but-async-still-resolving window this alone
    // doesn't close).
    if (busy) return;
    selectionSeqRef.current += 1;
    // This UI doesn't distinguish "Edge" (strictly no hosted fallback) from
    // "Hybrid" (hosted fallback on failure) — "hybrid" matches what this
    // panel's own footer copy already promises below ("...automatically
    // falls back to {hostedFallbackTarget}").
    setRoutingMode("hybrid");
    // Warm-up problem fix: previously this only flipped routing state and
    // left the engine cold until the person's first message. detectCapabilities
    // is session-cached after its first call (capabilities.ts), so this is
    // cheap on every click; prewarmTier1Engine itself is a no-op unless a
    // fitting model's weights are already on this device (see its own doc
    // comment in tier1.ts).
    void detectCapabilities().then((c) => prewarmTier1Engine(c));
  }

  function selectOwnProvider() {
    if (busy || ownProviderLocked) return;
    selectionSeqRef.current += 1;
    setRoutingMode("byok");
    if (onPlatformLlm) {
      pendingTenantSwitchSeqRef.current = selectionSeqRef.current;
      onSwitchToByok();
    }
  }

  // installedModels (a tier-1 weights download) wins the label outright: a
  // turn actually routes there whenever it's ready (tierRouter.ts's
  // best-model rule prefers tier 1 over tier 0), regardless of what Chrome's
  // OWN unrelated built-in-model download is doing. Checking tier0Status
  // first here was the bug behind "why is it downloading instead of using
  // the model I already have" - a genuinely ready tier-1 model still read
  // "Setting up..."/"Checking this browser..." whenever tier0 happened to be
  // mid-download or between polls, even though replies would work fine.
  // Also gated on webgpuReady (Bug A fix) - downloaded weights with WebGPU
  // unavailable/unconfirmed can't actually execute, so this must not claim
  // "Ready" on the strength of a download alone either.
  const tier0Label =
    installedModels.length > 0 && webgpuReady === true
      ? "Ready in this browser"
      : tier0Status === "available"
        ? "Ready in this browser"
        : tier0Status === "downloadable"
          ? "Available — one-time setup on first use"
          : tier0Status === "downloading"
            ? "Setting up…"
            : tier0Status === "checking"
              ? "Checking this browser…"
              : caps?.webgpu.available
                ? // A3: capable device, nothing installed — never a dead
                  // "unavailable"; the model list below carries the CTA.
                  t("deviceReadyNoModel")
                : "Not available in this browser";

  const hostedFallbackTarget = onPlatformLlm ? poolLabel("the platform pool", "the local model (Ollama)") : "your own provider";

  // Bug A fix: a downloaded tier-1 model can only actually be selected to
  // run when WebGPU is confirmed available on this device — otherwise the
  // "model" below reduces to weights sitting in cache with nothing able to
  // execute them.
  const runnableInstalledModels = webgpuReady === true ? installedModels : [];

  // The model an on-device turn would actually use, for honest footer copy:
  // a pinned + downloaded model wins, else the largest downloaded one (the
  // auto rule), else the browser built-in.
  const pinnedInstalled =
    override !== "auto" && override !== "hosted"
      ? runnableInstalledModels.find((m) => m.id === override) || null
      : null;
  const autoInstalled =
    runnableInstalledModels.length > 0
      ? [...runnableInstalledModels].sort((a, b) => b.sizeBytes - a.sizeBytes)[0]
      : null;
  const runningLocalModel = pinnedInstalled || autoInstalled;

  // UX gap fix: the audio/vision utilities below (Whisper/Kokoro/SmolVLM)
  // are structurally useless for a chat/call turn without a reasoning
  // model also ready — routeTurn's "never partially local" rule discards a
  // successful local transcript and falls all the way back to hosted if no
  // local reasoning model is ready. Same readiness check the tier0/footer
  // copy above already uses, reused here to gate a small note under the
  // utilities heading so it disappears the moment a text model IS ready.
  const reasoningModelReady = tier0Status === "available" || runningLocalModel !== null;

  const tier0Asset = assets.find((a) => a.id === TIER0_ASSET_ID) || null;

  return (
    <div className="mb-3 rounded-lg border border-border p-3">
      <div className="mb-2 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
        What powers this agent&rsquo;s replies
      </div>
      <p className="mb-2 text-[11px] leading-relaxed text-muted-foreground">
        Not a per-agent setting: {poolLabel("Platform pool", "Local model")} and Your own provider apply to your whole workspace, and On-device
        applies to this browser only — either way, the choice you make here covers every agent, not just this one.
      </p>
      <div className="grid grid-cols-1 gap-1.5 sm:grid-cols-3">
        <SourceOption
          icon={<Cloud size={13} />}
          title={poolLabel("Platform pool", "Local model")}
          subtitle={poolLabel("Hosted — managed for you", "Ollama on this machine")}
          active={selected === "platform"}
          disabled={busy}
          onClick={selectPlatform}
        />
        <SourceOption
          icon={<Cpu size={13} />}
          title="On-device"
          subtitle={tier0Label}
          active={selected === "on-device"}
          disabled={busy}
          onClick={selectOnDevice}
        />
        {ownProviderLocked ? (
          <UpgradeLock
            reason={
              isFreePlan
                ? "Connect and use your own LLM provider on a paid plan."
                : "Activate a provider (Providers page) before switching this on."
            }
            href={isFreePlan ? "/admin/billing" : "/providers"}
            label={isFreePlan ? "Upgrade" : "Connect"}
          >
            <SourceOption
              icon={<KeyRound size={13} />}
              title="Your own provider"
              subtitle={isFreePlan ? "Requires a paid plan" : "No active provider yet"}
              active={false}
              disabled
              onClick={() => {}}
            />
          </UpgradeLock>
        ) : (
          <SourceOption
            icon={<KeyRound size={13} />}
            title="Your own provider"
            subtitle="Your configured credentials"
            active={selected === "own-provider"}
            disabled={busy}
            onClick={selectOwnProvider}
          />
        )}
      </div>

      {/* Wave 2 fix (audit finding P0): honest note when this agent's
          server-declared routing policy overrides the raw device-preference
          selection above. Never blocks the selector itself — the device
          preference is still a legitimate thing to set (e.g. for when the
          policy is later relaxed) — this just stops the panel from silently
          promising something the next turn will not actually do. */}
      {policyDivergence && (
        <p className="mt-2 rounded-md border border-amber-500/30 bg-amber-500/10 px-2.5 py-1.5 text-[11px] leading-relaxed text-amber-600">
          {previewSource === "refused"
            ? "This agent's server routing policy will refuse the next turn rather than use the option selected above."
            : `This agent's server routing policy currently overrides this: the next turn will actually be processed by ${routingPreview!.label}.`}
        </p>
      )}

      {selected === "on-device" ? (
        <>
          {/* Scope of this choice, stated once (2026-09-19). On-device is a
              per-BROWSER routing preference read by the chat surfaces' router;
              nothing server-side (workflow runs, channel replies, scheduled
              runs) can reach a model that lives in this tab. Plain literal,
              same as the policy note above: there is no catalogue key for it. */}
          <p className="mt-2 text-[11px] leading-relaxed text-muted-foreground">
            On-device applies to chat in this browser. Workflows, channels and scheduled runs always use the server
            model.
          </p>
          {/* A3: the actionable on-device model list. Downloaded models are
              switchable in place; not-yet-downloaded ones stay visible but
              greyed, with size + Download; the marketplace link leads to the
              full /edge management page (delete/switch/browse). */}
          <OnDeviceModelList
            assets={assets}
            caps={caps}
            handleTier0Setup={handleTier0Setup}
            handleTier1Download={handleTier1Download}
            hostedFallbackTarget={hostedFallbackTarget}
            installedModels={installedModels}
            liveModelId={liveModelId}
            override={override}
            tier0Asset={tier0Asset}
            tier0Busy={tier0Busy}
            tier0Status={tier0Status}
            tier1Models={tier1Models}
            webgpuReady={webgpuReady}
          />

          {/* Phase 2: on-device audio/vision utilities — one fixed model
              each (no picker, unlike the text-model list above), same
              install-status + Set up affordance as the tier0 row. */}
          <OnDeviceUtilities
            assets={assets}
            handleUtilitySetup={handleUtilitySetup}
            reasoningModelReady={reasoningModelReady}
            utilityAvailable={utilityAvailable}
            utilityBusy={utilityBusy}
          />

          <OnDeviceFooter
            runningLocalModel={runningLocalModel}
            tier0Status={tier0Status}
            hostedFallbackTarget={hostedFallbackTarget}
            learningOptIn={learningOptIn}
            onLearningOptInChange={(on) => {
              setLearningOptInState(on);
              setEdgeLearningOptIn(on);
            }}
            memoryWriteStatus={memoryWriteStatus}
          />
        </>
      ) : (
        <>
          {/* A3 hosted context: the model currently serving this agent, with
              a Change affordance when the tenant can actually change it
              (BYOK, saved agent, not locked). Platform-pool tenants see the
              pool's model, read-only — the pool picks it, not the agent. */}
          {hostedModelLabel && (
            <div className="mt-2 flex items-center justify-between gap-2 rounded-md bg-secondary/40 px-2.5 py-1.5">
              <span className="min-w-0 truncate text-[11px] text-muted-foreground">
                {t("currentHostedModelLabel")}: <span className="font-mono text-foreground">{hostedModelLabel}</span>
              </span>
              {selected === "own-provider" && onChangeHostedModel && (
                <Button type="button" variant="outline" size="sm" disabled={busy} onClick={onChangeHostedModel}>
                  <Pencil size={11} />
                  {tw("change")}
                </Button>
              )}
            </div>
          )}
          <p className="mt-2 text-[11px] leading-relaxed text-muted-foreground">
            {selected === "platform"
              ? "Applies to your whole workspace, not just this agent — every agent not individually on your own provider follows it."
              : `Applies to your whole workspace, not just this agent — every agent not individually switched to ${poolLabel("the platform pool", "the local model")} follows it.`}
          </p>
        </>
      )}
    </div>
  );
}
