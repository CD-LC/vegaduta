"use client";

import { useEffect, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { Sparkles } from "lucide-react";
import { api } from "../../lib/api";
import { toast } from "../../lib/toastBus";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Alert, AlertDescription } from "../ui/alert";
import { SectionCard, SwitchToggle } from "./common";
import { useSectionData } from "./observability";
import { useTranslations } from "../../../lib/i18n";

// Smart AI governance (PlatformFeatureBudgetService + PlatformController's
// /api/platform/smart-ai surface) - the platform's own AI-native niceties
// (in-console error explanations, LLM-call diagnosis, model capability
// summaries) are metered features with a combined daily USD ceiling, a global
// on/off, and a per-feature enabled/dailyUsd override. Everything is a
// platform_setting row live on the next call - no redeploy. Flipping the
// master switch off degrades every gated feature to its non-AI behaviour
// immediately; nothing breaks.

// The fixed feature-key catalog (FEATURE_KEY constants in core/chat) - a
// feature row is only useful if a core service actually calls
// checkBudget(key), so the list is the three wired keys rather than a
// free-text key field that would silently no-op on a typo.
const SMART_AI_FEATURES = [
  { key: "error-explanation", label: "Error explanations", desc: "Plain-language explanation when a chat call fails" },
  { key: "llm-call-diagnosis", label: "LLM call diagnosis", desc: "AI diagnosis panel for failed model calls" },
  {
    key: "model-capability-summary",
    label: "Model capability summaries",
    desc: "Generated capability blurbs in the model picker",
  },
];

export function SmartAiSection({ pollMs, delay }: { pollMs?: number; delay?: number }) {
  const tHC = useTranslations("hcPlatformadminComponentsSectionsSmartai");
  const { data: config, error, loading, refresh } = useSectionData<any>(() => api.getSmartAiConfig(), null, pollMs);
  const [draft, setDraft] = useState<any>({});
  const [saving, setSaving] = useState(false);
  const [globalFieldErrors, setGlobalFieldErrors] = useState<Record<string, string>>({});

  // Feature rows load independently so one missing key can't blank the card.
  const [features, setFeatures] = useState<Record<string, any>>({});
  const [featureDrafts, setFeatureDrafts] = useState<Record<string, any>>({});
  const [savingFeature, setSavingFeature] = useState<string | null>(null);

  useEffect(() => {
    if (config) setDraft({ ...config });
  }, [config]);

  useEffect(() => {
    let cancelled = false;
    Promise.all(SMART_AI_FEATURES.map((f) => api.getSmartAiFeature(f.key).catch((): null => null))).then(
      (rows) => {
        if (cancelled) return;
        const byKey: Record<string, any> = {};
        for (const row of rows) if (row) byKey[row.featureKey] = row;
        setFeatures(byKey);
        setFeatureDrafts(byKey);
      },
      () => {}
    );
    return () => {
      cancelled = true;
    };
  }, []);

  const dirty =
    config &&
    (draft.enabled !== config.enabled ||
      String(draft.masterDailyUsd ?? "") !== String(config.masterDailyUsd ?? "") ||
      String(draft.windowSeconds ?? "") !== String(config.windowSeconds ?? "") ||
      String(draft.defaultFeatureDailyUsd ?? "") !== String(config.defaultFeatureDailyUsd ?? ""));

  async function saveGlobal() {
    const masterDailyUsd =
      draft.masterDailyUsd === "" || draft.masterDailyUsd == null ? null : Number(draft.masterDailyUsd);
    const defaultFeatureDailyUsd =
      draft.defaultFeatureDailyUsd === "" || draft.defaultFeatureDailyUsd == null
        ? null
        : Number(draft.defaultFeatureDailyUsd);
    const windowSeconds =
      draft.windowSeconds === "" || draft.windowSeconds == null ? null : Number(draft.windowSeconds);
    const errors: Record<string, string> = {};
    if (masterDailyUsd != null && (!Number.isFinite(masterDailyUsd) || masterDailyUsd < 0)) {
      errors.masterDailyUsd = "Must be a non-negative dollar amount.";
    }
    if (defaultFeatureDailyUsd != null && (!Number.isFinite(defaultFeatureDailyUsd) || defaultFeatureDailyUsd < 0)) {
      errors.defaultFeatureDailyUsd = "Must be a non-negative dollar amount.";
    }
    if (windowSeconds != null && (!Number.isInteger(windowSeconds) || windowSeconds < 60)) {
      errors.windowSeconds = "Must be a whole number of seconds, at least 60.";
    }
    setGlobalFieldErrors(errors);
    if (Object.keys(errors).length > 0) {
      const idByKey: Record<string, string> = {
        masterDailyUsd: "smartai-master",
        windowSeconds: "smartai-window",
        defaultFeatureDailyUsd: "smartai-default",
      };
      document.getElementById(idByKey[Object.keys(errors)[0]])?.focus();
      toast.error("Fix the highlighted field(s) below.");
      return;
    }
    setSaving(true);
    try {
      const updated = await api.updateSmartAiConfig({
        enabled: draft.enabled,
        masterDailyUsd,
        windowSeconds,
        defaultFeatureDailyUsd,
      });
      setDraft({ ...updated });
      setGlobalFieldErrors({});
      toast.success("Smart AI settings saved - live on the next feature call.");
      refresh().catch(() => {});
    } catch (err: any) {
      toast.error(err.message || "Could not save Smart AI settings.");
    } finally {
      setSaving(false);
    }
  }

  function featureDirty(key: string) {
    const saved = features[key];
    const d = featureDrafts[key];
    if (!saved || !d) return false;
    return d.enabled !== saved.enabled || String(d.dailyUsd ?? "") !== String(saved.dailyUsd ?? "");
  }

  async function saveFeature(key: string) {
    const d = featureDrafts[key];
    const dailyUsd = d.dailyUsd === "" || d.dailyUsd == null ? null : Number(d.dailyUsd);
    if (dailyUsd != null && (!Number.isFinite(dailyUsd) || dailyUsd < 0)) {
      toast.error("Feature daily cap must be a non-negative dollar amount (or blank to use the default).");
      return;
    }
    setSavingFeature(key);
    try {
      const updated = await api.updateSmartAiFeature(key, { enabled: d.enabled, dailyUsd });
      setFeatures((prev) => ({ ...prev, [key]: updated }));
      setFeatureDrafts((prev) => ({ ...prev, [key]: updated }));
      toast.success(`${key} updated.`);
    } catch (err: any) {
      toast.error(err.message || `Could not update ${key}.`);
    } finally {
      setSavingFeature(null);
    }
  }

  return (
    <SectionCard
      title={tHC("s11")}
      subtitle="The platform's own AI-native niceties - one master switch + a combined daily USD ceiling, with a per-feature override below"
      icon={Sparkles}
      delay={delay}
      actions={
        config ? (
          <Badge
            variant="outline"
            className={
              config.enabled
                ? "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]"
                : "border-[var(--warning-border)] bg-[var(--warning-bg)] text-[var(--warning-text)]"
            }
          >
            {config.enabled ? "ENABLED" : "DISABLED"}
          </Badge>
        ) : undefined
      }
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {loading ? (
        <p className="py-1 text-sm text-muted-foreground">{tHC("s10")}</p>
      ) : !config ? (
        <p className="py-1 text-sm text-muted-foreground">{tHC("s9")}</p>
      ) : (
        <>
          <div className="flex flex-wrap items-end gap-4 rounded-lg border border-border p-4">
            <div className="space-y-1.5">
              {/* Visual caption only - the switch carries its own aria-label. */}
              <span
                className="flex items-center gap-2 text-xs font-medium leading-none text-muted-foreground select-none"
                aria-hidden="true"
              >
                {tHC("s8")}
              </span>
              <div className="flex h-9 items-center">
                <SwitchToggle
                  on={!!draft.enabled}
                  onClick={() => setDraft((p: any) => ({ ...p, enabled: !p.enabled }))}
                  label={tHC("s7")}
                />
              </div>
            </div>
            <div className="w-40 space-y-1.5">
              <Label htmlFor="smartai-master" className="text-xs text-muted-foreground">
                {tHC("s6")}
              </Label>
              <Input
                id="smartai-master"
                type="number"
                min="0"
                step="0.01"
                value={draft.masterDailyUsd ?? ""}
                onChange={(e) => setDraft((p: any) => ({ ...p, masterDailyUsd: e.target.value }))}
                aria-invalid={!!globalFieldErrors.masterDailyUsd}
              />
              {globalFieldErrors.masterDailyUsd && (
                <p className="text-xs text-destructive">{globalFieldErrors.masterDailyUsd}</p>
              )}
            </div>
            <div className="w-40 space-y-1.5">
              <Label htmlFor="smartai-window" className="text-xs text-muted-foreground">
                {tHC("s5")}
              </Label>
              <Input
                id="smartai-window"
                type="number"
                min="60"
                step="60"
                value={draft.windowSeconds ?? ""}
                onChange={(e) => setDraft((p: any) => ({ ...p, windowSeconds: e.target.value }))}
                aria-invalid={!!globalFieldErrors.windowSeconds}
              />
              {globalFieldErrors.windowSeconds && (
                <p className="text-xs text-destructive">{globalFieldErrors.windowSeconds}</p>
              )}
            </div>
            <div className="w-44 space-y-1.5">
              <Label htmlFor="smartai-default" className="text-xs text-muted-foreground">
                {tHC("s4")}
              </Label>
              <Input
                id="smartai-default"
                type="number"
                min="0"
                step="0.01"
                value={draft.defaultFeatureDailyUsd ?? ""}
                onChange={(e) => setDraft((p: any) => ({ ...p, defaultFeatureDailyUsd: e.target.value }))}
                aria-invalid={!!globalFieldErrors.defaultFeatureDailyUsd}
              />
              {globalFieldErrors.defaultFeatureDailyUsd && (
                <p className="text-xs text-destructive">{globalFieldErrors.defaultFeatureDailyUsd}</p>
              )}
            </div>
            <Button size="sm" onClick={saveGlobal} disabled={!dirty || saving}>
              {saving ? "Saving…" : "Save"}
            </Button>
          </div>

          <div className="pt-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">{tHC("s3")}</div>
          <AnimatePresence initial={false}>
            {SMART_AI_FEATURES.map((f) => {
              const d = featureDrafts[f.key];
              if (!d) {
                return (
                  <div key={f.key} className="rounded-lg border border-border px-4 py-3 text-sm text-muted-foreground">
                    {f.label} {tHC("s2")}
                  </div>
                );
              }
              return (
                <motion.div
                  key={f.key}
                  className="flex flex-wrap items-center gap-4 rounded-lg border border-border px-4 py-3"
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0 }}
                >
                  <div className="min-w-[180px] flex-1">
                    <div className="text-sm font-medium text-foreground">{f.label}</div>
                    <div className="text-xs text-muted-foreground">
                      {f.desc} · <span className="font-mono">{f.key}</span>
                    </div>
                  </div>
                  <SwitchToggle
                    on={!!d.enabled}
                    onClick={() =>
                      setFeatureDrafts((prev) => ({ ...prev, [f.key]: { ...prev[f.key], enabled: !d.enabled } }))
                    }
                    label={`${f.label} enabled`}
                  />
                  <div className="w-36 space-y-1">
                    <Label htmlFor={`smartai-daily-${f.key}`} className="text-xs text-muted-foreground">
                      {tHC("s1")}
                    </Label>
                    <Input
                      id={`smartai-daily-${f.key}`}
                      type="number"
                      min="0"
                      step="0.01"
                      value={d.dailyUsd ?? ""}
                      onChange={(e) =>
                        setFeatureDrafts((prev) => ({ ...prev, [f.key]: { ...prev[f.key], dailyUsd: e.target.value } }))
                      }
                    />
                  </div>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={!featureDirty(f.key) || savingFeature === f.key}
                    onClick={() => saveFeature(f.key)}
                  >
                    {savingFeature === f.key ? "Saving…" : "Save"}
                  </Button>
                </motion.div>
              );
            })}
          </AnimatePresence>
        </>
      )}
    </SectionCard>
  );
}
