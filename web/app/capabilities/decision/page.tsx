"use client";

// /capabilities/decision - the tenant console for the DECISION capability on
// the generic /api/capabilities surface (CapabilityController in
// core/providers + DecisionCapabilityController in core/agents).
//
// A decision provider is NOT a chat provider - it never generates text. It
// answers typed questions (noul yes/no probability, choice over options,
// score over levels) and powers the platform's decision layer: cost routing
// on chat turns and Quick Task agent selection. This page exists so a tenant
// can (a) SEE whose key the active decision provider is running on for their
// traffic - theirs, the platform's, or none; (b) bring their own key for ANY
// provider the catalog lists, which then takes precedence for their
// workspace; and (c) experiment against that resolved key without ever
// touching it - the playground is a bounded server-side pass-through.
//
// The provider list is data-driven off GET /api/capabilities: a newly
// onboarded decision provider appears here with no frontend change.
//
// Page/panel split follows /secrets: this file owns fetching and route
// readiness; the interactive bits are plain sections below because the three
// concerns (status, credentials, playground) share one refresh cycle.

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { ArrowRight, CheckCircle2, KeyRound, Play, Plus, ShieldAlert, SlidersHorizontal, Trash2, X } from "lucide-react";
import {
  api,
  type CapabilityDescriptor,
  type CapabilityProvider,
  type DecisionPlaygroundResponse,
  type DecisionPolicy,
  type DecisionQuestion,
  type DecisionStatus,
} from "../../lib/api";
import AppShell from "../../components/AppShell";
import { usePageReady } from "../../components/RouteTransition";
import PageHeader from "../../components/ax/PageHeader";
import { useAuth } from "../../components/AuthProvider";
import { useTranslations } from "../../lib/i18n";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../../components/ui/card";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Textarea } from "../../components/ui/textarea";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../components/ui/alert-dialog";

type QuestionType = "noul" | "choice" | "score";

const CAPABILITY = "decision";

export default function DecisionCapabilityPage() {
  const t = useTranslations("decisionCapabilityPage");
  const { hasPermission } = useAuth();
  const canManage = hasPermission("MANAGE_INTEGRATIONS");

  const [catalog, setCatalog] = useState<CapabilityDescriptor[]>([]);
  const [status, setStatus] = useState<DecisionStatus | null>(null);
  const [policy, setPolicy] = useState<DecisionPolicy | null>(null);
  const [loaded, setLoaded] = useState(false);

  const providers: CapabilityProvider[] =
    catalog.find((c) => c.key === CAPABILITY)?.providers ?? [];

  const refresh = useCallback(() => {
    api
      .listCapabilities()
      .then(setCatalog)
      .catch(() => setCatalog([]))
      .finally(() => setLoaded(true));
    api
      .getDecisionStatus()
      .then(setStatus)
      .catch(() => setStatus(null));
    api
      .getCapabilityPolicy(CAPABILITY)
      .then(setPolicy)
      .catch(() => setPolicy(null));
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);
  usePageReady(loaded);

  // --- Credential form state (per provider row) ----------------------------
  const [apiKeys, setApiKeys] = useState<Record<string, string>>({});
  const [activating, setActivating] = useState<string | null>(null);
  const [confirmDeactivate, setConfirmDeactivate] = useState<string | null>(null);
  const [deactivating, setDeactivating] = useState(false);

  const activate = (providerKey: string) => {
    const key = (apiKeys[providerKey] ?? "").trim();
    if (!key) return;
    setActivating(providerKey);
    api
      .activateCapabilityCredential(CAPABILITY, providerKey, key)
      .then(() => {
        setApiKeys((m) => ({ ...m, [providerKey]: "" }));
        refresh();
      })
      .catch(() => {
        /* toJson already raised the global error toast */
      })
      .finally(() => setActivating(null));
  };

  const deactivate = (providerKey: string) => {
    setDeactivating(true);
    api
      .deactivateCapabilityCredential(CAPABILITY, providerKey)
      .then(refresh)
      .catch(() => {})
      .finally(() => {
        setDeactivating(false);
        setConfirmDeactivate(null);
      });
  };

  // --- Decision policy state -----------------------------------------------
  // Tri-state draft: null = "Inherit platform default" (also the reset). The
  // raw overrides map omits inherited keys entirely, so a missing key maps to
  // null here and a null drafts back to an omitted key on PUT.
  const [policyDraft, setPolicyDraft] = useState<{
    costRouting: boolean | null;
    agentRouting: boolean | null;
    simpleThreshold: string;
  }>({ costRouting: null, agentRouting: null, simpleThreshold: "" });
  const [policySaving, setPolicySaving] = useState(false);

  useEffect(() => {
    if (policy === null) return;
    setPolicyDraft({
      costRouting: policy.overrides.costRouting ?? null,
      agentRouting: policy.overrides.agentRouting ?? null,
      simpleThreshold:
        policy.overrides.simpleThreshold != null ? String(policy.overrides.simpleThreshold) : "",
    });
  }, [policy]);

  const thresholdInvalid =
    policyDraft.simpleThreshold.trim() !== "" &&
    (!Number.isFinite(Number(policyDraft.simpleThreshold)) ||
      Number(policyDraft.simpleThreshold) < 0.5 ||
      Number(policyDraft.simpleThreshold) > 0.99);

  const savePolicy = () => {
    if (thresholdInvalid) return;
    setPolicySaving(true);
    const overrides: Record<string, unknown> = {};
    if (policyDraft.costRouting !== null) overrides.costRouting = policyDraft.costRouting;
    if (policyDraft.agentRouting !== null) overrides.agentRouting = policyDraft.agentRouting;
    if (policyDraft.simpleThreshold.trim() !== "") {
      overrides.simpleThreshold = Number(policyDraft.simpleThreshold);
    }
    api
      .updateCapabilityPolicy(CAPABILITY, overrides)
      .then(setPolicy)
      .catch(() => {
        /* toJson already raised the global error toast */
      })
      .finally(() => setPolicySaving(false));
  };

  const tri = (v: boolean | null) => (v === null ? "inherit" : v ? "on" : "off");
  const untri = (s: string) => (s === "on" ? true : s === "off" ? false : null);

  // --- Playground state ----------------------------------------------------
  const [qType, setQType] = useState<QuestionType>("noul");
  const [state, setState] = useState("");
  const [instructions, setInstructions] = useState("");
  const [choices, setChoices] = useState<Array<{ key: string; label: string }>>([
    { key: "", label: "" },
    { key: "", label: "" },
  ]);
  const [levels, setLevels] = useState<string[]>(["", ""]);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<DecisionPlaygroundResponse | null>(null);
  const [playError, setPlayError] = useState<string | null>(null);

  const runPlayground = () => {
    const question: DecisionQuestion = { type: qType, instructions: instructions.trim() };
    if (qType === "choice") {
      const criteria: Record<string, string> = {};
      for (const c of choices) {
        if (c.key.trim()) criteria[c.key.trim()] = c.label.trim();
      }
      question.criteria = criteria;
    } else if (qType === "score") {
      question.criteria = levels.map((l) => l.trim()).filter(Boolean);
    }
    setRunning(true);
    setResult(null);
    setPlayError(null);
    api
      .runDecisionPlayground(state, { q: question })
      .then(setResult)
      .catch((e) => setPlayError(e?.message || "Playground call failed"))
      .finally(() => setRunning(false));
  };

  // The human name of the provider whose key resolves - catalog displayName
  // when the row is known, the raw provider_key otherwise.
  const activeProviderName =
    providers.find((p) => p.providerKey === status?.providerKey)?.displayName ??
    status?.providerKey ??
    null;

  const keySourceBadge =
    status?.source === "TENANT" ? (
      <Badge variant="state" className="bg-emerald-500/15 text-emerald-600 dark:text-emerald-400">
        {t("sourceTenant")}
      </Badge>
    ) : status?.source === "PLATFORM" ? (
      <Badge variant="state">{t("sourcePlatform")}</Badge>
    ) : status?.source === "ENV" ? (
      <Badge variant="state">{t("sourceEnv")}</Badge>
    ) : (
      <Badge variant="state" className="text-muted-foreground">
        {t("sourceNone")}
      </Badge>
    );

  return (
    <AppShell eyebrow={t("pageEyebrow")}>
      <PageHeader
        title={t("pageTitle")}
        subtitle={t("pageSubtitle")}
        actions={
          <Button asChild variant="outline" size="sm">
            <Link href="/providers">
              {t("providersLink")} <ArrowRight size={14} />
            </Link>
          </Button>
        }
      />

      {/* Status banner - "is a decision provider configured, and whose key is it?" */}
      <Card className="mb-6">
        <CardHeader>
          <div className="flex items-center justify-between gap-3">
            <CardTitle>{t("statusTitle")}</CardTitle>
            {keySourceBadge}
          </div>
          <CardDescription>
            {!status?.enabled
              ? t("statusDisabled")
              : status.configured
                ? status.source === "TENANT"
                  ? t("statusTenant", { provider: activeProviderName ?? "" })
                  : t("statusPlatform", { provider: activeProviderName ?? "" })
                : t("statusNotConfigured")}
          </CardDescription>
        </CardHeader>
      </Card>

      {/* BYOK credentials - one section per provider the catalog lists for
          this capability. Data-driven: a newly registered provider renders
          here with no frontend change. */}
      <Card className="mb-6">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <KeyRound size={16} /> {t("credentialTitle")}
          </CardTitle>
          <CardDescription>{t("credentialSubtitle")}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-6">
          {providers.length === 0 && (
            <p className="text-sm text-muted-foreground">{t("credentialNone")}</p>
          )}
          {providers.map((p) => (
            <div key={p.providerKey} className="flex flex-col gap-3 border-b border-border pb-5 last:border-b-0 last:pb-0">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex items-center gap-3">
                  {p.credential?.status === "ACTIVE" ? (
                    <CheckCircle2 size={16} className="text-emerald-500" />
                  ) : (
                    <ShieldAlert size={16} className="text-muted-foreground" />
                  )}
                  <div className="text-sm">
                    <span className="font-medium">{p.displayName}</span>{" "}
                    {p.credential ? (
                      <span className="text-muted-foreground">
                        {t("credentialSince", {
                          date: new Date(p.credential.createdAt).toLocaleDateString(),
                        })}
                      </span>
                    ) : (
                      <span className="text-muted-foreground">{t("credentialNotStored")}</span>
                    )}
                  </div>
                  {p.credential && (
                    <Badge variant="state">
                      {p.credential.status === "ACTIVE"
                        ? t("statusActive")
                        : p.credential.status === "INVALID"
                          ? t("statusInvalid")
                          : t("statusInactive")}
                    </Badge>
                  )}
                </div>
                {canManage && p.credential && (
                  <Button variant="outline" size="sm" onClick={() => setConfirmDeactivate(p.providerKey)}>
                    <Trash2 size={14} /> {t("deactivate")}
                  </Button>
                )}
              </div>

              {canManage && (
                <form
                  className="flex flex-col gap-2 sm:flex-row"
                  onSubmit={(e) => {
                    e.preventDefault();
                    activate(p.providerKey);
                  }}
                >
                  <div className="flex-1">
                    <Label htmlFor={`decision-key-${p.providerKey}`} className="sr-only">
                      {t("keyLabel", { provider: p.displayName })}
                    </Label>
                    <Input
                      id={`decision-key-${p.providerKey}`}
                      type="password"
                      autoComplete="off"
                      placeholder={t("keyPlaceholder", { provider: p.displayName })}
                      value={apiKeys[p.providerKey] ?? ""}
                      onChange={(e) =>
                        setApiKeys((m) => ({ ...m, [p.providerKey]: e.target.value }))
                      }
                    />
                  </div>
                  <Button
                    type="submit"
                    disabled={activating === p.providerKey || !(apiKeys[p.providerKey] ?? "").trim()}
                  >
                    {activating === p.providerKey
                      ? t("activating")
                      : p.credential
                        ? t("rotateKey")
                        : t("activate")}
                  </Button>
                </form>
              )}
            </div>
          ))}
          <p className="text-xs text-muted-foreground">{t("keyHelp")}</p>
        </CardContent>
      </Card>

      {/* Decision controls - WHERE the decision model may act, not just whose
          key funds it. Tri-state per feature (Inherit/On/Off) + the
          workspace-wide "is this turn simple" bar. Per-agent overrides live in
          the agent's own cost-routing editor - linked below. */}
      <Card className="mb-6">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <SlidersHorizontal size={16} /> {t("policyTitle")}
          </CardTitle>
          <CardDescription>{t("policySubtitle")}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {policy === null ? (
            <p className="text-sm text-muted-foreground">{t("policyLoading")}</p>
          ) : (
            <>
              {(["costRouting", "agentRouting"] as const).map((field) => (
                <div key={field} className="flex flex-wrap items-center justify-between gap-3">
                  <div className="min-w-0">
                    <div className="text-sm font-medium text-foreground">{t(`policy_${field}`)}</div>
                    <div className="text-xs text-muted-foreground">{t(`policy_${field}Help`)}</div>
                  </div>
                  <div className="flex gap-1">
                    {(["inherit", "on", "off"] as const).map((opt) => (
                      <Button
                        key={opt}
                        type="button"
                        size="sm"
                        variant={tri(policyDraft[field]) === opt ? "default" : "outline"}
                        disabled={!canManage}
                        onClick={() => setPolicyDraft((d) => ({ ...d, [field]: untri(opt) }))}
                      >
                        {t(`policyOpt_${opt}`)}
                      </Button>
                    ))}
                  </div>
                </div>
              ))}
              <div className="flex flex-wrap items-center gap-2">
                <Label htmlFor="decision-policy-threshold" className="text-sm text-foreground">
                  {t("policyThreshold")}
                </Label>
                <Input
                  id="decision-policy-threshold"
                  type="number"
                  min={0.5}
                  max={0.99}
                  step={0.05}
                  className="w-24"
                  placeholder="0.75"
                  value={policyDraft.simpleThreshold}
                  onChange={(e) => setPolicyDraft((d) => ({ ...d, simpleThreshold: e.target.value }))}
                  disabled={!canManage}
                />
                <span className="text-xs text-muted-foreground">{t("policyThresholdHelp")}</span>
              </div>
              {canManage && (
                <div>
                  <Button type="button" size="sm" onClick={savePolicy} disabled={policySaving || thresholdInvalid}>
                    {policySaving ? t("saving") : t("policySave")}
                  </Button>
                </div>
              )}
              <p className="text-xs text-muted-foreground">{t("policyPropagation")}</p>
            </>
          )}
        </CardContent>
      </Card>

      {/* Playground */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Play size={16} /> {t("playgroundTitle")}
          </CardTitle>
          <CardDescription>{t("playgroundSubtitle")}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <div className="flex gap-2">
            {(["noul", "choice", "score"] as QuestionType[]).map((qt) => (
              <Button
                key={qt}
                variant={qType === qt ? "default" : "outline"}
                size="sm"
                onClick={() => setQType(qt)}
              >
                {qt}
              </Button>
            ))}
          </div>
          <p className="text-xs text-muted-foreground">{t(`typeHelp_${qType}`)}</p>

          <div className="flex flex-col gap-2">
            <Label htmlFor="decision-state">{t("stateLabel")}</Label>
            <Textarea
              id="decision-state"
              rows={3}
              placeholder={t("statePlaceholder")}
              value={state}
              onChange={(e) => setState(e.target.value)}
            />
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="decision-instructions">{t("instructionsLabel")}</Label>
            <Input
              id="decision-instructions"
              placeholder={t(`instructionsPlaceholder_${qType}`)}
              value={instructions}
              onChange={(e) => setInstructions(e.target.value)}
            />
          </div>

          {qType === "choice" && (
            <div className="flex flex-col gap-2">
              <Label>{t("optionsLabel")}</Label>
              {choices.map((c, i) => (
                <div key={i} className="flex gap-2">
                  <Input
                    className="w-40"
                    placeholder={t("optionKeyPlaceholder")}
                    value={c.key}
                    onChange={(e) =>
                      setChoices(choices.map((x, j) => (j === i ? { ...x, key: e.target.value } : x)))
                    }
                  />
                  <Input
                    className="flex-1"
                    placeholder={t("optionLabelPlaceholder")}
                    value={c.label}
                    onChange={(e) =>
                      setChoices(choices.map((x, j) => (j === i ? { ...x, label: e.target.value } : x)))
                    }
                  />
                  {choices.length > 2 && (
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => setChoices(choices.filter((_, j) => j !== i))}
                    >
                      <X size={14} />
                    </Button>
                  )}
                </div>
              ))}
              <Button
                variant="outline"
                size="sm"
                className="self-start"
                onClick={() => setChoices([...choices, { key: "", label: "" }])}
              >
                <Plus size={14} /> {t("addOption")}
              </Button>
            </div>
          )}

          {qType === "score" && (
            <div className="flex flex-col gap-2">
              <Label>{t("levelsLabel")}</Label>
              {levels.map((lvl, i) => (
                <div key={i} className="flex gap-2">
                  <Input
                    placeholder={t("levelPlaceholder", { index: i + 1 })}
                    value={lvl}
                    onChange={(e) =>
                      setLevels(levels.map((x, j) => (j === i ? e.target.value : x)))
                    }
                  />
                  {levels.length > 2 && (
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={() => setLevels(levels.filter((_, j) => j !== i))}
                    >
                      <X size={14} />
                    </Button>
                  )}
                </div>
              ))}
              {levels.length < 10 && (
                <Button
                  variant="outline"
                  size="sm"
                  className="self-start"
                  onClick={() => setLevels([...levels, ""])}
                >
                  <Plus size={14} /> {t("addLevel")}
                </Button>
              )}
            </div>
          )}

          {/* Runs on the tenant's decision key, so the server gates it on
              MANAGE_INTEGRATIONS like the key itself. */}
          <div>
            <Button onClick={runPlayground} disabled={!canManage || running || !instructions.trim()}>
              <Play size={14} /> {running ? t("running") : t("run")}
            </Button>
          </div>
          {!canManage && (
            <p className="text-sm text-muted-foreground" data-testid="decision-playground-needs-permission">
              {t("playgroundNeedsPermission")}
            </p>
          )}

          {playError && (
            <p className="text-sm text-destructive">{playError}</p>
          )}

          {result && (
            <div className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                <Badge variant="state">{result.model}</Badge>
                <Badge variant="state">{t(`source_${result.keySource}`)}</Badge>
                {Object.entries(result.usage || {}).map(([k, v]) => (
                  <span key={k}>
                    {k}: {String(v)}
                  </span>
                ))}
              </div>
              <pre className="max-h-96 overflow-auto rounded-lg border border-border bg-muted/40 p-4 text-xs">
                {JSON.stringify(result.answers, null, 2)}
              </pre>
            </div>
          )}
        </CardContent>
      </Card>

      <AlertDialog
        open={confirmDeactivate !== null}
        onOpenChange={(open) => {
          if (!open) setConfirmDeactivate(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {t("deactivateTitle", {
                provider:
                  providers.find((p) => p.providerKey === confirmDeactivate)?.displayName ??
                  confirmDeactivate ??
                  "",
              })}
            </AlertDialogTitle>
            <AlertDialogDescription>{t("deactivateBody")}</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>{t("cancel")}</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => confirmDeactivate && deactivate(confirmDeactivate)}
              disabled={deactivating}
            >
              {deactivating ? t("deactivating") : t("deactivate")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </AppShell>
  );
}
