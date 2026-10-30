// @ts-nocheck
"use client";

// Code Intelligence — console for the code-intel microservice, merged into
// the main app shell (rail item "railCode"). This page + its co-located
// ./lib.js client talk to code-intel DIRECTLY (NEXT_PUBLIC_CODE_INTEL_URL,
// default http://localhost:8090) using the app's Keycloak token; core is not
// in the path for this UI (see the long history note in git / README).
//
// Modernized (2026-07-22, direct feedback: "modernize this code-intel page …
// make it the best Code Intel UI"): rebuilt off the old inline-style /
// `.ci-*` chrome onto the app's canonical shadcn primitives (Card / Button /
// Badge / Input / Textarea / Label / Tabs / Alert) and platform tokens, so it
// reads as the same product as every other redesigned screen. The
// "Index & graph" refresh icon now spins in place from click until the
// index+graph round-trip finishes. All data flow, endpoints, state and i18n
// keys are unchanged — this was a presentation pass.

import { useCallback, useEffect, useId, useState } from "react";
import {
  Boxes,
  Search as SearchIcon,
  GitBranch,
  Play,
  Plus,
  Loader2,
  ShieldCheck,
  BookOpen,
  Sparkles,
  CheckCircle2,
  XCircle,
  FileCode,
  X,
} from "lucide-react";
import AppShell from "../components/AppShell";
import { usePageReady } from "../components/RouteTransition";
import Select from "../components/Select";
import { Button } from "../components/ui/button";
import { Input } from "../components/ui/input";
import { Textarea } from "../components/ui/textarea";
import { Label } from "../components/ui/label";
import { Badge } from "../components/ui/badge";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { Alert, AlertDescription } from "../components/ui/alert";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "../components/ui/tabs";
import { useTranslations } from "../lib/i18n";
import PaginationControls from "../components/PaginationControls";
import { usePagination } from "../lib/usePagination";
import SearchTab from "./SearchTab";
import ReposTab from "./ReposTab";
import GovernanceTab from "./GovernanceTab";
import { CODE_CLS, HIT_CLS, INFO_CLS, HINT_CLS, Toggle, Field } from "./shared";
import { codeIntel, CODE_INTEL_URL, VIA_CORE, errorHintKey } from "./lib";

const TABS = [
  { id: "repos", labelKey: "tabRepos", icon: Boxes },
  { id: "knowledge", labelKey: "tabKnowledge", icon: BookOpen },
  { id: "search", labelKey: "tabSearch", icon: SearchIcon },
  { id: "graph", labelKey: "tabGraph", icon: GitBranch },
  { id: "exec", labelKey: "tabExec", icon: Play },
  { id: "governance", labelKey: "tabGovernance", icon: ShieldCheck },
];

export default function CodeIntelPage() {
  const t = useTranslations("codeIntel");
  const repoSelectId = useId();
  const [tab, setTab] = useState("repos");
  const [repos, setRepos] = useState([]);
  const [repoId, setRepoId] = useState("");
  const [error, setError] = useState(null);
  // Hold the transition cover until the repo list resolves, so the error Alert
  // for a slow/unreachable code-intel service doesn't flash before the real
  // content (see usePageReady / RouteTransition).
  const [loaded, setLoaded] = useState(false);

  const refreshRepos = useCallback(() => {
    codeIntel
      .listRepos()
      .then((list) => {
        setRepos(list || []);
        setRepoId((cur) => cur || (list && list[0]?.repo_id) || "");
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoaded(true));
  }, []);

  useEffect(() => {
    // Reachability check kept only for its error side effect — if code-intel
    // is unreachable the error Alert surfaces it. Deliberately NOT rendered as
    // a "Connected · <auth mode>" status: that leaked the backend (keycloak)
    // and made this screen feel like a separate app. Platform health already
    // lives in the shared AppShell topbar ("COGNITION HEALTHY").
    codeIntel.health().catch((e) => setError(e.message));
    refreshRepos();
  }, [refreshRepos]);
  usePageReady(loaded);

  return (
    <AppShell title={t("title")} eyebrow={t("eyebrow")} wide>
      {error && (
        <Alert variant="destructive" className="relative mb-5 pe-10">
          <XCircle />
          <AlertDescription>
            {error} {t("errorDismissSuffix")} {t(errorHintKey(error), { url: CODE_INTEL_URL })}
          </AlertDescription>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="absolute right-2 top-2 size-7 shrink-0 text-destructive hover:text-destructive"
            onClick={() => setError(null)}
            aria-label="Dismiss"
          >
            <X size={14} />
          </Button>
        </Alert>
      )}

      <Tabs value={tab} onValueChange={setTab}>
        {/* Tab strip (segmented) + repo selector */}
        <div className="mb-5 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <TabsList className="h-auto w-full flex-wrap justify-start gap-1 lg:w-auto">
            {TABS.map((tabItem, idx) => {
              const Icon = tabItem.icon;
              return (
                <TabsTrigger
                  key={tabItem.id}
                  value={tabItem.id}
                  id={`code-intel-tab-${tabItem.id}`}
                  aria-controls={`code-intel-tabpanel-${tabItem.id}`}
                  onKeyDown={(e) => {
                    if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
                    e.preventDefault();
                    const dir = e.key === "ArrowRight" ? 1 : -1;
                    const nextTab = TABS[(idx + dir + TABS.length) % TABS.length];
                    setTab(nextTab.id);
                    document.getElementById(`code-intel-tab-${nextTab.id}`)?.focus();
                  }}
                  className="flex-none gap-1.5 px-3 py-1.5"
                >
                  <Icon size={14} /> {t(tabItem.labelKey)}
                </TabsTrigger>
              );
            })}
          </TabsList>
          <div className="flex shrink-0 items-center gap-2">
            <Label htmlFor={repoSelectId} className="text-xs text-muted-foreground">
              {t("repoLabel")}
            </Label>
            <div className="w-40">
              <Select
                id={repoSelectId}
                value={repoId}
                onChange={(e) => setRepoId(e.target.value)}
                options={repos.map((r) => ({ value: r.repo_id, label: r.repo_id }))}
                placeholder={t("repoSelectPlaceholder")}
              />
            </div>
          </div>
        </div>

        <p className="mb-6 max-w-3xl text-sm leading-relaxed text-muted-foreground">
          <span className="font-medium text-foreground">{t("guideHeading")}</span> {t("guideText")}
        </p>

        <TabsContent value="repos" id="code-intel-tabpanel-repos" aria-labelledby="code-intel-tab-repos">
          <ReposTab repos={repos} repoId={repoId} setRepoId={setRepoId} refresh={refreshRepos} onError={setError} />
        </TabsContent>
        <TabsContent value="knowledge" id="code-intel-tabpanel-knowledge" aria-labelledby="code-intel-tab-knowledge">
          <KnowledgeTab repoId={repoId} onError={setError} />
        </TabsContent>
        <TabsContent value="search" id="code-intel-tabpanel-search" aria-labelledby="code-intel-tab-search">
          <SearchTab repoId={repoId} onError={setError} />
        </TabsContent>
        <TabsContent value="graph" id="code-intel-tabpanel-graph" aria-labelledby="code-intel-tab-graph">
          <GraphTab repoId={repoId} onError={setError} />
        </TabsContent>
        <TabsContent value="exec" id="code-intel-tabpanel-exec" aria-labelledby="code-intel-tab-exec">
          <ExecTab repoId={repoId} onError={setError} />
        </TabsContent>
        <TabsContent value="governance" id="code-intel-tabpanel-governance" aria-labelledby="code-intel-tab-governance">
          <GovernanceTab onError={setError} />
        </TabsContent>
      </Tabs>
    </AppShell>
  );
}

/* ------------------------------------------------------------------ */
/* Repositories                                                        */

function GraphTab({ repoId, onError }) {
  const t = useTranslations("codeIntel");
  const [symbol, setSymbol] = useState("");
  const [mode, setMode] = useState("impact");
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);

  const run = async () => {
    if (!repoId || !symbol) return;
    setBusy(true);
    try {
      const fn = mode === "callers" ? codeIntel.callers : mode === "callees" ? codeIntel.callees : codeIntel.impact;
      setResult(await fn(repoId, symbol));
    } catch (e) {
      onError(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className="mb-4 flex flex-wrap gap-2">
        <div className="w-full sm:w-40 sm:shrink-0">
          <Select
            value={mode}
            onChange={(e) => setMode(e.target.value)}
            options={[
              { value: "impact", label: t("modeOptionImpact") },
              { value: "callers", label: t("modeOptionCallers") },
              { value: "callees", label: t("modeOptionCallees") },
            ]}
          />
        </div>
        <Input
          className="min-w-0 flex-1"
          placeholder={repoId ? "TenantContext  |  ChargeService.charge" : t("selectRepoFirst")}
          value={symbol}
          disabled={!repoId}
          onChange={(e) => setSymbol(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && run()}
        />
        <Button size="sm" disabled={busy || !repoId || !symbol} onClick={run}>
          {busy ? <Loader2 size={15} className="animate-spin" /> : <GitBranch size={15} />} {t("queryButton")}
        </Button>
      </div>
      {result && <pre className={`${CODE_CLS} max-h-[460px]`}>{JSON.stringify(result, null, 2)}</pre>}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Execute & rework                                                   */

function ExecTab({ repoId, onError }) {
  const tHC = useTranslations("hcCodeintelPage");
  const t = useTranslations("codeIntel");
  const [ws, setWs] = useState(null);
  const [command, setCommand] = useState("");
  const [runResult, setRunResult] = useState(null);
  const [fixPath, setFixPath] = useState("");
  const [fixContent, setFixContent] = useState("");
  const [diff, setDiff] = useState("");
  const [busy, setBusy] = useState("");
  const [notice, setNotice] = useState("");

  const create = async () => {
    setBusy("create");
    try {
      setWs(await codeIntel.createWorkspace(repoId));
      setRunResult(null);
      setDiff("");
    } catch (e) {
      onError(e.message);
    } finally {
      setBusy("");
    }
  };

  const run = async () => {
    setBusy("run");
    setNotice("");
    try {
      const r = await codeIntel.runCode(repoId, ws.workspace_id, command);
      if (r?.status) {
        setRunResult(null);
        setNotice(r.message || "Submitted for approval.");
      } else {
        setRunResult(r);
      }
    } catch (e) {
      onError(e.message);
    } finally {
      setBusy("");
    }
  };

  const applyFix = async () => {
    setBusy("apply");
    setNotice("");
    try {
      const r = await codeIntel.applyFiles(repoId, ws.workspace_id, [{ path: fixPath, content: fixContent }]);
      setFixPath("");
      setFixContent("");
      setNotice(r?.message || (r?.written ? `Applied: ${r.written.join(", ")}` : "Change submitted."));
    } catch (e) {
      onError(e.message);
    } finally {
      setBusy("");
    }
  };

  const showDiff = async () => {
    setBusy("diff");
    try {
      const d = await codeIntel.workspaceDiff(repoId, ws.workspace_id);
      setDiff(d.diff || "(no changes)");
    } catch (e) {
      onError(e.message);
    } finally {
      setBusy("");
    }
  };

  if (!repoId) return <p className="text-sm text-muted-foreground">{t("selectRepoFirst")}</p>;

  return (
    <div>
      <div className={INFO_CLS}>
        <b className="font-medium text-foreground">{tHC("s18")}</b> {tHC("s17")}
        <code className="mx-1 rounded border border-border bg-muted px-1.5 py-0.5 font-mono text-[11px]">
          CODE_INTEL_ALLOW_EXEC=true
        </code>
        .)
      </div>

      <div className="mb-4 flex items-center gap-3">
        <Button variant={ws ? "secondary" : "default"} size="sm" disabled={busy === "create"} onClick={create}>
          {busy === "create" ? <Loader2 size={15} className="animate-spin" /> : <Plus size={15} />}
          {ws ? t("newWorkspaceButton") : t("createWorkspaceButton")}
        </Button>
        {ws && <Badge variant="secondary">workspace {ws.workspace_id}</Badge>}
      </div>

      {ws && (
        <>
          <div className="mb-4 flex gap-2">
            <Input
              className="flex-1 font-mono"
              placeholder={tHC("s7")}
              value={command}
              onChange={(e) => setCommand(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && command && run()}
            />
            <Button size="sm" disabled={busy === "run" || !command} onClick={run}>
              {busy === "run" ? <Loader2 size={15} className="animate-spin" /> : <Play size={15} />} {t("runButton")}
            </Button>
          </div>

          {notice && (
            <div className={HINT_CLS}>
              {notice} {VIA_CORE && "See the platform Approvals screen to approve/track it."}
            </div>
          )}

          {runResult && runResult.final && (
            <div
              className={`mb-4 rounded-xl border p-4 shadow-sm ${
                runResult.success ? "border-emerald-500/40 bg-emerald-500/5" : "border-destructive/40 bg-destructive/5"
              }`}
            >
              <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-foreground">
                {runResult.success ? (
                  <CheckCircle2 size={15} className="text-emerald-500" />
                ) : (
                  <XCircle size={15} className="text-destructive" />
                )}
                {runResult.success ? "passed" : "failed"} · exit {runResult.final?.exit_code} ·{" "}
                {runResult.final?.duration_seconds}s{runResult.flaky && " · (flaky: recovered on retry)"}
              </div>
              <pre className={CODE_CLS}>{(runResult.final?.stdout || "") + (runResult.final?.stderr || "")}</pre>
              {!runResult.success && <div className={HINT_CLS}>{runResult.rework_hint}</div>}
            </div>
          )}

          <div className="grid gap-4 lg:grid-cols-2">
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">{tHC("s16")}</CardTitle>
              </CardHeader>
              <CardContent>
                <Field label={tHC("s15")}>
                  <Input
                    placeholder="src/main/java/.../Foo.java"
                    value={fixPath}
                    onChange={(e) => setFixPath(e.target.value)}
                  />
                </Field>
                <Field label={tHC("s14")}>
                  <Textarea
                    className="min-h-40 font-mono text-xs"
                    value={fixContent}
                    onChange={(e) => setFixContent(e.target.value)}
                  />
                </Field>
                <Button variant="secondary" size="sm" disabled={busy === "apply" || !fixPath} onClick={applyFix}>
                  {busy === "apply" ? <Loader2 size={15} className="animate-spin" /> : <FileCode size={15} />}{" "}
                  {t("applyFixButton")}
                </Button>
              </CardContent>
            </Card>

            <Card>
              <CardHeader>
                <CardTitle className="text-sm">{tHC("s13")}</CardTitle>
              </CardHeader>
              <CardContent>
                <Button variant="secondary" size="sm" onClick={showDiff}>
                  <GitBranch size={13} /> {t("showDiffButton")}
                </Button>
                {diff && <pre className={`${CODE_CLS} mt-3 max-h-80`}>{diff}</pre>}
              </CardContent>
            </Card>
          </div>
        </>
      )}

      <AgentResolvePanel repoId={repoId} onError={onError} />
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Autonomous resolve (SDLC agent)                                    */

function AgentResolvePanel({ repoId, onError }) {
  const tHC = useTranslations("hcCodeintelPage");
  const t = useTranslations("codeIntel");
  const [goal, setGoal] = useState("");
  const [testCommand, setTestCommand] = useState("");
  const [maxIterations, setMaxIterations] = useState(4);
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const tracePagination = usePagination(result?.trace || []);

  const run = async () => {
    if (!repoId || !goal || !testCommand) return;
    setBusy(true);
    setResult(null);
    try {
      setResult(await codeIntel.agentResolve(repoId, goal, testCommand, maxIterations));
    } catch (e) {
      onError(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card className="mt-5">
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-sm">
          <Sparkles size={16} className="text-primary" /> {tHC("s12")}
        </CardTitle>
      </CardHeader>
      <CardContent>
        <p className="mb-4 text-sm leading-relaxed text-muted-foreground">{tHC("s11")}</p>
        <Field label={tHC("s10")}>
          <Textarea
            className="min-h-[70px]"
            placeholder={tHC("s9")}
            value={goal}
            onChange={(e) => setGoal(e.target.value)}
          />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={tHC("s8")}>
            <Input
              className="font-mono"
              placeholder={tHC("s7")}
              value={testCommand}
              onChange={(e) => setTestCommand(e.target.value)}
            />
          </Field>
          <Field label={tHC("s6")}>
            <Input
              type="number"
              min={1}
              max={10}
              value={maxIterations}
              onChange={(e) => setMaxIterations(Math.min(10, Math.max(1, Number(e.target.value) || 1)))}
            />
          </Field>
        </div>
        <Button size="sm" disabled={busy || !repoId || !goal || !testCommand} onClick={run}>
          {busy ? <Loader2 size={15} className="animate-spin" /> : <Sparkles size={15} />}
          {busy ? t("resolvingButton") : t("runResolveButton")}
        </Button>

        {result && (
          <div
            className={`mt-4 rounded-xl border p-4 shadow-sm ${
              result.resolved ? "border-emerald-500/40 bg-emerald-500/5" : "border-destructive/40 bg-destructive/5"
            }`}
          >
            <div className="mb-2 flex items-center gap-1.5 text-sm font-semibold text-foreground">
              {result.resolved ? (
                <CheckCircle2 size={15} className="text-emerald-500" />
              ) : (
                <XCircle size={15} className="text-destructive" />
              )}
              {result.resolved ? "resolved" : result.status || "did not resolve"}
              {result.iterations != null && ` · ${result.iterations} iteration${result.iterations === 1 ? "" : "s"}`}
            </div>
            {result.blocked_reason && (
              <div className={HINT_CLS}>
                {result.blocked_reason}
                {result.approval_id &&
                  ` (approval_id: ${result.approval_id} — resolve it on the Guardrails tab, then retry)`}
              </div>
            )}
            {tracePagination.pageItems.map((step) => (
              <div key={step.iteration} className="mb-2">
                <div className="flex items-center gap-1.5 text-xs font-semibold text-foreground">
                  iteration {step.iteration}:
                  {step.success ? (
                    <CheckCircle2 size={13} className="text-emerald-500" aria-label="passed" />
                  ) : (
                    <XCircle size={13} className="text-destructive" aria-label="failed" />
                  )}
                  {step.summary}
                </div>
                {step.output_tail && <pre className={`${CODE_CLS} mt-1`}>{step.output_tail}</pre>}
              </div>
            ))}
            <PaginationControls
              page={tracePagination.page}
              totalPages={tracePagination.totalPages}
              totalItems={tracePagination.totalItems}
              pageSize={tracePagination.pageSize}
              onPageChange={tracePagination.setPage}
              onPageSizeChange={tracePagination.setPageSize}
              itemLabel="iterations"
            />
            {result.diff && (
              <>
                <div className="mt-2.5 text-xs font-semibold text-foreground">{tHC("s5")}</div>
                <pre className={`${CODE_CLS} mt-1 max-h-80`}>{result.diff}</pre>
              </>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}

/* ------------------------------------------------------------------ */
/* Ask the Code (knowledge bridge, grounded + cited)                  */

function KnowledgeTab({ repoId, onError }) {
  const tHC = useTranslations("hcCodeintelPage");
  const t = useTranslations("codeIntel");
  const [q, setQ] = useState("");
  const [result, setResult] = useState(null);
  const [busy, setBusy] = useState(false);
  const [allRepos, setAllRepos] = useState(true);
  const passagesPagination = usePagination(result?.passages || []);

  const run = async () => {
    if (!q) return;
    setBusy(true);
    try {
      setResult(await codeIntel.knowledgeQuery(q, allRepos ? null : repoId));
    } catch (e) {
      onError(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div>
      <div className={INFO_CLS}>
        <b className="font-medium text-foreground">{tHC("s4")}</b> {tHC("s3")}
      </div>
      <div className="mb-4 flex flex-wrap items-center gap-2">
        <Input
          className="min-w-0 flex-1"
          placeholder={tHC("s2")}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && run()}
        />
        <Toggle label={t("allReposLabel")} on={allRepos} onChange={(v) => setAllRepos(v)} />
        <Button size="sm" disabled={busy || !q} onClick={run}>
          {busy ? <Loader2 size={15} className="animate-spin" /> : <BookOpen size={15} />} {t("askButton")}
        </Button>
      </div>
      {result && !result.grounded && <p className="text-sm text-muted-foreground">{tHC("s1")}</p>}
      {passagesPagination.pageItems.map((p, i) => (
        <div key={i} className={HIT_CLS}>
          <div className="mb-2 flex flex-wrap items-center gap-2">
            <span className="font-mono text-xs text-primary">{p.citation}</span>
            {p.symbol && <Badge variant="secondary">{p.symbol}</Badge>}
            <span className="ms-auto font-mono text-xs text-muted-foreground">{(p.score ?? 0).toFixed(3)}</span>
          </div>
          <pre className={CODE_CLS}>{(p.content || "").split("\n").slice(0, 14).join("\n")}</pre>
        </div>
      ))}
      <PaginationControls
        page={passagesPagination.page}
        totalPages={passagesPagination.totalPages}
        totalItems={passagesPagination.totalItems}
        pageSize={passagesPagination.pageSize}
        onPageChange={passagesPagination.setPage}
        onPageSizeChange={passagesPagination.setPageSize}
        itemLabel="passages"
      />
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Guardrails: policy, pending approvals, audit trail                 */
