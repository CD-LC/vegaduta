"use client";

// Per-plan caps and feature gates (Tenants & Users console, page.tsx): loads
// every plan's limits row, edits a draft per plan, saves one plan at a time.

import { useCallback, useEffect, useState } from "react";
import { motion } from "motion/react";
import { Sliders } from "lucide-react";
import { api } from "../lib/api";
import { toast } from "../lib/toastBus";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { SwitchToggle } from "../components/sections/common";
import { LIMIT_NUMBER_FIELDS, LIMIT_TOGGLE_FIELDS, PLAN_OPTIONS } from "./tenantOptions";
import { useTranslations } from "../../lib/i18n";

export function PlanLimitsCard() {
    const tHC = useTranslations("hcPlatformadminTenantsPlanLimitsCard");
  const [planLimits, setPlanLimits] = useState<Record<string, Record<string, any>>>({});
  const [draftLimits, setDraftLimits] = useState<Record<string, Record<string, any>>>({});
  const [limitsLoading, setLimitsLoading] = useState(true);
  const [limitsError, setLimitsError] = useState(null);
  const [savingLimitsPlan, setSavingLimitsPlan] = useState(null);

  const refreshLimits = useCallback(async () => {
    setLimitsLoading(true);
    try {
      const rows = await api.getPlatformPlanLimits();
      const byPlan: Record<string, Record<string, any>> = {};
      for (const row of rows || []) byPlan[row.plan] = row;
      setPlanLimits(byPlan);
      setDraftLimits(byPlan);
      setLimitsError(null);
    } catch (err) {
      setLimitsError((err as Error).message);
    }
    setLimitsLoading(false);
  }, []);

  function handleLimitFieldChange(plan: string, field: string, value: any) {
    setDraftLimits((prev) => ({ ...prev, [plan]: { ...prev[plan], [field]: value } }));
  }

  function isLimitsDirty(plan: string) {
    const saved = planLimits[plan];
    const draft = draftLimits[plan];
    if (!saved || !draft) return false;
    return [...LIMIT_NUMBER_FIELDS, ...LIMIT_TOGGLE_FIELDS].some((f) => saved[f.key] !== draft[f.key]);
  }

  async function handleSaveLimits(plan: string) {
    const draft = draftLimits[plan];
    if (!draft) return;
    setSavingLimitsPlan(plan);
    try {
      const updated = await api.updatePlatformPlanLimits(plan, draft);
      setPlanLimits((prev) => ({ ...prev, [plan]: updated }));
      setDraftLimits((prev) => ({ ...prev, [plan]: updated }));
      toast.success(`${plan} plan limits saved.`);
    } catch (err) {
      toast.error((err as Error).message || `Could not save ${plan} plan limits.`);
    } finally {
      setSavingLimitsPlan(null);
    }
  }

  useEffect(() => {
    refreshLimits().catch((e) => console.error("Failed to load plan limits", e));
  }, [refreshLimits]);

  return (
    <motion.div
      className="mt-6"
      initial={{ opacity: 0, y: 12 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.35, ease: [0.16, 1, 0.3, 1], delay: 0.05 }}
    >
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-sm">
            <Sliders size={16} className="text-primary" /> {tHC("s5")}
          </CardTitle>
          <p className="text-xs text-muted-foreground">
            {tHC("s4")}
          </p>
        </CardHeader>
        <CardContent>
          {limitsError && (
            <div className="mb-3 rounded-lg border border-destructive/30 bg-destructive/10 px-4 py-3 text-sm text-destructive">
              {limitsError}
            </div>
          )}
          {limitsLoading && !limitsError && <p className="text-sm text-muted-foreground">{tHC("s3")}</p>}
          {!limitsLoading && !limitsError && Object.keys(draftLimits).length === 0 && (
            <p className="text-sm text-muted-foreground">{tHC("s2")}</p>
          )}

          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {PLAN_OPTIONS.map(({ value: plan, label }) => {
              const draft = draftLimits[plan];
              if (!draft) return null;
              const dirty = isLimitsDirty(plan);
              return (
                <div key={plan} className="flex flex-col gap-3 rounded-lg border border-border p-4">
                  <div className="text-xs font-semibold uppercase tracking-wide text-foreground">{label}</div>

                  {LIMIT_NUMBER_FIELDS.map((f) => (
                    <div className="space-y-1.5" key={f.key}>
                      <Label htmlFor={`limit-${plan}-${f.key}`} className="text-xs text-muted-foreground">
                        {f.label}
                      </Label>
                      <Input
                        id={`limit-${plan}-${f.key}`}
                        type="number"
                        min={-1}
                        value={draft[f.key] ?? ""}
                        onChange={(e) =>
                          handleLimitFieldChange(plan, f.key, e.target.value === "" ? "" : Number(e.target.value))
                        }
                      />
                      <span className="text-[11px] text-muted-foreground">{tHC("s1")}</span>
                    </div>
                  ))}

                  {LIMIT_TOGGLE_FIELDS.map((f) => (
                    <div key={f.key} className="flex items-center justify-between gap-2">
                      <span className="text-xs text-muted-foreground">{f.label}</span>
                      <SwitchToggle
                        on={draft[f.key]}
                        label={f.label}
                        onClick={() => handleLimitFieldChange(plan, f.key, !draft[f.key])}
                      />
                    </div>
                  ))}

                  <Button
                    size="sm"
                    className="mt-1"
                    disabled={!dirty || savingLimitsPlan === plan}
                    onClick={() => handleSaveLimits(plan)}
                  >
                    {savingLimitsPlan === plan ? "Saving…" : "Save"}
                  </Button>
                </div>
              );
            })}
          </div>
        </CardContent>
      </Card>
    </motion.div>
  );
}
