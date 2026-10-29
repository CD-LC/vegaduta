"use client";

import { useEffect, useState } from "react";
import { Wallet, Gauge, MessagesSquare } from "lucide-react";
import { api } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Button } from "../ui/button";
import Select from "../Select";
import { SectionCard, ComingSoon, InfoNote } from "./common";

// Budgets & Limits §3.3/§4 - the per-principal DOLLAR budget over a
// configurable window, decremented by every modality's real cost. Backend:
// GET/PUT /api/platform/per-user-budget (PerUserBudgetService, core-3).
//
// FIX (2026-08-09): this component originally guessed the contract ahead of
// the backend landing - { amountUsd, windowSeconds, modalities: {text,image,
// voice} } with DOLLAR sub-caps - and was never updated once the real
// PerUserBudgetService/PerUserBudgetConfigUpdateRequest shipped with
// different field names AND different sub-cap semantics. Saves silently
// no-opped: `amountUsd`/`modalities` aren't keys PerUserBudgetConfigUpdateRequest
// recognizes, so Jackson dropped them as unknown JSON properties and only
// `windowSeconds` (the one field that happened to match) ever actually
// persisted - the success toast fired because the PUT itself succeeded, it
// just didn't write what the admin typed. Corrected to the REAL contract:
//   { enabled, dailyUsd, windowSeconds, splitPct: { TEXT, IMAGE, VOICE, VIDEO }, fallbackUsdPer1kTokens }
// splitPct is each modality's cap as a PERCENTAGE of dailyUsd (0-100,
// independent gates - see PerUserBudgetService.BudgetConfig's javadoc), not
// an independent dollar amount. Keys are the Modality enum's Jackson-default
// serialization (upper-case name()). VIDEO is a real key on the backend
// (default 0%) but stays hidden here - see the InfoNote below - to keep this
// fix scoped to "make Save/Load actually work" rather than also redesigning
// the layout; a future pass can surface it as a fourth field.

const WINDOW_PRESETS = [
  { seconds: 3600, label: "1 hour" },
  { seconds: 86400, label: "24 hours (daily)" },
  { seconds: 604800, label: "7 days (weekly)" },
  { seconds: 2592000, label: "30 days (monthly)" },
];

function windowLabel(seconds: number) {
  const match = WINDOW_PRESETS.find((p) => p.seconds === Number(seconds));
  if (match) return match.label;
  const h = Math.round(Number(seconds) / 3600);
  return `${h} hour${h === 1 ? "" : "s"}`;
}

export function PerUserBudgetSection() {
  const [budget, setBudget] = useState<any>(null);
  const [unavailable, setUnavailable] = useState(false);
  const [loading, setLoading] = useState(true);
  const [draft, setDraft] = useState<any>(null);
  const [saving, setSaving] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  useEffect(() => {
    let cancelled = false;
    api
      .getPerUserBudget()
      .then((b) => {
        if (cancelled) return;
        setBudget(b);
        // 2026-08-09 fix: this was reading amountUsd/modalities.* - fields
        // that don't exist on the response. The real shape (BudgetConfig.java)
        // is dailyUsd + splitPct (a Map<Modality, Integer> keyed by the
        // uppercase enum name, e.g. "TEXT") - see the matching fix in save()
        // below. Silently reading undefined meant every load fell back to
        // the 0.15 default regardless of what was actually saved, and the
        // "Current: $X" display always showed $0.00.
        setDraft({
          amountUsd: b?.dailyUsd ?? 0.15,
          windowSeconds: b?.windowSeconds ?? 86400,
          text: b?.splitPct?.TEXT ?? "",
          image: b?.splitPct?.IMAGE ?? "",
          voice: b?.splitPct?.VOICE ?? "",
        });
      })
      .catch(() => {
        // Endpoint not built yet (or unreachable) - fall to the coming-soon
        // state rather than surfacing a scary error toast for a known gap.
        if (!cancelled) setUnavailable(true);
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  async function save() {
    if (!draft) return;
    const errors: Record<string, string> = {};
    const amount = Number(draft.amountUsd);
    if (!(amount > 0)) {
      errors.amountUsd = "Must be a positive dollar value.";
    }
    for (const m of ["text", "image", "voice"]) {
      if (draft[m] === "") continue;
      const pct = Number(draft[m]);
      if (!Number.isFinite(pct) || pct < 0 || pct > 100) {
        errors[m] = "Must be a percentage between 0 and 100.";
      }
    }
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) {
      const firstKey = errors.amountUsd ? "budget-amount-usd" : `budget-subcap-${Object.keys(errors)[0]}`;
      document.getElementById(firstKey)?.focus();
      toast.error("Fix the highlighted field(s) below.");
      return;
    }
    setSaving(true);
    try {
      // Field names here must match PerUserBudgetConfigUpdateRequest.java
      // exactly (dailyUsd/splitPct, not amountUsd/modalities - see the load
      // effect's comment above for the full bug this fixes) - Jackson
      // silently drops unrecognized JSON properties rather than erroring, so
      // a name mismatch here means the value never reaches
      // updateGlobalConfig's non-null-field upsert at all.
      const payload: any = {
        dailyUsd: amount,
        windowSeconds: Number(draft.windowSeconds) || 86400,
        splitPct: {
          text: draft.text === "" ? null : Number(draft.text),
          image: draft.image === "" ? null : Number(draft.image),
          voice: draft.voice === "" ? null : Number(draft.voice),
        },
      };
      const updated = await api.updatePerUserBudget(payload);
      setBudget(updated || { dailyUsd: payload.dailyUsd, windowSeconds: payload.windowSeconds });
      setFieldErrors({});
      toast.success("Per-user budget updated - applies on the next request, no restart.");
    } catch (err: any) {
      toast.error(err.message || "Could not update the per-user budget.");
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return (
      <SectionCard title="Global default budget" icon={Wallet}>
        <p className="py-1 text-sm text-muted-foreground">Loading…</p>
      </SectionCard>
    );
  }

  if (unavailable) {
    return (
      <SectionCard title="Global default budget" icon={Wallet}>
        <ComingSoon
          title="Per-user dollar budget"
          description="A per-principal dollar budget over a configurable window, decremented by every modality's real cost (text / image / voice; video is always pay-per-use). Configure the amount, window, and per-modality sub-caps here once the backend lands."
          roadmap="Roadmap P1 (core-3): PerUserBudgetService + PlatformSetting-backed runtime config. Precedence: per-user → per-tenant → per-plan → global default."
          endpoints={["GET /api/platform/per-user-budget", "PUT /api/platform/per-user-budget"]}
        />
      </SectionCard>
    );
  }

  return (
    <SectionCard
      title="Global default budget"
      subtitle="Per-user dollar cap over a configurable window - changes apply on the next request, no restart."
      icon={Wallet}
    >
      <InfoNote>
        Dollars (not tokens) because voice/image aren&rsquo;t token-priced. Video generation is excluded from the
        included budget - it&rsquo;s always pay-per-use. Per-modality sub-caps are a{" "}
        <strong>percentage of the daily amount above</strong> (e.g. 50 = that modality alone can spend up to half the
        daily budget), not a separate dollar figure - each is an independent gate, so several modalities at 100% can
        each spend the full amount. Leave a sub-cap blank to let that modality draw on the shared amount without its own
        independent gate.
      </InfoNote>

      <div className="flex flex-wrap items-end gap-3">
        <div className="w-40 space-y-1.5">
          <Label htmlFor="budget-amount-usd" className="text-xs text-muted-foreground">
            Amount (USD)
          </Label>
          <Input
            id="budget-amount-usd"
            type="number"
            min="0"
            step="0.01"
            value={draft.amountUsd}
            onChange={(e) => setDraft((d: any) => ({ ...d, amountUsd: e.target.value }))}
            aria-invalid={!!fieldErrors.amountUsd}
          />
          {fieldErrors.amountUsd && <p className="text-xs text-destructive">{fieldErrors.amountUsd}</p>}
        </div>
        <div className="w-56 space-y-1.5">
          <Label htmlFor="budget-window" className="text-xs text-muted-foreground">
            Window
          </Label>
          <Select
            id="budget-window"
            value={draft.windowSeconds}
            onChange={(e) => setDraft((d: any) => ({ ...d, windowSeconds: e.target.value }))}
            options={WINDOW_PRESETS.map((p) => ({ value: p.seconds, label: p.label }))}
          />
        </div>
      </div>

      <div className="mt-4 mb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
        Per-modality sub-caps (% of daily budget, optional)
      </div>
      <div className="flex flex-wrap items-end gap-3">
        {["text", "image", "voice"].map((m) => (
          <div key={m} className="w-32 space-y-1.5">
            <Label htmlFor={`budget-subcap-${m}`} className="text-xs capitalize text-muted-foreground">
              {m}
            </Label>
            <div className="relative">
              <Input
                id={`budget-subcap-${m}`}
                type="number"
                min="0"
                max="100"
                step="1"
                placeholder="no cap"
                value={draft[m]}
                onChange={(e) => setDraft((d: any) => ({ ...d, [m]: e.target.value }))}
                className="pe-6"
                aria-invalid={!!fieldErrors[m]}
              />
              <span className="pointer-events-none absolute inset-y-0 right-2.5 flex items-center text-xs text-muted-foreground">
                %
              </span>
            </div>
            {fieldErrors[m] && <p className="text-xs text-destructive">{fieldErrors[m]}</p>}
          </div>
        ))}
      </div>

      <div className="mt-4 flex items-center gap-3">
        <Button type="button" onClick={save} disabled={saving} size="sm">
          {saving ? "Saving…" : "Save budget"}
        </Button>
        <span className="text-xs text-muted-foreground">
          Current: ${Number(budget?.dailyUsd ?? 0).toFixed(2)} / {windowLabel(budget?.windowSeconds ?? 86400)}
        </span>
      </div>
    </SectionCard>
  );
}

// The platform-hosted-pool TOKEN quota (FreeTierUsageLimiterService).
// A DIFFERENT, independent gate from PerUserBudgetSection above (which is a
// DOLLAR cap) - this is the raw token-count cap behind the "monthly token
// limit reached" error. Raising the dollar budget above never touches this
// one; support requests to raise the token cap belong here.
// monthlyTokenLimit is the FREE-tier base for the single rolling-30-day
// window (the message-count and daily-token windows were removed
// 2026-09-26); PREMIUM/UNLIMITED multiply it (default 400,000 x 3 =
// 1,200,000/30 days).
export function FreeTierQuotaSection() {
  const [, setQuota] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [draft, setDraft] = useState<any>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .getFreeTierQuota()
      .then((q) => {
        if (cancelled) return;
        setQuota(q);
        setDraft({
          monthlyTokenLimit: q?.monthlyTokenLimit ?? 400000,
          premiumMultiplier: q?.premiumMultiplier ?? 2,
          unlimitedMultiplier: q?.unlimitedMultiplier ?? 3,
        });
      })
      .catch((err) => !cancelled && setLoadError(err.message))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  async function save() {
    if (!draft) return;
    const values = {
      monthlyTokenLimit: Number(draft.monthlyTokenLimit),
      premiumMultiplier: Number(draft.premiumMultiplier),
      unlimitedMultiplier: Number(draft.unlimitedMultiplier),
    };
    if (Object.values(values).some((v) => !(v > 0))) {
      toast.error("All three values must be positive numbers.");
      return;
    }
    setSaving(true);
    try {
      const updated = await api.updateFreeTierQuota(values);
      setQuota(updated || values);
      toast.success("Token quota updated - applies on the next request, no restart.");
    } catch (err: any) {
      toast.error(err.message || "Could not update the token quota.");
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return (
      <SectionCard title="Platform token quota" icon={Gauge}>
        <p className="py-1 text-sm text-muted-foreground">Loading…</p>
      </SectionCard>
    );
  }

  if (loadError) {
    return (
      <SectionCard title="Platform token quota" icon={Gauge}>
        <div className="rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {loadError}
        </div>
      </SectionCard>
    );
  }

  const effectiveUnlimited = Number(draft.monthlyTokenLimit || 0) * Number(draft.unlimitedMultiplier || 0);
  const effectivePremium = Number(draft.monthlyTokenLimit || 0) * Number(draft.premiumMultiplier || 0);

  return (
    <SectionCard
      title="Platform token quota"
      subtitle="Token cap on the platform-hosted LLM pool, per rolling 30 days - independent from the dollar budget above. Changes apply on the next request, no restart."
      icon={Gauge}
    >
      <InfoNote>
        This is the cap behind &ldquo;monthly token limit reached&rdquo;. FREE tenants use the base value directly;
        PREMIUM and UNLIMITED multiply it. To give one specific tenant a clean slate on their current window without
        changing these limits, use &ldquo;Reset usage&rdquo; on that tenant&rsquo;s row in Tenants &amp; Users instead.
      </InfoNote>

      <div className="flex flex-wrap items-end gap-3">
        <div className="w-56 space-y-1.5">
          <Label htmlFor="budget-monthly-token-limit" className="text-xs text-muted-foreground">
            Monthly token limit (FREE base, per 30 days)
          </Label>
          <Input
            id="budget-monthly-token-limit"
            type="number"
            min="1"
            value={draft.monthlyTokenLimit}
            onChange={(e) => setDraft((d: any) => ({ ...d, monthlyTokenLimit: e.target.value }))}
          />
        </div>
        <div className="w-36 space-y-1.5">
          <Label htmlFor="budget-premium-multiplier" className="text-xs text-muted-foreground">
            Premium multiplier
          </Label>
          <Input
            id="budget-premium-multiplier"
            type="number"
            min="1"
            step="1"
            value={draft.premiumMultiplier}
            onChange={(e) => setDraft((d: any) => ({ ...d, premiumMultiplier: e.target.value }))}
          />
        </div>
        <div className="w-36 space-y-1.5">
          <Label htmlFor="budget-unlimited-multiplier" className="text-xs text-muted-foreground">
            Unlimited multiplier
          </Label>
          <Input
            id="budget-unlimited-multiplier"
            type="number"
            min="1"
            step="1"
            value={draft.unlimitedMultiplier}
            onChange={(e) => setDraft((d: any) => ({ ...d, unlimitedMultiplier: e.target.value }))}
          />
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Button type="button" onClick={save} disabled={saving} size="sm">
          {saving ? "Saving…" : "Save quota"}
        </Button>
        <span className="text-xs text-muted-foreground">
          Effective per 30 days: Premium {effectivePremium.toLocaleString()} tokens · Unlimited{" "}
          {effectiveUnlimited.toLocaleString()} tokens
        </span>
      </div>
    </SectionCard>
  );
}

// 2026-08-11 - chat-generation platform settings (GET/PUT
// /api/platform/chat-generation-settings, core-3). These three values were
// previously hardcoded in core's chat path; this card makes them
// operator-selectable. A THIRD independent lever alongside the two cost
// gates above: the dollar budget and token quota cap SPEND, while these
// control how many tokens each turn generates in the first place -
// Gemini 2.5+ thinking effort (thinking tokens bill as OUTPUT tokens, so
// "high" can multiply per-turn cost without changing the visible reply)
// and the conversation-history replay window (turns + character cap), which
// is the dominant input-token cost on long conversations. PUT accepts a
// partial update, but this card always sends all three fields - the form
// holds all of them, so a full send is simpler and harmless.

const REASONING_EFFORTS = [
  { value: "none", label: "None - no thinking budget" },
  { value: "low", label: "Low (recommended)" },
  { value: "medium", label: "Medium" },
  { value: "high", label: "High - deepest, most expensive" },
];

export function ChatGenerationSection() {
  const [settings, setSettings] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [draft, setDraft] = useState<any>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .getChatGenerationSettings()
      .then((s) => {
        if (cancelled) return;
        setSettings(s);
        setDraft({
          geminiReasoningEffort: s?.geminiReasoningEffort ?? "low",
          historyMaxTurns: s?.historyMaxTurns ?? 40,
          historyMaxChars: s?.historyMaxChars ?? 32000,
        });
      })
      .catch((err) => !cancelled && setLoadError(err.message))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  async function save() {
    if (!draft) return;
    const turns = Number(draft.historyMaxTurns);
    const chars = Number(draft.historyMaxChars);
    // Mirror the server's own PUT validation ranges (PlatformController) so a
    // typo gets an inline message instead of a raw rejected-request toast.
    // Turns are capped at 400 because that is the stored-history hard cap
    // (SessionMemoryService.MAX_STORED_ENTRIES) - a larger window could never
    // replay more than the store keeps.
    if (!(Number.isInteger(turns) && turns >= 1 && turns <= 400)) {
      toast.error("History turns must be a whole number between 1 and 400.");
      return;
    }
    if (!(Number.isInteger(chars) && chars >= 1000 && chars <= 200000)) {
      toast.error("History max characters must be a whole number between 1,000 and 200,000.");
      return;
    }
    setSaving(true);
    try {
      const payload = {
        geminiReasoningEffort: draft.geminiReasoningEffort,
        historyMaxTurns: turns,
        historyMaxChars: chars,
      };
      const updated = await api.updateChatGenerationSettings(payload);
      setSettings(updated || payload);
      toast.success("Chat generation defaults updated - applies on the next request, no restart.");
    } catch (err: any) {
      toast.error(err.message || "Could not update the chat generation defaults.");
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return (
      <SectionCard title="Chat generation defaults" icon={MessagesSquare}>
        <p className="py-1 text-sm text-muted-foreground">Loading…</p>
      </SectionCard>
    );
  }

  if (loadError) {
    return (
      <SectionCard title="Chat generation defaults" icon={MessagesSquare}>
        <div className="rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
          {loadError}
        </div>
      </SectionCard>
    );
  }

  return (
    <SectionCard
      title="Chat generation defaults"
      subtitle="Per-turn generation cost levers for the hosted chat path - independent from the spend caps above. Changes apply on the next request, no restart."
      icon={MessagesSquare}
    >
      <div className="flex flex-wrap items-end gap-3">
        <div className="w-64 space-y-1.5">
          <Label htmlFor="chatgen-reasoning-effort" className="text-xs text-muted-foreground">
            Gemini thinking effort
          </Label>
          <Select
            id="chatgen-reasoning-effort"
            value={draft.geminiReasoningEffort}
            onChange={(e) => setDraft((d: any) => ({ ...d, geminiReasoningEffort: e.target.value }))}
            options={REASONING_EFFORTS}
          />
          <p className="text-xs text-muted-foreground">
            Reasoning budget for hosted-pool Gemini 2.5+ models - thinking tokens are billed as output;
            &lsquo;low&rsquo; balances quality and cost
          </p>
        </div>
        <div className="w-44 space-y-1.5">
          <Label htmlFor="budget-history-max-turns" className="text-xs text-muted-foreground">
            History window (turns)
          </Label>
          <Input
            id="budget-history-max-turns"
            type="number"
            min="1"
            max="400"
            step="1"
            value={draft.historyMaxTurns}
            onChange={(e) => setDraft((d: any) => ({ ...d, historyMaxTurns: e.target.value }))}
          />
        </div>
        <div className="w-44 space-y-1.5">
          <Label htmlFor="budget-history-max-chars" className="text-xs text-muted-foreground">
            History window (max characters)
          </Label>
          <Input
            id="budget-history-max-chars"
            type="number"
            min="1000"
            max="200000"
            step="1"
            value={draft.historyMaxChars}
            onChange={(e) => setDraft((d: any) => ({ ...d, historyMaxChars: e.target.value }))}
          />
        </div>
      </div>
      <p className="mt-1.5 text-xs text-muted-foreground">
        How much prior conversation is replayed to the model each turn - the main lever on per-turn token cost
      </p>

      <div className="mt-4 flex flex-wrap items-center gap-3">
        <Button type="button" onClick={save} disabled={saving} size="sm">
          {saving ? "Saving…" : "Save defaults"}
        </Button>
        <span className="text-xs text-muted-foreground">
          Current: {settings?.geminiReasoningEffort ?? "low"} effort · {Number(settings?.historyMaxTurns ?? 40)} turns ·{" "}
          {Number(settings?.historyMaxChars ?? 32000).toLocaleString()} chars
        </span>
      </div>
    </SectionCard>
  );
}
