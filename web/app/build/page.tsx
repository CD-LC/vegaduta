// @ts-nocheck
"use client";

// App Builder — consumer-facing console for the app-builder microservice.
// Non-technical flow: pick a template + describe the app -> create a
// project -> keep chatting to change it (each turn is one /generate call)
// -> start a live preview in an iframe -> "Preview & Share" the URL. There
// is no separate publish pipeline on the backend yet, so this UI is
// deliberately honest about that (see the Preview & Share button below)
// rather than inventing a "publish to production" step that doesn't exist.
//
// Conventions mirrored from ../code-intel/page.tsx (the closest existing
// analog — a console for a sibling backend microservice): co-located lib.ts
// fetch client using the app's Keycloak token, AppShell wrapper,
// usePageReady/RouteTransition to avoid an error-flash on load, shadcn
// primitives, useTranslations. app-builder has no core proxy yet (v0, DIRECT
// mode only — see lib.ts), so this page also mirrors code-intel's
// reachability-check-for-error-side-effect pattern: no "Connected" badge,
// just an Alert if the service turns out to be unreachable.
import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import {
  Sparkles,
  Loader2,
  RotateCw,
  XCircle,
  Plus,
  FolderOpen,
  LayoutTemplate,
  Bot,
  Smartphone,
  X,
} from "lucide-react";
import { Github } from "../components/lucideBrandIcons";
import AppShell from "../components/AppShell";
import { usePageReady } from "../components/RouteTransition";
import Select from "../components/Select";
import { Button } from "../components/ui/button";
import { Card, CardContent } from "../components/ui/card";
import { Alert, AlertDescription } from "../components/ui/alert";
import PageHeader from "../components/ax/PageHeader";
import ProjectsList from "./ProjectsList";
import { Tabs, TabsList, TabsTrigger } from "../components/ui/tabs";
import { useTranslations } from "../lib/i18n";
import { appBuilder, APP_BUILDER_URL, APP_BUILDER_CONFIGURED, onWarming } from "./lib";
import CreateSurface from "./CreateSurface";
import ImportRepoCard from "./ImportRepoCard";
import StudioSurface from "./studio/StudioSurface";
import { isNetworkErrorMessage } from "./errorKind";
import { BUILD_STRINGS as S } from "./buildStrings";
import { EmptyState } from "./BuildEmptyState";
import { ProjectView } from "./ProjectView";

// Tab order is the story: agent first (the intelligence), then the studio that
// puts it in people's hands, then the raw code projects. Copy lives in
// buildStrings.ts; only the icons are bound here.
const SURFACE_ICONS = { create: Bot, studio: Smartphone, apps: LayoutTemplate } as const;
// The "apps" tab is the App Builder console; with no app-builder service there is nothing behind
// it, so the tab is left out instead of opening on a not-available notice.
const SURFACE_TABS = S.surfaceTabs
  .filter((tab) => tab.value !== "apps" || APP_BUILDER_CONFIGURED)
  .map((tab) => ({ ...tab, Icon: SURFACE_ICONS[tab.value] }));

export default function AppBuilderPage() {
  const t = useTranslations("appBuilder");
  const [error, setError] = useState(null);
  const [loaded, setLoaded] = useState(false);
  // True while any app-builder call is still in flight past the cold-start
  // threshold (service runs min_replicas=0 — see lib.ts's onWarming). Shows
  // the amber notice below so a ~9s replica boot doesn't read as a dead page.
  const [warming, setWarming] = useState(false);
  useEffect(() => onWarming(setWarming), []);
  // Page-level surfaces: "create" (the merged blueprint-first designer —
  // one blueprint compiles to an envoy via the brief/apply pipeline OR to a
  // Git-backed app via the app-builder), "studio" (2026-09-27: the edge-first
  // AI app studio - a working app on a live phone in seconds, tap-to-edit, AI
  // on the person's own device, no GitHub or app-builder service needed), and
  // "apps" (the App Builder console, which needs the optional app-builder
  // service). The older Build Studio / Build Center surfaces were merged into
  // Create — see web/app/build/CreateSurface.tsx.
  //
  // VegaDūta is agentic AI: the agent is the starting point and apps are the
  // high-ceiling outcome it powers. So "create" is ALWAYS the initial surface
  // — the last tab is deliberately not remembered; remounting /build lands on
  // the Agent builder every time.
  const [surface, setSurface] = useState("create");
  const surfaceTabs = (
    <Tabs value={surface} onValueChange={setSurface} className="mb-6">
      <TabsList
        className={`grid h-auto w-full grid-cols-1 gap-1.5 rounded-2xl border border-border bg-card p-1.5 text-foreground ${SURFACE_TABS.length === 3 ? "sm:grid-cols-3" : "sm:grid-cols-2"}`}
      >
        {SURFACE_TABS.map(({ value, label, line, Icon, startHere }: (typeof SURFACE_TABS)[number]) => (
          <TabsTrigger
            key={value}
            value={value}
            className="group h-auto justify-start gap-3 rounded-xl px-3.5 py-3 text-start sm:justify-center sm:text-center data-[state=active]:bg-primary/10 data-[state=active]:shadow-none"
          >
            <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground transition-colors group-data-[state=active]:bg-primary group-data-[state=active]:text-primary-foreground">
              <Icon size={17} />
            </span>
            <span className="min-w-0">
              <span className="flex flex-wrap items-center gap-1.5 text-sm font-semibold text-foreground sm:justify-center">
                {label}
                {startHere && (
                  <span className="rounded-full bg-primary/10 px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-primary">
                    {S.startHere}
                  </span>
                )}
              </span>
              <span className="mt-0.5 block text-xs font-normal leading-snug text-muted-foreground">{line}</span>
            </span>
          </TabsTrigger>
        ))}
      </TabsList>
    </Tabs>
  );

  const tp = useTranslations("buildPage");
  // The standard console header; the shell supplies only the topbar eyebrow.
  const pageHeader = <PageHeader icon={Sparkles} title={t("title")} subtitle={tp("subtitle")} />;

  const [templates, setTemplates] = useState([]);
  const [projects, setProjects] = useState([]);
  const [projectsLoaded, setProjectsLoaded] = useState(false);
  const [projectsFailed, setProjectsFailed] = useState(false);
  const [activeProjectId, setActiveProjectId] = useState(null);
  const [project, setProject] = useState(null);
  const [projectLoading, setProjectLoading] = useState(false);
  // getProject failed (or returned nothing): keep the selection and show a
  // retry panel instead of a blank pane.
  const [openFailed, setOpenFailed] = useState(false);

  // BYO-repo pivot (docs/APP-BUILDER-BYO-REPO-CONTRACT-2026-07-30.md §4):
  // best-effort status fetch just to drive the header pill below. Never
  // surfaces as a page-level error — GithubConnectCard/the /build/github
  // page own the real connect/disconnect error handling.
  const [githubStatus, setGithubStatus] = useState(null);

  const refreshProjects = useCallback(() => {
    appBuilder
      .listProjects()
      .then((list) => {
        setProjects(list || []);
        setProjectsFailed(false);
      })
      .catch((e) => {
        setError(e.message);
        setProjectsFailed(true);
      })
      .finally(() => setProjectsLoaded(true));
  }, []);

  useEffect(() => {
    // app-builder is an OPTIONAL service (see lib.ts): when this deployment
    // has no NEXT_PUBLIC_APP_BUILDER_URL there is nothing to probe, and firing
    // these three fetches anyway is exactly what produced the production
    // "Failed to fetch ... http://localhost:8091" toast. Settle the page as
    // loaded and let the unavailable branch below render instead.
    if (!APP_BUILDER_CONFIGURED) {
      setLoaded(true);
      return;
    }
    // Reachability check kept only for its error side effect (same reasoning
    // as code-intel/page.tsx: no "Connected" status badge, platform health
    // already lives in the shared AppShell topbar).
    appBuilder.health().catch((e) => setError(e.message));
    appBuilder
      .listTemplates()
      .then((list) => setTemplates(list || []))
      .catch((e) => setError(e.message))
      .finally(() => setLoaded(true));
    refreshProjects();
    // GitHub connection status for the header pill — best-effort, silent on
    // failure. The backend for this is being built in parallel and is not
    // live everywhere yet, so a failure here just means "show disconnected",
    // not an error banner on top of everything else on this page.
    appBuilder
      .getGithubStatus()
      .then((s) => setGithubStatus(s))
      .catch(() => setGithubStatus({ connected: false, repo_full_name: null }));
  }, [refreshProjects]);
  usePageReady(loaded);

  const openReq = useRef(0);
  const openProject = useCallback((id) => {
    // A banner about the previous project must not follow you to the next.
    setError(null);
    setOpenFailed(false);
    if (!id) {
      openReq.current++;
      setActiveProjectId(null);
      setProject(null);
      setProjectLoading(false);
      return;
    }
    // Drop the previous project immediately and ignore late responses so a
    // slow A can never land under B's selector.
    const reqId = ++openReq.current;
    setActiveProjectId(id);
    setProject(null);
    setProjectLoading(true);
    appBuilder
      .getProject(id)
      .then((p) => {
        if (openReq.current !== reqId) return;
        if (!p) {
          setError("This project could not be loaded.");
          setOpenFailed(true);
          return;
        }
        setProject(p);
      })
      .catch((e) => {
        if (openReq.current !== reqId) return;
        setError(e.message);
        setProject(null);
        setOpenFailed(true);
      })
      .finally(() => {
        if (openReq.current === reqId) setProjectLoading(false);
      });
  }, []);

  const handleCreated = useCallback(
    (p) => {
      setError(null);
      setOpenFailed(false);
      setProject(p);
      setActiveProjectId(p.project_id);
      refreshProjects();
    },
    [refreshProjects]
  );

  const handleDeleted = useCallback(
    (id) => {
      if (activeProjectId === id) {
        setActiveProjectId(null);
        setProject(null);
      }
      refreshProjects();
    },
    [activeProjectId, refreshProjects]
  );

  // Honest "not available on this deployment" state. app-builder is not part
  // of deploy/docker-compose.yml and has no public hostname on the Cloudflare
  // tunnel, so on a stock deployment this is the branch a visitor gets —
  // instead of a broken console that toasts a fetch failure against a URL the
  // browser could never reach. The route stays reachable (deep links, and the
  // moment an operator sets NEXT_PUBLIC_APP_BUILDER_URL at build time this
  // whole branch disappears); the nav rail entry is hidden separately in
  // AppShell so nobody is invited here for nothing.
  //
  // Copy is inline rather than i18n keys on purpose: web/messages/en.json is
  // owned by a concurrent session right now, and a missing key renders as the
  // literal key path ("appBuilder.unavailableHeading") to the user. Keys to
  // add on a follow-up pass: appBuilder.unavailableHeading /
  // .unavailableBody / .unavailableHint.
  if (surface === "studio") {
    return (
      <AppShell eyebrow={t("eyebrow")} wide>
        {pageHeader}
        {surfaceTabs}
        <StudioSurface
          onOpenApp={(id) => {
            setSurface("apps");
            openProject(id);
          }}
        />
      </AppShell>
    );
  }

  if (surface === "create") {
    return (
      <AppShell eyebrow={t("eyebrow")} wide>
        {pageHeader}
        {surfaceTabs}
        <CreateSurface
          onOpenApp={(id) => {
            setSurface("apps");
            openProject(id);
          }}
        />
        {APP_BUILDER_CONFIGURED && (
          <ImportRepoCard
            onImported={(id) => {
              setSurface("apps");
              openProject(id);
            }}
          />
        )}
      </AppShell>
    );
  }

  if (!APP_BUILDER_CONFIGURED) {
    return (
      <AppShell eyebrow={t("eyebrow")} wide>
        {pageHeader}
        {surfaceTabs}
        <div className="mx-auto max-w-xl py-16 text-center">
          <div className="mb-4 inline-flex size-12 items-center justify-center rounded-2xl bg-muted text-muted-foreground">
            <Sparkles aria-hidden="true" size={22} />
          </div>
          <h2 className="text-lg font-semibold text-foreground">App Builder is not available on this deployment</h2>
          <p className="mx-auto mt-2 max-w-md text-sm leading-relaxed text-muted-foreground">
            The app-builder service is an optional add-on and is not running here yet. Nothing is broken on your side —
            there is simply nothing to connect to.
          </p>
          <p className="mx-auto mt-3 max-w-md text-xs leading-relaxed text-muted-foreground">
            Platform operators: deploy the app-builder service behind a public hostname, then rebuild the web app with
            NEXT_PUBLIC_APP_BUILDER_URL set to it.
          </p>
        </div>
      </AppShell>
    );
  }

  return (
    <AppShell eyebrow={t("eyebrow")} wide>
      {pageHeader}
      {surfaceTabs}
      {error && (
        <Alert variant="destructive" className="relative mb-5 pe-10">
          <XCircle aria-hidden="true" />
          <AlertDescription>
            {error} {t("errorDismissSuffix")}
            {isNetworkErrorMessage(error) && <> {t("errorCheckUrlHint", { url: APP_BUILDER_URL })}</>}
          </AlertDescription>
          <Button
            type="button"
            variant="ghost"
            size="icon"
            className="absolute right-2 top-2 size-7 shrink-0 text-destructive hover:text-destructive"
            onClick={() => setError(null)}
            aria-label={S.dismiss}
          >
            <X aria-hidden="true" size={14} />
          </Button>
        </Alert>
      )}
      {warming && (
        <Alert className="mb-5 border-[color-mix(in_srgb,var(--state-warning)_40%,transparent)] bg-[color-mix(in_srgb,var(--state-warning)_10%,transparent)]">
          <Loader2 aria-hidden="true" className="animate-spin text-[var(--state-warning)]" />
          <AlertDescription className="text-[var(--state-warning)]">{t("warmingUp")}</AlertDescription>
        </Alert>
      )}

      {/* Header row: project switcher + "new app" escape hatch */}
      <div className="mb-3 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="max-w-2xl text-sm leading-relaxed text-muted-foreground">
          <span className="font-medium text-foreground">{t("guideHeading")}</span> {t("guideText")}
        </p>
        {projects.length > 0 && (
          <div className="flex flex-wrap items-center gap-2">
            <FolderOpen aria-hidden="true" size={14} className="text-muted-foreground" />
            <div className="w-full min-w-0 sm:w-56">
              <Select
                value={activeProjectId || ""}
                onChange={(e) => openProject(e.target.value)}
                options={projects.map((p) => ({
                  value: p.project_id,
                  label:
                    p.project_id +
                    (p.archived_at
                      ? S.projectOptionSuffix("archived")
                      : p.lifecycle === "ephemeral"
                        ? S.projectOptionSuffix("ephemeral")
                        : ""),
                }))}
                placeholder={t("projectSelectPlaceholder")}
                aria-label={t("projectSelectPlaceholder")}
              />
            </div>
            <Button variant="outline" size="sm" onClick={() => openProject(null)}>
              <Plus aria-hidden="true" size={14} /> {t("newAppButton")}
            </Button>
          </div>
        )}
      </div>

      {/* GitHub connection pill — BYO-repo pivot (contract §4). Links out to
          the dedicated connect/manage screen rather than duplicating the
          connect form inline here. */}
      <div className="mb-5">
        <Link
          href="/build/github"
          className="inline-flex items-center gap-2 rounded-lg border border-border bg-card px-3 py-1.5 text-xs font-medium text-foreground transition-colors hover:bg-accent/50"
        >
          <Github aria-hidden="true" size={13} />
          {githubStatus?.connected ? (
            <>
              <span className="state-pill" style={{ ["--state" as string]: "var(--state-success)" }}>
                <span className="state-dot" /> {S.githubConnected}
              </span>
              <span className="font-mono text-muted-foreground">{githubStatus.repo_full_name}</span>
            </>
          ) : (
            <span className="state-pill" style={{ ["--state" as string]: "var(--state-idle)" }}>
              <span className="state-dot" /> {S.githubConnect}
            </span>
          )}
        </Link>
      </div>

      {!activeProjectId ? (
        <div className="space-y-8">
          <ProjectsList
            projects={projects}
            loaded={projectsLoaded}
            failed={projectsFailed}
            onRetry={refreshProjects}
            onOpen={openProject}
          />
          <EmptyState templates={templates} onCreated={handleCreated} onError={setError} />
        </div>
      ) : projectLoading && !project ? (
        <div className="flex items-center gap-2 py-16 text-sm text-muted-foreground">
          <Loader2 aria-hidden="true" size={16} className="animate-spin" /> {t("loadingProject")}
        </div>
      ) : project ? (
        <ProjectView
          key={project.project_id}
          project={project}
          setProject={setProject}
          onDeleted={handleDeleted}
          onError={setError}
          onClearError={() => setError(null)}
          onProjectsChanged={refreshProjects}
          githubStatus={githubStatus}
        />
      ) : (
        <Card>
          <CardContent className="flex flex-col items-start gap-3 py-10 sm:items-center sm:text-center">
            <XCircle size={22} className="text-destructive" aria-hidden="true" />
            <p className="text-sm text-foreground">
              {openFailed ? "We couldn't open this project." : "Select a project to continue."}
            </p>
            <div className="flex flex-wrap gap-2">
              <Button size="sm" onClick={() => openProject(activeProjectId)}>
                <RotateCw aria-hidden="true" size={14} /> Try again
              </Button>
              <Button size="sm" variant="ghost" onClick={() => openProject(null)}>
                Back to new app
              </Button>
            </div>
          </CardContent>
        </Card>
      )}
    </AppShell>
  );
}
