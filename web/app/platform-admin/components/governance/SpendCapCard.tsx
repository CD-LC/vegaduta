"use client";

import { useCallback, useEffect, useState } from "react";
import { Wallet, Pencil, KeyRound } from "lucide-react";
import { SectionCard } from "../sections/common";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Skeleton } from "../ui/skeleton";
import MasterKeyPrompt from "../tenants/MasterKeyPrompt";
import { toast } from "../../lib/toastBus";
import { governanceApi, type TenantSpendCap } from "../../lib/adminApi/governance";
import {
  ALERT_STAGE_LABEL,
  capBarWidth,
  capPercent,
  capTone,
  formatCapUsd,
  parseCapInput,
} from "../../lib/governanceFormat";
import { formatDay } from "../../lib/platformFormat";
import { Checkbox } from "../../../components/ui/checkbox";
import { useTranslations } from "../../../lib/i18n";

// Tenant detail "Spend cap" card. Monthly (calendar month, UTC) cap on the
// tenant's hosted-pool LLM spend; BYOK is never counted or blocked. At 100%
// core refuses hosted LLM calls with 402 until the month rolls over or the cap
// is raised/removed here. Writes are master-key gated (X-Master-Key), the key
// held in memory for this page only.

const TONE_BAR: Record<string, string> = {
  ok: "bg-primary",
  warn: "bg-[var(--warning)]",
  bad: "bg-destructive",
  muted: "bg-muted-foreground",
};

export default function SpendCapCard({ tenantId, tenantName }: { tenantId: string; tenantName: string }) {
  const tHC = useTranslations("hcPlatformadminComponentsGovernanceSpendCapCard");
  const [cap, setCap] = useState<TenantSpendCap | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");
  const [noCap, setNoCap] = useState(false);
  const [draftError, setDraftError] = useState<string | null>(null);
  const [masterKey, setMasterKey] = useState<string | null>(null);
  const [promptOpen, setPromptOpen] = useState(false);
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setCap(await governanceApi.getSpendCap(tenantId));
      setError(null);
    } catch (err: any) {
      setCap(null);
      setError(err?.message || "Spend cap unavailable.");
    } finally {
      setLoading(false);
    }
  }, [tenantId]);

  useEffect(() => {
    setEditing(false);
    void load();
  }, [load]);

  function startEdit() {
    setDraft(cap?.capUsd != null ? String(cap.capUsd) : "");
    setNoCap(cap?.capUsd == null);
    setDraftError(null);
    setEditing(true);
  }

  async function save(key: string) {
    const parsed = parseCapInput(draft, noCap);
    if ("error" in parsed) {
      setDraftError(parsed.error);
      return;
    }
    setSaving(true);
    try {
      const next = await governanceApi.setSpendCap(tenantId, parsed.value, key);
      setCap(next);
      setEditing(false);
      toast.success(
        parsed.value == null
          ? `${tenantName}: spend cap removed.`
          : `${tenantName}: spend cap set to ${formatCapUsd(parsed.value)}/month.`
      );
    } catch (err: any) {
      // A rejected key is wrong, not transient - re-prompt next time.
      if (err?.status === 401 || err?.status === 403) setMasterKey(null);
      setDraftError(err?.message || "Could not save the cap.");
    } finally {
      setSaving(false);
    }
  }

  function onSave() {
    const parsed = parseCapInput(draft, noCap);
    if ("error" in parsed) {
      setDraftError(parsed.error);
      return;
    }
    setDraftError(null);
    if (!masterKey) {
      setPromptOpen(true);
      return;
    }
    void save(masterKey);
  }

  const pct = cap ? capPercent(cap.capUsd, cap.monthToDateUsd, cap.percentUsed) : null;
  const tone = cap ? capTone(cap) : "muted";

  return (
    <SectionCard
      title={tHC("s8")}
      icon={Wallet}
      subtitle="Monthly limit on hosted-pool LLM spend (calendar month, UTC). BYOK calls are never counted or blocked."
      actions={
        cap && !editing ? (
          <Button size="sm" variant="outline" onClick={startEdit}>
            <Pencil /> {tHC("s7")}
          </Button>
        ) : undefined
      }
    >
      {loading && !cap && <Skeleton className="h-16 w-full" />}
      {error && !loading && (
        <p className="text-xs text-muted-foreground">
          {tHC("s6")} {error}
        </p>
      )}

      {cap && (
        <div className="space-y-3">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <span className="font-semibold">
              {cap.capUsd == null ? "No cap" : `${formatCapUsd(cap.capUsd)} / month`}
            </span>
            {cap.blocked && <Badge variant="destructive">Blocked</Badge>}
            {!cap.blocked && cap.capUsd != null && cap.alertStage >= 80 && (
              <Badge variant="outline" className="border-[var(--warning-border)] text-[var(--warning-text)]">
                {ALERT_STAGE_LABEL[cap.alertStage] || `${cap.alertStage}% alert`}
              </Badge>
            )}
            {cap.capUsd != null && !cap.blocked && cap.alertStage < 80 && (
              <span className="text-xs text-muted-foreground">{ALERT_STAGE_LABEL[0]}</span>
            )}
          </div>

          <div className="max-w-md space-y-1">
            <div className="flex items-baseline justify-between text-xs">
              <span>
                {tHC("s5")} <span className="font-mono">{formatCapUsd(cap.monthToDateUsd)}</span>
                {cap.monthStart ? (
                  <span className="text-muted-foreground"> since {formatDay(cap.monthStart)}</span>
                ) : null}
              </span>
              {pct != null && <span className="font-mono text-muted-foreground">{pct}%</span>}
            </div>
            {cap.capUsd != null && (
              <div
                className="relative h-2 w-full overflow-hidden rounded-full bg-muted"
                role="meter"
                aria-label={tHC("s4")}
                aria-valuemin={0}
                aria-valuemax={100}
                aria-valuenow={capBarWidth(pct)}
              >
                <div className={`h-full ${TONE_BAR[tone]}`} style={{ width: `${capBarWidth(pct)}%` }} />
                <div
                  className="absolute inset-y-0 w-px bg-foreground/60"
                  style={{ left: "80%" }}
                  title={tHC("s3")}
                  aria-hidden="true"
                />
              </div>
            )}
            {cap.blocked && <p className="text-xs text-destructive">{tHC("s2")}</p>}
          </div>

          {editing && (
            <div className="space-y-2 rounded-lg border border-border p-3">
              <div className="flex flex-wrap items-end gap-3">
                <div className="space-y-1">
                  <Label htmlFor="spend-cap-input" className="text-xs text-muted-foreground">
                    {tHC("s1")}
                  </Label>
                  <Input
                    id="spend-cap-input"
                    type="number"
                    inputMode="decimal"
                    min={0}
                    step="0.01"
                    className="h-9 w-40"
                    value={draft}
                    disabled={noCap || saving}
                    onChange={(e: any) => setDraft(e.target.value)}
                    onKeyDown={(e: any) => e.key === "Enter" && onSave()}
                  />
                </div>
                <div className="flex h-9 items-center gap-2 text-sm">
                  <Checkbox checked={noCap} disabled={saving} onCheckedChange={(v) => setNoCap(v)} id="spend-no-cap" />
                  <label htmlFor="spend-no-cap">No cap</label>
                </div>
              </div>
              {draftError && (
                <p className="text-xs text-destructive" role="alert">
                  {draftError}
                </p>
              )}
              <div className="flex flex-wrap items-center gap-2">
                <Button size="sm" disabled={saving} onClick={onSave}>
                  {saving ? "Saving..." : "Save cap"}
                </Button>
                <Button size="sm" variant="outline" disabled={saving} onClick={() => setEditing(false)}>
                  Cancel
                </Button>
                <span className="inline-flex items-center gap-1 text-[11px] text-muted-foreground">
                  <KeyRound size={12} /> {masterKey ? "Master key unlocked for this page" : "Requires the master key"}
                </span>
              </div>
            </div>
          )}
        </div>
      )}

      <MasterKeyPrompt
        open={promptOpen}
        submitting={saving}
        description="Unlocks spend-cap changes on this page. Core re-checks it on every save."
        onCancel={() => setPromptOpen(false)}
        onSubmit={(key) => {
          setMasterKey(key);
          setPromptOpen(false);
          void save(key);
        }}
      />
    </SectionCard>
  );
}
