"use client";

// Phone app studio - the edge-first surface of /build.
//
// The answer to "why not just ask ChatGPT for an app?": a chat gives you a
// code block to copy somewhere. This gives you a RUNNING app on a phone
// screen in seconds, that you change by tapping it, restyle without a
// prompt, undo in one click, and put on your actual phone by QR or link -
// with the AI running on your own device (free, private) when it can, and
// the cloud model only as a fallback.
//
// Everything here is client-side: projects and versions live in this
// browser (store.ts), the app renders in an opaque-origin sandboxed iframe
// (runtime.ts), share links carry the app in the URL fragment (share.ts).
// The optional "Android APK" hand-off is the only server path - it pushes
// the app into the existing app-builder capacitor-app project flow.

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowLeft,
  Camera,
  Cloud,
  Cpu,
  MousePointerClick,
  PlugZap,
  QrCode,
  Smartphone,
  Sparkles,
  Trash2,
  X,
  AlertTriangle,
} from "lucide-react";
import { Button } from "../../components/ui/button";
import { VersionDiff } from "./VersionDiff";
import { Textarea } from "../../components/ui/textarea";
import { Input } from "../../components/ui/input";
import Select from "../../components/Select";
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
import OnDeviceSetupNudge from "../../components/edge/OnDeviceSetupNudge";
import { EDGE_PREFS_EVENT } from "../../lib/edge/capabilities";
import { prewarmTier0Session } from "../../lib/edge/tier0";
import { downloadManager } from "../../lib/edge/downloadManager";
import { edgeStatus, type EdgeStatus } from "../../lib/build/edgeDraft";
import { cloudRoute, deviceEngine, type CloudRoute, type DeviceEngineInfo } from "./engine";
import { withHarness } from "./runtime";
import SandboxFrame from "../SandboxFrame";
import { deleteRemote, mergeAll, pullRemote, scheduleSync } from "./studioSync";
import {
  currentHtml,
  MAX_PROJECTS,
  loadProjects,
  newProject,
  saveNotice,
  saveProjectsDetailed,
  type SaveResult,
  type StudioProject,
} from "./store";
import GalleryPanel from "../gallery/GalleryPanel";
import { BLANK_APP, TEMPLATES, VEGADUTA_SAMPLE_APP, matchTemplate } from "./templates";
import { useProjectSession, type WorkspaceProps } from "./useProjectSession";
import ChatPane from "./ChatPane";
import PreviewPane from "./PreviewPane";
import ToolsRail from "./ToolsRail";
import { StudioSignInOverlay } from "./StudioSignInOverlay";
import { type EnginePref, type EngineState, plannedEngine, timeAgo } from "./sessionTypes";

const ENGINE_PREF_KEY = "vd.studio.engine";

const WHY = [
  {
    icon: Smartphone,
    title: "A running app, not a code block",
    body: "Multi-screen apps too — describe the pages and it builds them with a working nav bar. Nothing to copy, paste or install.",
  },
  {
    icon: MousePointerClick,
    title: "Tap anything to change it",
    body: "Point at a button or heading and say what you want. Change text and colours directly, no prompt needed.",
  },
  {
    icon: Cpu,
    title: "AI where you want it",
    body: "On your own device (free, private, offline) or on a VegaDūta-hosted model in the cloud — the app can have an AI block your users call.",
  },
  {
    icon: PlugZap,
    title: "Wire in agents & workflows",
    body: "Attach any agent or workflow from the Connect tab, choose what to send and what to show — your app gains real superpowers, no code.",
  },
  {
    icon: QrCode,
    title: "Publish for real users",
    body: "One click gives your app a short link, its own sign-in for your customers and hosted AI — a product you can hand to people.",
  },
  {
    icon: Camera,
    title: "Real phone features",
    body: "Camera, Bluetooth, vibration, share sheet — ask for them and the app uses them once it's on your phone.",
  },
];

function projectName(description: string): string {
  const s = description.trim().replace(/\s+/g, " ");
  return s.length > 36 ? s.slice(0, 34) + "…" : s || "My app";
}

/** An amber, dismissible, screen-reader-announced banner (storage / limit warnings). */
function Notice({ text, onDismiss }: { text: string; onDismiss?: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    ref.current?.scrollIntoView?.({ block: "nearest" });
  }, [text]);
  return (
    <div
      ref={ref}
      role="alert"
      className="flex items-start gap-2 rounded-xl border border-amber-500/40 bg-amber-500/10 p-3 text-xs text-foreground"
    >
      <AlertTriangle aria-hidden="true" size={14} className="mt-0.5 shrink-0 text-amber-600" />
      <span className="min-w-0 flex-1">{text}</span>
      {onDismiss && (
        <button type="button" onClick={onDismiss} aria-label="Dismiss" className="rounded p-0.5 hover:bg-amber-500/20">
          <X aria-hidden="true" size={12} />
        </button>
      )}
    </div>
  );
}

export default function StudioSurface({ onOpenApp }: { onOpenApp?: (projectId: string) => void }) {
  const [projects, setProjects] = useState<StudioProject[]>([]);
  // The newest save outcome (trimmed / failed), shown as a banner - a quota
  // failure used to vanish silently and the person lost work on reload.
  const lastSave = useRef<SaveResult | null>(null);
  const [saveWarn, setSaveWarn] = useState<string | null>(null);
  const [limitWarn, setLimitWarn] = useState<string | null>(null);
  const [prefill, setPrefill] = useState<{ id: string; text: string } | null>(null);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [engineInfo, setEngineInfo] = useState<DeviceEngineInfo | null>(null);
  const [deviceStatus, setDeviceStatus] = useState<EdgeStatus | null>(null);
  const [cloud, setCloud] = useState<CloudRoute | null>(null);
  const [enginePref, setEnginePref] = useState<EnginePref>("cloud");
  const [idea, setIdea] = useState("");
  const [showGallery, setShowGallery] = useState(false);

  useEffect(() => {
    const local = loadProjects();
    setProjects(local);
    // Local-first: the list above is what the person works with immediately.
    // In the background, pull whatever the server has that this device lacks
    // (a new phone, cleared site data). The merge is a union by version
    // timestamp, so an edit made while this runs is never lost.
    let live = true;
    void pullRemote(local).then((remotes) => {
      if (!live || remotes.length === 0) return;
      setProjects((prev) => {
        const merged = mergeAll(prev, remotes);
        lastSave.current = saveProjectsDetailed(merged);
        return merged;
      });
    });
    try {
      const p = localStorage.getItem(ENGINE_PREF_KEY);
      if (p === "device" || p === "cloud" || p === "auto") setEnginePref(p);
    } catch {
      /* per-viewer convenience only */
    }
    return () => {
      live = false;
    };
  }, []);

  // Which on-device engine is ready - refreshed when prefs change or a model
  // download finishes anywhere in the app.
  useEffect(() => {
    let live = true;
    const refresh = (): void => {
      void deviceEngine().then((i): void => {
        if (live) setEngineInfo(i);
      });
      // kickWarmup=true: warm an ALREADY-downloaded WebLLM model while the
      // person is still reading the page, so the first "Build it" doesn't
      // pay the cold engine load inside the request's own deadline. Never
      // starts a download (edgeStatus/prewarmTier1Engine's contract); the
      // studio was previously the only /build surface that left the engine
      // cold until the first request (CreateSurface already passes true).
      void edgeStatus(true).then((st): void => {
        if (live) setDeviceStatus(st);
      });
      // Chrome's built-in model pays its own first-use init - same
      // fire-and-forget, never-downloads contract as prewarmTier1Engine.
      // This is the device engine on machines with no WebLLM model
      // installed, and its cold start is exactly the 15s "taking too long
      // to start" timeout this page kept hitting.
      prewarmTier0Session();
    };
    void cloudRoute({ fresh: true }).then((c): void => {
      if (live) setCloud(c);
    });
    refresh();
    window.addEventListener(EDGE_PREFS_EVENT, refresh);
    const unsub = downloadManager.subscribe((e) => {
      if (e.type === "asset" && e.asset.state === "ready") refresh();
    });
    return () => {
      live = false;
      window.removeEventListener(EDGE_PREFS_EVENT, refresh);
      unsub();
    };
  }, []);

  useEffect(() => {
    if (lastSave.current) setSaveWarn(saveNotice(lastSave.current));
  }, [projects]);

  const persist = useCallback((next: StudioProject[]) => {
    setProjects(next);
    lastSave.current = saveProjectsDetailed(next);
    scheduleSync(next);
  }, []);

  const updateProject = useCallback((id: string, fn: (p: StudioProject) => StudioProject) => {
    setProjects((prev) => {
      const next = prev.map((p) => (p.id === id ? fn(p) : p));
      lastSave.current = saveProjectsDetailed(next);
      scheduleSync(next);
      return next;
    });
  }, []);

  const setPref = (p: EnginePref) => {
    setEnginePref(p);
    try {
      localStorage.setItem(ENGINE_PREF_KEY, p);
    } catch {
      /* ignore */
    }
  };

  const engine: EngineState = { device: engineInfo, deviceStatus, cloud, pref: enginePref };
  const active = projects.find((p) => p.id === activeId) || null;
  const [pendingCreate, setPendingCreate] = useState<{ id: string; description: string; fromTemplate: boolean } | null>(
    null
  );

  // At the cap the person is told - the oldest app is never dropped silently.
  const roomForAnother = (): boolean => {
    if (projects.length >= MAX_PROJECTS) {
      setLimitWarn(
        `You already have ${MAX_PROJECTS} apps, the most this browser keeps. Delete one under "Your apps" to make room for a new one.`
      );
      return false;
    }
    setLimitWarn(null);
    return true;
  };

  const startFromIdea = (description: string) => {
    const d = description.trim();
    if (!d) return;
    if (!roomForAnother()) return;
    const t = matchTemplate(d);
    const p = t
      ? newProject(projectName(d), t.emoji, t.html, `Started from the ${t.name} starter`, "template")
      : newProject(projectName(d), "✨", BLANK_APP, "Blank app", "template");
    persist([p, ...projects]);
    setActiveId(p.id);
    setPendingCreate({ id: p.id, description: d, fromTemplate: !!t });
    setIdea("");
  };

  const startFromTemplate = (id: string) => {
    const t = TEMPLATES.find((x) => x.id === id);
    if (!t) return;
    if (!roomForAnother()) return;
    const p = newProject(t.name, t.emoji, t.html, `Started from the ${t.name} starter`, "template");
    persist([p, ...projects]);
    setActiveId(p.id);
  };

  const startFromVegaDemo = () => {
    if (!roomForAnother()) return;
    const t = VEGADUTA_SAMPLE_APP;
    const p = newProject(t.name, t.emoji, t.html, "Opened the VegaDuta interactive demo", "template");
    persist([p, ...projects]);
    setActiveId(p.id);
  };

  const deleteProject = (id: string) => {
    void deleteRemote(id);
    persist(projects.filter((p) => p.id !== id));
    setLimitWarn(null);
    if (activeId === id) setActiveId(null);
  };
  const [confirmDelete, setConfirmDelete] = useState<StudioProject | null>(null);

  const banners = (
    <>
      {saveWarn && <Notice text={saveWarn} />}
      {limitWarn && <Notice text={limitWarn} onDismiss={() => setLimitWarn(null)} />}
    </>
  );

  if (active) {
    return (
      <div className="flex flex-col gap-3">
        {banners}
        <Workspace
          key={active.id}
          project={active}
          engine={engine}
          enginePref={enginePref}
          setEnginePref={setPref}
          update={(fn) => updateProject(active.id, fn)}
          onBack={() => setActiveId(null)}
          pendingCreate={pendingCreate && pendingCreate.id === active.id ? pendingCreate : null}
          clearPendingCreate={() => setPendingCreate(null)}
          prefill={prefill && prefill.id === active.id ? prefill.text : null}
          clearPrefill={() => setPrefill(null)}
          onOpenApp={onOpenApp}
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-8">
      {banners}
      {/* Hero */}
      <section className="relative overflow-hidden rounded-3xl border border-border bg-gradient-to-br from-primary/[0.10] via-background to-orange-500/[0.08] px-5 py-8 sm:px-10 sm:py-12">
        <div className="grid items-center gap-10 lg:grid-cols-[1.25fr_1fr]">
          <div className="flex flex-col gap-5">
            <span className="inline-flex w-fit items-center gap-1.5 rounded-full border border-primary/30 bg-primary/10 px-3 py-1 text-xs font-medium text-primary">
              <Cpu aria-hidden="true" size={12} /> Phone app studio · AI that can run on your device
            </span>
            <h1 className="text-3xl font-extrabold leading-tight tracking-tight text-foreground sm:text-5xl">
              Describe an app.
              <br />
              <span className="bg-gradient-to-r from-primary to-orange-500 bg-clip-text text-transparent">
                Hold it in your hand in a minute.
              </span>
            </h1>
            <p className="max-w-xl text-base text-muted-foreground">
              Say what you want — a single screen or a multi-page app, camera, Bluetooth, an AI that answers on-device
              or on VegaDūta-hosted models. Then wire in your agents and workflows, pick what each call sends and shows,
              add sign-in for your customers, and publish a real link. No code anywhere in that sentence — that&apos;s
              the point.
            </p>
            <form
              className="flex flex-col gap-2 rounded-2xl border border-border bg-background/80 p-2 shadow-sm backdrop-blur"
              onSubmit={(e) => {
                e.preventDefault();
                startFromIdea(idea);
              }}
            >
              <Textarea
                value={idea}
                onChange={(e) => setIdea(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent?.isComposing) {
                    e.preventDefault();
                    startFromIdea(idea);
                  }
                }}
                rows={2}
                placeholder="e.g. A 3-page order app for my bakery — menu, cart and order tracking, with a camera screen for scanning receipts."
                className="min-h-[64px] resize-none border-0 bg-transparent text-base shadow-none focus-visible:ring-0"
                aria-label="Describe your app"
              />
              <div className="flex flex-wrap items-center justify-between gap-2 px-1">
                <EngineBadge engine={engine} setPref={setPref} />
                <Button type="submit" disabled={!idea.trim()} className="gap-1.5">
                  <Sparkles aria-hidden="true" size={15} /> Build it
                </Button>
              </div>
            </form>
            <OnDeviceSetupNudge className="rounded-xl border" />
          </div>
          <HeroPhone onOpen={startFromVegaDemo} />
        </div>
      </section>

      {/* Starters */}
      <section className="flex flex-col gap-3">
        <div className="flex items-baseline justify-between gap-2">
          <h2 className="text-lg font-semibold text-foreground">Or start from a working app</h2>
          <span className="text-xs text-muted-foreground">Opens instantly, no AI needed. Then make it yours.</span>
        </div>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          {TEMPLATES.map((t) => (
            <button
              key={t.id}
              type="button"
              onClick={() => startFromTemplate(t.id)}
              className="group flex flex-col items-start gap-1.5 rounded-2xl border border-border bg-card p-4 text-start transition hover:-translate-y-0.5 hover:border-primary/50 hover:shadow-md"
            >
              <span className="text-3xl transition group-hover:scale-110">{t.emoji}</span>
              <span className="text-sm font-semibold text-foreground">{t.name}</span>
              <span className="text-xs text-muted-foreground">{t.blurb}</span>
            </button>
          ))}
        </div>
      </section>

      {/* Template gallery */}
      <section className="flex flex-col gap-3">
        <div>
          <Button variant="outline" size="sm" onClick={() => setShowGallery((v) => !v)} aria-expanded={showGallery}>
            {showGallery ? "Hide template gallery" : "Browse the template gallery"}
          </Button>
        </div>
        {showGallery && (
          <GalleryPanel
            projects={projects.map((p) => ({ id: p.id, name: p.name, emoji: p.emoji, html: currentHtml(p) }))}
            onUseStudio={(t) => {
              if (!roomForAnother()) return;
              const p = newProject(t.name, t.emoji, t.html, t.note, "template");
              persist([p, ...projects]);
              // A "Make it yours" remix opens the app with the adapt request
              // already typed into the request box - not auto-run, so it can be tweaked.
              if (t.adapt) setPrefill({ id: p.id, text: t.adapt });
              setActiveId(p.id);
            }}
          />
        )}
      </section>

      {/* Your apps */}
      {projects.length > 0 && (
        <section className="flex flex-col gap-3">
          <h2 className="text-lg font-semibold text-foreground">Your apps</h2>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {projects.map((p) => (
              <div key={p.id} className="flex items-center gap-3 rounded-2xl border border-border bg-card p-3">
                <button
                  type="button"
                  onClick={() => setActiveId(p.id)}
                  className="flex min-w-0 flex-1 items-center gap-3 text-start"
                >
                  <span className="grid size-11 shrink-0 place-items-center rounded-xl bg-primary/10 text-2xl">
                    {p.emoji}
                  </span>
                  <span className="min-w-0">
                    <span className="block truncate text-sm font-semibold text-foreground">{p.name}</span>
                    <span className="block text-xs text-muted-foreground">
                      {p.versions.length} version{p.versions.length === 1 ? "" : "s"} · {timeAgo(p.updatedAt)}
                    </span>
                  </span>
                </button>
                <Button
                  variant="ghost"
                  size="sm"
                  aria-label={`Delete ${p.name}`}
                  className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                  onClick={() => setConfirmDelete(p)}
                >
                  <Trash2 aria-hidden="true" size={14} />
                </Button>
              </div>
            ))}
          </div>
          <p className="text-xs text-muted-foreground">
            Saved in this browser only. Nothing is uploaded unless you share it.
          </p>
        </section>
      )}

      {/* Why not a chatbot */}
      <section className="flex flex-col gap-3">
        <h2 className="text-lg font-semibold text-foreground">Why build here instead of asking a chatbot?</h2>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {WHY.map(({ icon: Icon, title, body }) => (
            <div key={title} className="flex gap-3 rounded-2xl border border-border bg-card p-4">
              <span className="grid size-9 shrink-0 place-items-center rounded-xl bg-primary/10 text-primary">
                <Icon size={17} />
              </span>
              <div>
                <p className="text-sm font-semibold text-foreground">{title}</p>
                <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">{body}</p>
              </div>
            </div>
          ))}
        </div>
      </section>

      {/* Deleting a studio project removes its whole version history from
          this browser - same AlertDialog the apps tab uses, not a blocking
          window.confirm. */}
      <AlertDialog open={!!confirmDelete} onOpenChange={(open) => !open && setConfirmDelete(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete &ldquo;{confirmDelete?.name}&rdquo;?</AlertDialogTitle>
            <AlertDialogDescription>
              This removes the app and its {confirmDelete?.versions.length ?? 0} saved version
              {(confirmDelete?.versions.length ?? 0) === 1 ? "" : "s"} from this browser. This can&apos;t be undone.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              onClick={() => {
                if (confirmDelete) deleteProject(confirmDelete.id);
                setConfirmDelete(null);
              }}
            >
              Delete app
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function deviceLine(e: EngineState): { tone: "ok" | "warn" | "off"; text: string; detail: string } {
  if (e.device) {
    return {
      tone: "ok",
      text: `${e.device.runtime} · ${e.device.modelName}`,
      detail: "Runs in this browser on your GPU. Free, private, works offline.",
    };
  }
  const st = e.deviceStatus;
  if (!st) return { tone: "off", text: "Checking…", detail: "" };
  if (st.state === "warming") return { tone: "warn", text: `WebLLM · loading ${st.modelName}`, detail: st.detail };
  if (st.state === "needs-setup")
    return {
      tone: "warn",
      text: "Not set up yet",
      detail: "One free download of a model and your device builds apps with no cloud.",
    };
  if (st.state === "unsupported")
    return {
      tone: "off",
      text: "Not supported in this browser",
      detail: "Needs WebGPU. Use a recent Chrome or Edge on a laptop.",
    };
  return { tone: "off", text: "Off on this deployment", detail: st.detail };
}

function EngineBadge({
  engine,
  setPref,
  compact,
}: {
  engine: EngineState;
  setPref: (p: EnginePref) => void;
  compact?: boolean;
}) {
  const next = plannedEngine(engine);
  const dev = deviceLine(engine);
  const dot = (tone: "ok" | "warn" | "off") =>
    tone === "ok" ? "bg-emerald-500" : tone === "warn" ? "bg-amber-500" : "bg-muted-foreground/40";
  const select = (
    <Select
      value={engine.pref}
      onChange={(e) => setPref(e.target.value as EnginePref)}
      options={[
        { value: "cloud", label: "Platform AI (default)" },
        { value: "device", label: "On my device (edge) only" },
        { value: "auto", label: "Auto: device first, then platform" },
      ]}
      aria-label="Which AI builds your app"
    />
  );
  const nextChip = (
    <span
      className={`inline-flex min-w-0 items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium ${
        next.kind === "device"
          ? "bg-emerald-500/10 text-emerald-700 dark:text-emerald-300"
          : next.kind === "cloud"
            ? "bg-sky-500/10 text-sky-700 dark:text-sky-300"
            : "bg-amber-500/10 text-amber-700 dark:text-amber-300"
      }`}
      title={next.cost}
    >
      {next.kind === "device" ? <Cpu aria-hidden="true" size={12} /> : <Cloud aria-hidden="true" size={12} />}
      <span className="truncate">{next.label}</span>
      {next.cost && <span className="min-w-0 truncate">· {next.cost}</span>}
    </span>
  );
  if (compact) {
    return (
      <div
        className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1.5"
        title={`On device: ${dev.text}
Cloud: ${engine.cloud?.label || "…"}`}
      >
        <span className="shrink-0 text-xs text-muted-foreground">Builds with</span>
        <span className="flex min-w-0 max-w-full">{nextChip}</span>
        <span className="w-56 max-w-full shrink-0">{select}</span>
      </div>
    );
  }
  return (
    <div className="flex w-full flex-col gap-2 rounded-xl border border-border bg-background/60 p-2.5 text-xs">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-muted-foreground">Next build runs on</span>
        <span className="min-w-0 max-w-full">{nextChip}</span>
        <span className="ms-auto">{select}</span>
      </div>
      <div className="grid gap-1.5 sm:grid-cols-2">
        <div className="flex items-start gap-2 rounded-lg bg-secondary/60 p-2">
          <span className={`mt-1 size-2 shrink-0 rounded-full ${dot(dev.tone)}`} />
          <span className="min-w-0">
            <span className="block font-medium text-foreground">
              <Cpu aria-hidden="true" size={11} className="me-1 inline" /> On your device: {dev.text}
            </span>
            <span className="block text-muted-foreground">{dev.detail}</span>
          </span>
        </div>
        <div className="flex items-start gap-2 rounded-lg bg-secondary/60 p-2">
          <span
            className={`mt-1 size-2 shrink-0 rounded-full ${engine.cloud && engine.cloud.kind !== "unknown" && engine.cloud.kind !== "signed-out" ? "bg-sky-500" : "bg-muted-foreground/40"}`}
          />
          <span className="min-w-0">
            <span className="block font-medium text-foreground">
              <Cloud aria-hidden="true" size={11} className="me-1 inline" /> Cloud: {engine.cloud?.label || "checking…"}
            </span>
            <span className="block text-muted-foreground">{engine.cloud?.detail || ""}</span>
          </span>
        </div>
      </div>
      <p className="leading-relaxed text-muted-foreground">
        Leave it on Auto: the app is built on your device first (free and private — it finishes warming up in the
        background and retries on its own if it is still starting), and the cloud steps in only if the device cannot
        answer. Nothing you need to do.
      </p>
    </div>
  );
}

function HeroPhone({ onOpen }: { onOpen: () => void }) {
  const demo = useMemo(() => withHarness(VEGADUTA_SAMPLE_APP.html), []);
  return (
    <button
      type="button"
      onClick={onOpen}
      aria-label="Open the VegaDuta sample app"
      className="group relative mx-auto hidden w-[210px] rotate-[3deg] cursor-pointer self-center transition duration-300 hover:-translate-y-2 hover:rotate-[1.5deg] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2 sm:block lg:w-[260px]"
    >
      <span className="absolute -right-5 -top-3 z-10 rounded-full bg-gradient-to-r from-primary to-orange-500 px-2.5 py-1 text-[10px] font-bold tracking-wider text-white shadow-lg">
        LIVE DEMO
      </span>
      <div className="rounded-[2.6rem] border-[10px] border-neutral-900 bg-neutral-900 shadow-2xl transition duration-300 group-hover:shadow-[0_28px_60px_-12px_rgba(244,82,30,0.45)]">
        <div className="relative h-[430px] overflow-hidden rounded-[2rem] bg-white lg:h-[520px]">
          <SandboxFrame
            html={demo}
            sandbox="allow-scripts"
            title=""
            tabIndex={-1}
            aria-hidden="true"
            className="pointer-events-none absolute left-0 top-0 h-[750px] w-[360px] origin-top-left scale-[0.53] border-0 lg:scale-[0.667]"
          />
          <span className="pointer-events-none absolute inset-x-0 bottom-0 flex items-center justify-center gap-1 bg-gradient-to-t from-neutral-900/85 via-neutral-900/40 to-transparent pb-4 pt-10 text-[13px] font-semibold text-white">
            Tap to explore <span aria-hidden="true">→</span>
          </span>
        </div>
      </div>
    </button>
  );
}

export type { EngineState, PlannedEngine } from "./sessionTypes";
export { plannedEngine } from "./sessionTypes";

function Workspace(props: WorkspaceProps) {
  const s = useProjectSession(props);
  const {
    project,
    engine,
    editable,
    isOwner,
    onBack,
    update,
    setEnginePref,
    setPanel,
    diffFrom,
    setDiffFrom,
    restore,
    pendingAuth,
    finishPendingAuth,
  } = s;
  return (
    <div className="flex flex-col gap-3">
      {/* Top bar */}
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="ghost" size="sm" onClick={onBack} className="gap-1">
          <ArrowLeft aria-hidden="true" size={14} /> All apps
        </Button>
        <span className="text-xl">{project.emoji}</span>
        <Input
          value={project.name}
          onChange={(e) => update((p) => ({ ...p, name: e.target.value.slice(0, 60) }))}
          disabled={!isOwner}
          className="h-8 w-56 border-transparent bg-transparent px-1 text-base font-semibold shadow-none hover:border-border"
          aria-label="App name"
        />
        <div className="ms-auto flex flex-wrap items-center gap-2">
          <EngineBadge engine={engine} setPref={setEnginePref} compact />
          <Button variant="outline" size="sm" onClick={() => setPanel("share")} className="gap-1.5">
            <Smartphone aria-hidden="true" size={14} /> Put it on my phone
          </Button>
        </div>
      </div>

      {!editable && (
        <p
          role="status"
          className="rounded-xl border border-border bg-secondary/60 p-2.5 text-xs text-muted-foreground"
        >
          You have <b>view-only</b> access to this app. You can try it, compare versions and comment, but not change it.
        </p>
      )}
      <div className="grid grid-cols-[minmax(0,1fr)] gap-4 lg:grid-cols-[320px_minmax(0,1fr)_320px]">
        <div className="order-2 flex min-h-0 flex-col gap-3 lg:order-1">
          <ChatPane s={s} />
        </div>
        <PreviewPane s={s} />
        <ToolsRail s={s} />
      </div>
      {diffFrom !== null && (
        <VersionDiff
          versions={project.versions}
          initialFrom={diffFrom}
          onClose={() => setDiffFrom(null)}
          onRestore={restore}
          canRestore={editable}
        />
      )}
      {pendingAuth && (
        <StudioSignInOverlay
          slug={pendingAuth.slug}
          appName={project.name}
          onDone={(user) => finishPendingAuth(pendingAuth.slug, user)}
        />
      )}
    </div>
  );
}
