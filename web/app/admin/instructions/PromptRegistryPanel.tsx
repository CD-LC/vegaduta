"use client";

import { useCallback, useContext, useEffect, useMemo, useState } from "react";
import { ChevronDown, Info, Search, TriangleAlert } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { api } from "../../lib/api";
import type { PromptRiskClass, PromptScopePath, PromptView } from "../../lib/api/promptRegistry";
import { errorMessage } from "../../lib/errors";
import { AuthContext } from "../../components/AuthProvider";
import EmptyState from "../../components/EmptyState";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "../../components/ui/card";
import { Checkbox } from "../../components/ui/checkbox";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Select } from "../../components/ui/select";
import { Skeleton } from "../../components/ui/skeleton";
import PromptEditor from "./PromptEditor";
import { RISK_CLASSES, isCustomised, matchesQuery, type EditTarget } from "./promptRegistryModel";
import { registrySearch, resolveRequestedAgent } from "./registryDeepLink";

type AgentOption = { id: string; name: string };

/**
 * Every prompt key the platform sends to a model, with what is in force now (the effective wording, which scope
 * supplies it and its version), searchable and filterable by risk class, and editable at the platform, tenant or
 * agent scope. The effective value shown is the one the chosen agent would get when an agent scope is picked.
 *
 * `initialAgentId` (the page's ?agent= parameter) opens the registry at agent scope for that agent once the agent
 * list confirms the person can see it; otherwise it opens at the workspace scope with a notice. The address bar's
 * ?agent= follows the scope picker (set at agent scope, removed otherwise) so the view can be shared and reloaded.
 */
export default function PromptRegistryPanel({ initialAgentId = null }: { initialAgentId?: string | null } = {}) {
  const t = useTranslations("promptDirectives");
  const auth = useContext(AuthContext);
  const canEditPlatform = auth?.isPlatformAdmin === true;

  // The agent the link asked for, until the agent list has confirmed it (or not).
  const [requested, setRequested] = useState<string | null>(initialAgentId);
  const [scope, setScope] = useState<PromptScopePath>(initialAgentId ? "agent" : "tenant");
  const [agentNotice, setAgentNotice] = useState(false);
  const [agents, setAgents] = useState<AgentOption[]>([]);
  const [agentId, setAgentId] = useState<string | null>(null);
  const [views, setViews] = useState<PromptView[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [risk, setRisk] = useState<PromptRiskClass | "ALL">("ALL");
  const [onlyCustomised, setOnlyCustomised] = useState(false);
  const [open, setOpen] = useState<Set<string>>(() => new Set());

  // The agent list is only needed for the agent scope.
  useEffect(() => {
    if (scope !== "agent" || agents.length > 0) return undefined;
    let cancelled = false;
    api
      .listAgents()
      .then((list: Array<{ id: string; name?: string | null }>) => {
        if (cancelled) return;
        const options = list.map((a) => ({ id: a.id, name: a.name || a.id }));
        setAgents(options);
        if (requested) {
          const found = resolveRequestedAgent(requested, options.map((a) => a.id));
          setRequested(null);
          if (found.kind === "agent") setAgentId(found.agentId);
          else {
            setScope("tenant");
            setAgentNotice(found.missing);
          }
        }
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setLoadError(errorMessage(e, t("regAgentsFailed")));
        if (requested) {
          setRequested(null);
          setScope("tenant");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [scope, agents.length, requested, t]);

  // Keep ?agent= in step with the scope picker. Not while the requested agent is still being checked: the link's
  // parameter must survive until it is known whether it is usable.
  useEffect(() => {
    if (requested || typeof window === "undefined") return;
    const next = registrySearch(window.location.search, scope, agentId);
    if (next !== window.location.search) {
      window.history.replaceState(null, "", `${window.location.pathname}${next}${window.location.hash}`);
    }
  }, [requested, scope, agentId]);

  const effectiveAgent = scope === "agent" ? agentId : null;

  const load = useCallback(async () => {
    try {
      const next = await api.listPrompts({ agentId: effectiveAgent });
      setViews(next);
      setLoadError(null);
    } catch (e: unknown) {
      setLoadError(errorMessage(e, t("regLoadFailed")));
    }
  }, [effectiveAgent, t]);

  useEffect(() => {
    if (requested) return;
    if (scope === "agent" && !agentId) {
      setViews(null);
      return;
    }
    void load();
  }, [load, scope, agentId, requested]);

  const visible = useMemo(
    () =>
      (views ?? []).filter(
        (v) =>
          matchesQuery(v, query) &&
          (risk === "ALL" || v.definition.riskClass === risk) &&
          (!onlyCustomised || isCustomised(v.effective) || v.overrides.length > 0)
      ),
    [views, query, risk, onlyCustomised]
  );

  const toggle = (key: string) =>
    setOpen((s) => {
      const n = new Set(s);
      if (n.has(key)) n.delete(key);
      else n.add(key);
      return n;
    });

  const canEdit = scope !== "platform" || canEditPlatform;
  const target: EditTarget = { scope, agentId: effectiveAgent };

  const scopeOptions = [
    { value: "tenant", label: t("regScope_tenant") },
    { value: "agent", label: t("regScope_agent") },
    ...(canEditPlatform ? [{ value: "platform", label: t("regScope_platform") }] : []),
  ];

  return (
    <section aria-label={t("regTitle")} className="flex flex-col gap-4" data-testid="prompt-registry">
      <Card>
        <CardHeader>
          <CardTitle className="text-sm">{t("regTitle")}</CardTitle>
          <CardDescription className="text-xs">{t("regIntro")}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <div className="grid gap-3 md:grid-cols-2">
            <div className="flex flex-col gap-1">
              <Label htmlFor="reg-scope" className="text-xs">
                {t("regScopeLabel")}
              </Label>
              <Select
                id="reg-scope"
                aria-label={t("regScopeLabel")}
                value={scope}
                options={scopeOptions}
                onValueChange={(v) => {
                  setAgentNotice(false);
                  setScope(v as PromptScopePath);
                }}
              />
            </div>
            {scope === "agent" && (
              <div className="flex flex-col gap-1">
                <Label htmlFor="reg-agent" className="text-xs">
                  {t("regAgentLabel")}
                </Label>
                <Select
                  id="reg-agent"
                  aria-label={t("regAgentLabel")}
                  value={agentId ?? ""}
                  placeholder={t("regAgentPlaceholder")}
                  options={agents.map((a) => ({ value: a.id, label: a.name }))}
                  onValueChange={setAgentId}
                />
              </div>
            )}
          </div>

          <div className="grid gap-3 md:grid-cols-[1fr_14rem_auto] md:items-end">
            <div className="flex flex-col gap-1">
              <Label htmlFor="reg-search" className="text-xs">
                {t("regSearchLabel")}
              </Label>
              <div className="relative">
                <Search className="pointer-events-none absolute left-2 top-2.5 size-4 text-muted-foreground" aria-hidden="true" />
                <Input
                  id="reg-search"
                  className="pl-8"
                  value={query}
                  placeholder={t("regSearchPlaceholder")}
                  onChange={(e) => setQuery(e.target.value)}
                />
              </div>
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor="reg-risk" className="text-xs">
                {t("regRiskLabel")}
              </Label>
              <Select
                id="reg-risk"
                aria-label={t("regRiskLabel")}
                value={risk}
                options={[
                  { value: "ALL", label: t("regRiskAll") },
                  ...RISK_CLASSES.map((r) => ({ value: r, label: t(`regRisk_${r}`) })),
                ]}
                onValueChange={(v) => setRisk(v as PromptRiskClass | "ALL")}
              />
            </div>
            <div className="flex items-center gap-2 pb-2">
              <Checkbox id="reg-custom" checked={onlyCustomised} onCheckedChange={(v) => setOnlyCustomised(v === true)} />
              <Label htmlFor="reg-custom" className="text-xs">
                {t("regOnlyCustomised")}
              </Label>
            </div>
          </div>
          {scope === "platform" && !canEditPlatform && (
            <p className="text-xs text-muted-foreground">{t("regPlatformReadOnly")}</p>
          )}
        </CardContent>
      </Card>

      {agentNotice && (
        <Alert variant="info" data-testid="registry-agent-unavailable">
          <Info aria-hidden="true" />
          <AlertDescription>{t("regAgentUnavailable")}</AlertDescription>
        </Alert>
      )}

      {loadError && (
        <Alert variant="destructive">
          <TriangleAlert aria-hidden="true" />
          <AlertTitle>{t("loadFailedTitle")}</AlertTitle>
          <AlertDescription>{loadError}</AlertDescription>
        </Alert>
      )}

      {scope === "agent" && !agentId && !requested && !loadError && (
        <p className="text-sm text-muted-foreground">{t("regPickAgent")}</p>
      )}

      {views === null && !loadError && (scope !== "agent" || agentId || requested) && (
        <div className="flex flex-col gap-3" data-testid="registry-loading">
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
      )}

      {views !== null && (
        <>
          <p className="text-xs text-muted-foreground" role="status">
            {t("regCount", { shown: visible.length, total: views.length })}
          </p>
          {visible.length === 0 ? (
            <EmptyState title={t("regEmptyTitle")} hint={t("regEmptyBody")} />
          ) : (
            visible.map((v) => {
              const d = v.definition;
              const isOpen = open.has(d.key);
              const e = v.effective;
              return (
                <Card key={d.key} data-testid={`prompt-${d.key}`}>
                  <CardHeader className="gap-2">
                    <div className="flex flex-wrap items-center gap-2">
                      <CardTitle className="text-sm">{d.title}</CardTitle>
                      <Badge variant={isCustomised(e) ? "accent" : "secondary"} data-testid={`source-${d.key}`}>
                        {e.source === "DEFAULT" || e.source === "FALLBACK"
                          ? t(`regSource_${e.source}`)
                          : t("regSourceVersion", { source: t(`regSource_${e.source}`), version: e.version })}
                      </Badge>
                      <Badge variant="outline">{t(`regRisk_${d.riskClass}`)}</Badge>
                      {d.securityCritical && <Badge variant="outline">{t("securityCritical")}</Badge>}
                      {!e.enabled && <Badge variant="outline">{t("off")}</Badge>}
                      {d.retired && <Badge variant="outline">{t("regRetired")}</Badge>}
                    </div>
                    <code className="w-fit break-all font-mono text-[11px] text-muted-foreground">{d.key}</code>
                    {d.description && <CardDescription className="text-xs">{d.description}</CardDescription>}
                    <div className="text-xs text-muted-foreground">
                      {e.text === null ? t("regEffectiveOff") : t("regEffectiveTokens", { tokens: e.estimatedTokens })}
                    </div>
                    {e.text !== null && (
                      <pre
                        tabIndex={0}
                        role="region"
                        className="max-h-24 overflow-auto whitespace-pre-wrap break-words rounded border border-border bg-muted p-2 font-sans text-xs"
                        aria-label={t("regEffectiveLabel", { title: d.title })}
                      >
                        {e.text}
                      </pre>
                    )}
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      className="w-fit"
                      aria-expanded={isOpen}
                      aria-controls={`prompt-${d.key}`}
                      onClick={() => toggle(d.key)}
                    >
                      <ChevronDown className={`size-4 transition-transform ${isOpen ? "rotate-180" : ""}`} aria-hidden="true" />
                      {isOpen ? t("regCloseEditor", { title: d.title }) : t("regOpenEditor", { title: d.title })}
                    </Button>
                  </CardHeader>
                  {isOpen && (
                    <CardContent>
                      {d.retired ? (
                        <p className="text-xs text-muted-foreground">{t("regRetiredBody")}</p>
                      ) : (
                        <PromptEditor view={v} target={target} canEdit={canEdit} onChanged={load} />
                      )}
                    </CardContent>
                  )}
                </Card>
              );
            })
          )}
        </>
      )}
    </section>
  );
}
