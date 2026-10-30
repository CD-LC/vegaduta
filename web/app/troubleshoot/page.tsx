"use client";

// /troubleshoot - the owner's "something is wrong" panel for a personal/local
// install. Checks the things that actually break an agentic turn from the
// browser's point of view (core API reachable, the local model server answers,
// the recommended model is pulled, tool calling works) and offers the real
// fix for each - including one-click model pulls. What the page cannot see
// (Docker, host ports) is delegated to `scripts/vegaduta doctor --personal`.
import { useCallback, useEffect, useState } from "react";
import {
  LifeBuoy,
  RefreshCw,
  Download,
  Wrench,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  GraduationCap,
} from "lucide-react";
import AppShell from "../components/AppShell";
import PageHeader from "../components/ax/PageHeader";
import { Button } from "../components/ui/button";
import { localModelsApi, type LocalModelChoice, type LocalModelRecommendation } from "../lib/api/localModels";
import { localModelAllowed, tierCommand } from "../lib/personalInstall";
import { localModelManagedHere } from "../lib/api/localModels";
import { academyHref, hasAcademy } from "../lib/academyUrl";
import { useTranslations } from "../lib/i18n";

type State = "run" | "ok" | "warn" | "fail";

interface Check {
  key: string;
  state: State;
  detail?: string;
}

const MARK: Record<State, React.ReactNode> = {
  run: <RefreshCw className="h-4 w-4 animate-spin text-muted-foreground" />,
  ok: <CheckCircle2 className="h-4 w-4 text-emerald-500" />,
  warn: <AlertTriangle className="h-4 w-4 text-amber-500" />,
  fail: <XCircle className="h-4 w-4 text-destructive" />,
};

export default function TroubleshootPage() {
    const tHC = useTranslations("hcTroubleshootPage");
  const t = useTranslations("troubleshoot");
  const [rec, setRec] = useState<LocalModelRecommendation | null>(null);
  const [checks, setChecks] = useState<Check[]>([
    { key: "api", state: "run" },
    { key: "server", state: "run" },
    { key: "model", state: "run" },
  ]);
  const [pullPct, setPullPct] = useState<number | null>(null);
  const [pulling, setPulling] = useState(false);
  const [using, setUsing] = useState<string | null>(null);
  const [useNote, setUseNote] = useState<{ state: State; text: string } | null>(null);
  const [probe, setProbe] = useState<{ state: State; text: string } | null>(null);
  // Personal installs always diagnose their local model; on lite/full the
  // page opens when the install actually runs one (a reachable model server
  // or a pool row pointing at it), so a self-hosted operator still gets the
  // checks while a hosted tenant sees the shared notice.
  const [allowed, setAllowed] = useState<boolean | null>(() => (localModelAllowed() ? true : null));

  const set = (key: string, state: State, detail?: string) =>
    setChecks((cs) => cs.map((c) => (c.key === key ? { ...c, state, detail } : c)));

  const run = useCallback(async () => {
    if (allowed !== true) return;
    setRec(null);
    setProbe(null);
    setUseNote(null);
    setChecks([
      { key: "api", state: "run" },
      { key: "server", state: "run" },
      { key: "model", state: "run" },
    ]);
    let r: LocalModelRecommendation;
    try {
      r = await localModelsApi.getLocalModelRecommendation();
    } catch {
      set("api", "fail", t("apiFail"));
      set("server", "run", t("skipped"));
      set("model", "run", t("skipped"));
      return;
    }
    setRec(r);
    set("api", "ok", t("apiOk"));

    if (!r.ollama.reachable) {
      set("server", "fail", t("serverFail", { url: r.ollama.baseUrl, cmd: tierCommand("") }));
      set("model", "run", t("skipped"));
      return;
    }
    set("server", "ok", t("serverOk", { url: r.ollama.baseUrl }));

    const want = r.recommended.tag;
    if (r.ollama.installedTags.includes(want)) {
      set("model", "ok", t("modelOk", { tag: want }));
    } else {
      set("model", "fail", t("modelMissing", { tag: want }));
    }
  }, [t, allowed]);

  useEffect(() => {
    if (allowed !== null) return undefined;
    let live = true;
    void localModelManagedHere().then((v) => {
      if (live) setAllowed(v);
    });
    return () => {
      live = false;
    };
  }, [allowed]);

  useEffect(() => {
    void run();
  }, [run]);

  const pull = useCallback(
    async (tag: string) => {
      if (!rec) return;
      setPulling(true);
      setPullPct(0);
      try {
        const { jobId } = await localModelsApi.startLocalModelPull(tag);
        for (;;) {
          await new Promise((r) => setTimeout(r, 1500));
          const st = await localModelsApi.getLocalModelPull(jobId);
          setPullPct(st.percent);
          if (st.status === "done") {
            await run();
            break;
          }
          if (st.status === "failed") {
            set("model", "fail", st.message || t("pullFailed"));
            break;
          }
        }
      } catch {
        set("model", "fail", t("pullFailed"));
      } finally {
        setPulling(false);
      }
    },
    [rec, run, t]
  );

  // Runtime model switch: repoints the local pool row (or creates it when the
  // install never got one) - no restart. See POST /api/local-models/use.
  const switchToModel = useCallback(
    async (tag: string) => {
      if (!rec) return;
      setUsing(tag);
      setUseNote(null);
      try {
        const r = await localModelsApi.useLocalModel(tag);
        await run();
        setUseNote({
          state: r.enabled ? "ok" : "warn",
          text: r.enabled ? t(r.created ? "useCreated" : "useDone", { tag }) : t("useDisabled", { tag }),
        });
      } catch {
        setUseNote({ state: "fail", text: t("useFailed", { tag }) });
      } finally {
        setUsing(null);
      }
    },
    [rec, run, t]
  );

  const testTools = useCallback(async () => {
    if (!rec) return;
    setProbe({ state: "run", text: t("probing") });
    try {
      const p = await localModelsApi.probeLocalModel(rec.recommended.tag);
      const ok = p.toolCalling !== "none";
      setProbe({
        state: ok ? "ok" : "warn",
        text: ok ? t("probeOk", { grade: p.toolCalling, ms: p.latencyMs }) : t("probeWarn"),
      });
    } catch {
      setProbe({ state: "fail", text: t("probeFail") });
    }
  }, [rec, t]);

  const modelCheck = checks.find((c) => c.key === "model");

  return (
    <AppShell eyebrow={t("eyebrow")}>
      <PageHeader
        title={t("title")}
        subtitle={t("subtitle")}
        actions={
          <Button variant="outline" size="sm" onClick={() => void run()}>
            <RefreshCw className="me-2 h-4 w-4" />
            {t("recheck")}
          </Button>
        }
      />
      <div className="mx-auto w-full max-w-3xl space-y-3 px-4 pb-16">
        {allowed === false && (
          <div
            className="flex items-start gap-3 rounded-lg border border-border bg-card p-4"
            data-testid="troubleshoot-shared-notice"
          >
            <span className="mt-0.5 shrink-0">
              <LifeBuoy className="h-4 w-4 text-muted-foreground" />
            </span>
            <p className="text-sm text-muted-foreground">{t("sharedBody")}</p>
          </div>
        )}
        {allowed !== false && (
          <>
            {checks.map((c) => (
              <div key={c.key} className="flex items-start gap-3 rounded-lg border border-border bg-card p-4">
                <span className="mt-0.5 shrink-0">{MARK[c.state]}</span>
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium">{t(`check.${c.key}`)}</p>
                  {c.detail && <p className="mt-0.5 text-sm text-muted-foreground">{c.detail}</p>}
                  {c.key === "model" && c.state === "fail" && rec && (
                    <div className="mt-2">
                      <Button size="sm" disabled={pulling} onClick={() => void pull(rec.recommended.tag)}>
                        <Download className="me-2 h-4 w-4" />
                        {pulling
                          ? t("pulling", { pct: pullPct ?? 0 })
                          : t("pullModel", { tag: rec.recommended.tag, gb: rec.recommended.downloadGb })}
                      </Button>
                      {pulling && pullPct != null && (
                        <div className="mt-2 h-1.5 overflow-hidden rounded bg-muted">
                          <div className="h-full bg-primary transition-all" style={{ width: `${pullPct}%` }} />
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>
            ))}

            <div className="flex items-start gap-3 rounded-lg border border-border bg-card p-4">
              <span className="mt-0.5 shrink-0">
                <Wrench className="h-4 w-4 text-muted-foreground" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium">{t("check.tools")}</p>
                <p className="mt-0.5 text-sm text-muted-foreground">{probe ? probe.text : t("toolsBody")}</p>
                {probe?.state === "run" || null}
                <div className="mt-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={!rec || !rec.ollama.reachable || modelCheck?.state !== "ok" || probe?.state === "run"}
                    onClick={() => void testTools()}
                  >
                    {probe?.state === "run" ? <RefreshCw className="me-2 h-4 w-4 animate-spin" /> : null}
                    {t("testTools")}
                  </Button>
                </div>
              </div>
            </div>

            {rec && (
              <div className="rounded-lg border border-border bg-card p-4">
                <div className="flex items-start gap-3">
                  <span className="mt-0.5 shrink-0">
                    <Download className="h-4 w-4 text-muted-foreground" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium">{t("catalogTitle")}</p>
                    <p className="mt-0.5 text-sm text-muted-foreground">
                      {t("catalogSub", { ram: rec.hardware.ramGb })}
                    </p>
                    {rec.servingModel && (
                      <p className="mt-0.5 text-sm text-muted-foreground">
                        {t("servingNow", { tag: rec.servingModel })}
                      </p>
                    )}
                    {rec.servingModel && rec.servingBlocked && (
                      <p className="mt-1 text-sm text-destructive" data-testid="serving-blocked">
                        {t(
                          rec.servingBlocked === "pool-off"
                            ? "servingBlockedPoolOff"
                            : rec.servingBlocked === "row-disabled"
                              ? "servingBlockedRowDisabled"
                              : "servingBlockedRowUnusable"
                        )}{" "}
                        {t("servingBlockedFix")}
                      </p>
                    )}
                    {useNote && (
                      <p
                        className={`mt-1 text-sm ${useNote.state === "fail" ? "text-destructive" : useNote.state === "warn" ? "text-amber-600" : "text-emerald-600"}`}
                      >
                        {useNote.text}
                      </p>
                    )}
                  </div>
                </div>
                <ul className="mt-3 divide-y divide-border">
                  {[rec.recommended, ...rec.alternatives].map((m: LocalModelChoice) => {
                    const installed = rec.ollama.installedTags.includes(m.tag);
                    const fits = m.ramGb <= rec.hardware.ramGb;
                    const isRec = m.tag === rec.recommended.tag;
                    return (
                      <li key={m.tag} className="flex items-center gap-3 py-2.5">
                        <div className="min-w-0 flex-1">
                          <p className="truncate text-sm font-medium">
                            {m.tag}
                            {isRec && (
                              <span className="ms-2 rounded bg-primary/10 px-1.5 py-0.5 text-[11px] text-primary">
                                {t("recommended")}
                              </span>
                            )}
                            {installed && (
                              <span className="ms-2 rounded bg-emerald-500/10 px-1.5 py-0.5 text-[11px] text-emerald-600">
                                {t("installed")}
                              </span>
                            )}
                            {m.tag === rec.servingModel && (
                              <span className="ms-2 rounded bg-primary px-1.5 py-0.5 text-[11px] text-primary-foreground">
                                {t("inUse")}
                              </span>
                            )}
                          </p>
                          <p className="mt-0.5 truncate text-xs text-muted-foreground">
                            {t("catalogMeta", { gb: m.downloadGb, ram: m.ramGb, grade: m.toolCalling })}
                          </p>
                        </div>
                        {installed && m.tag !== rec.servingModel && (
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={using != null || pulling || !rec.ollama.reachable}
                            title={t("useHint")}
                            onClick={() => void switchToModel(m.tag)}
                          >
                            {using === m.tag ? <RefreshCw className="me-1.5 h-3.5 w-3.5 animate-spin" /> : null}
                            {t("use")}
                          </Button>
                        )}
                        {!installed && (
                          <Button
                            size="sm"
                            variant={fits ? "outline" : "ghost"}
                            disabled={pulling || !fits || !rec.ollama.reachable}
                            title={fits ? undefined : t("tooBig", { ram: m.ramGb })}
                            onClick={() => void pull(m.tag)}
                          >
                            {t("pull")}
                          </Button>
                        )}
                      </li>
                    );
                  })}
                </ul>
              </div>
            )}

            {hasAcademy() && (
              <div className="flex items-start gap-3 rounded-lg border border-border bg-card p-4">
                <span className="mt-0.5 shrink-0">
                  <GraduationCap className="h-4 w-4 text-muted-foreground" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium">{t("learnTitle")}</p>
                  <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-sm">
                    <a
                      className="text-primary hover:underline"
                      href={academyHref() + "/agents"}
                      target="_blank"
                      rel="noreferrer"
                    >
                      {t("learnAgents")}
                    </a>
                    <a
                      className="text-primary hover:underline"
                      href={academyHref() + "/local-models"}
                      target="_blank"
                      rel="noreferrer"
                    >
                      {t("learnModels")}
                    </a>
                    <a
                      className="text-primary hover:underline"
                      href={academyHref() + "/install"}
                      target="_blank"
                      rel="noreferrer"
                    >
                      {t("learnInstall")}
                    </a>
                  </div>
                </div>
              </div>
            )}

            <div className="flex items-start gap-3 rounded-lg border border-border bg-card p-4">
              <span className="mt-0.5 shrink-0">
                <LifeBuoy className="h-4 w-4 text-muted-foreground" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium">{t("check.down")}</p>
                <p className="mt-0.5 text-sm text-muted-foreground">{t("downBody")}</p>
                <code className="mt-2 block rounded bg-muted px-3 py-2 text-xs">
                  {tHC("s1")}
                </code>
              </div>
            </div>
          </>
        )}
      </div>
    </AppShell>
  );
}
