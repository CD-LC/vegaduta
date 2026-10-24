"use client";

// SandboxPreview — the "next-gen" part of the builder: render the
// generated file set INSTANTLY in a sandboxed iframe, without waiting for
// a GitHub Actions run or a Pages deploy. The preview loop becomes
//   type/drag/drop -> code appears on screen immediately -> commit to git
// which is the difference between an app builder and a ticket queue.
//
// How it works, dependency-light:
//   * @babel/standalone (vendored at /vendored/babel.min.js, loaded lazily)
//     transpiles TS/TSX/JSX in-browser.
//   * Each transformed module becomes a blob: URL; relative imports are
//     rewritten to the sibling file's blob URL, CSS imports become
//     style-injector shims, asset imports become blob/data URL exports,
//     and bare npm imports are pointed at esm.sh.
//   * The iframe is `sandbox="allow-scripts"` (opaque origin — generated
//     code cannot touch the console page, its storage, or its network).
//
// Supported honestly: vite-style web apps (node-react, capacitor-app's
// web build) and plain static HTML. Server-rendered frameworks the
// browser can't build (astro-site, python-fastapi) show the Pages link as
// the preview path instead of pretending.

import { useEffect, useRef, useState } from "react";
import { AlertTriangle, ExternalLink, Loader2, Monitor, MonitorPlay, RefreshCw, RotateCw, Smartphone, Tablet, Wand2 } from "lucide-react";
import { Button } from "../components/ui/button";
import type { AppBuilderFileWrite } from "./lib";
import SandboxFrame from "./SandboxFrame";
import { CODE_EXT, buildModuleGraph, buildStaticDoc, shellDoc } from "./sandboxModules";
import {
  PREVIEW_MESSAGE_TAG,
  addError,
  formatFixRequest,
  injectReporter,
} from "./previewReporter";
import { loadBabel } from "../lib/babel";

// ---------------------------------------------------------------------------
// module graph (pure parts live in sandboxModules.ts)

function transpile(path: string, code: string): string {
  const Babel = window.Babel!;
  const isTs = /\.tsx?$/.test(path);
  const presets: unknown[] = [];
  if (isTs) {
    presets.push(["typescript", { isTSX: true, allExtensions: true }]);
  }
  presets.push(["react", { runtime: "automatic" }]);
  return Babel.transform(code, { filename: path, presets }).code;
}

const makeUrl = (content: string | Blob, type: string): string =>
  URL.createObjectURL(content instanceof Blob ? content : new Blob([content], { type }));

/** Device frames for the instant preview. Width/height are CSS pixels of the
 * simulated viewport; "fit" fills the pane. */
const DEVICES = [
  { id: "fit", label: "Fit to pane", Icon: Monitor, w: 0, h: 0 },
  { id: "tablet", label: "Tablet (768×1024)", Icon: Tablet, w: 768, h: 1024 },
  { id: "phone", label: "Phone (390×844)", Icon: Smartphone, w: 390, h: 844 },
] as const;

export default function SandboxPreview({
  files,
  pagesUrl,
  onFix,
  fixDisabled,
}: {
  files: AppBuilderFileWrite[] | null;
  pagesUrl?: string | null;
  /** Called with a ready-to-send instruction when the user asks the builder
   * to fix the runtime errors the preview reported. */
  onFix?: (instruction: string) => void;
  fixDisabled?: boolean;
}) {
  const [state, setState] = useState<
    { kind: "loading" } | { kind: "ready"; srcdoc: string; notes: string[] } | { kind: "error"; message: string } | { kind: "empty" }
  >({ kind: "empty" });
  const blobUrls = useRef<string[]>([]);
  const [nonce, setNonce] = useState(0);
  const [device, setDevice] = useState<(typeof DEVICES)[number]["id"]>("fit");
  const [landscape, setLandscape] = useState(false);
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const [runtimeErrors, setRuntimeErrors] = useState<string[]>([]);

  // Errors belong to one render of the file set; a new set or a reload starts
  // clean so a fixed app doesn't keep showing its old failures.
  useEffect(() => {
    setRuntimeErrors([]);
  }, [files, nonce]);

  useEffect(() => {
    const onMessage = (e: MessageEvent) => {
      if (e.source !== iframeRef.current?.contentWindow) return;
      const d = e.data as { [k: string]: unknown } | null;
      if (!d || d[PREVIEW_MESSAGE_TAG] !== 1 || typeof d.message !== "string") return;
      const msg = d.message;
      setRuntimeErrors((cur) => addError(cur, msg));
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const cleanup = () => {
      blobUrls.current.forEach((u) => URL.revokeObjectURL(u));
      blobUrls.current = [];
    };

    if (!files || files.length === 0) {
      cleanup();
      setState({ kind: "empty" });
      return cleanup;
    }

    setState({ kind: "loading" });
    (async () => {
      const map = new Map(files.map((f) => [f.path, f.content]));
      const hasModules = [...map.keys()].some((p) =>
        CODE_EXT.some((e) => p.endsWith(e))
      );
      // Pure static-html (no JS modules anywhere) renders directly.
      if (!hasModules && map.has("index.html")) {
        const built = buildStaticDoc(map, makeUrl);
        if (built) {
          if (!cancelled) {
            cleanup();
            blobUrls.current = built.urls;
            setState({ kind: "ready", srcdoc: injectReporter(built.doc), notes: built.notes });
          }
          return;
        }
      }
      await loadBabel();
      if (cancelled) return;
      const graph = buildModuleGraph(map, { transpile, makeUrl });
      if (!graph) {
        setState({
          kind: "error",
          message:
            "No runnable web entry point in this project — the Pages link is the preview for this stack.",
        });
        return;
      }
      if (!cancelled) {
        cleanup();
        blobUrls.current = graph.urls;
        setState({ kind: "ready", srcdoc: injectReporter(shellDoc(graph.entryUrl, graph.importMap)), notes: graph.notes });
      }
    })().catch((e) => {
      if (!cancelled) {
        setState({
          kind: "error",
          message: e instanceof Error ? e.message : String(e),
        });
      }
    });
    return () => {
      cancelled = true;
      cleanup();
    };
  }, [files, nonce]);

  return (
    <div className="flex h-full min-h-[320px] flex-col gap-2">
      <div className="flex items-center gap-2">
        <MonitorPlay aria-hidden="true" size={14} className="text-primary" />
        <span className="text-xs font-medium text-foreground">Instant preview</span>
        <span className="text-[10px] text-muted-foreground">runs in this browser — nothing is deployed</span>
        <div className="ml-auto flex items-center gap-1">
          {state.kind === "ready" && (
            <div role="group" aria-label="Preview device" className="mr-1 flex items-center rounded-md border border-border p-0.5">
              {DEVICES.map(({ id, label, Icon }) => (
                <button
                  key={id}
                  type="button"
                  title={label}
                  aria-label={label}
                  aria-pressed={device === id}
                  onClick={() => setDevice(id)}
                  className={`inline-flex size-6 items-center justify-center rounded ${
                    device === id ? "bg-primary text-primary-foreground" : "text-muted-foreground hover:text-foreground"
                  }`}
                >
                  <Icon size={12} />
                </button>
              ))}
              {device !== "fit" && (
                <button
                  type="button"
                  title="Rotate"
                  aria-label="Rotate device"
                  aria-pressed={landscape}
                  onClick={() => setLandscape((v) => !v)}
                  className="inline-flex size-6 items-center justify-center rounded text-muted-foreground hover:text-foreground"
                >
                  <RotateCw aria-hidden="true" size={12} />
                </button>
              )}
            </div>
          )}
          {files && files.length > 0 && (
            <Button variant="ghost" size="sm" onClick={() => setNonce((n) => n + 1)} aria-label="Reload preview">
              <RefreshCw aria-hidden="true" size={12} />
            </Button>
          )}
          {pagesUrl && (
            <a
              href={pagesUrl}
              target="_blank"
              rel="noopener noreferrer"
              title="Deployed GitHub Pages preview — 404s until the repo's pages job has deployed; see the Build on GitHub pane for fixes"
              className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline"
            >
              Pages <ExternalLink aria-hidden="true" size={10} />
            </a>
          )}
        </div>
      </div>
      {runtimeErrors.length > 0 && (
        <div
          role="alert"
          className="flex items-start gap-2 rounded-lg border border-destructive/40 bg-destructive/5 px-3 py-2 text-xs"
        >
          <AlertTriangle aria-hidden="true" size={14} className="mt-0.5 shrink-0 text-destructive" />
          <div className="min-w-0 flex-1">
            <p className="font-medium text-foreground">
              The preview hit {runtimeErrors.length} runtime error{runtimeErrors.length > 1 ? "s" : ""}
            </p>
            <p className="truncate text-muted-foreground" title={runtimeErrors.join("\n")}>
              {runtimeErrors[0]}
            </p>
          </div>
          {onFix && (
            <Button
              size="sm"
              variant="outline"
              disabled={fixDisabled}
              onClick={() => onFix(formatFixRequest(runtimeErrors))}
            >
              <Wand2 aria-hidden="true" size={12} /> Fix with AI
            </Button>
          )}
        </div>
      )}
      {state.kind === "ready" && state.notes.length > 0 && (
        <p className="text-[11px] leading-snug text-muted-foreground" title={state.notes.join(" ")}>
          Preview note: {state.notes[0]}
          {state.notes.length > 1 ? ` (+${state.notes.length - 1} more)` : ""}
        </p>
      )}
      <div className="min-h-0 flex-1 overflow-auto rounded-xl border border-border bg-card">
        {state.kind === "empty" && (
          <div className="flex h-full items-center justify-center p-6 text-center text-xs text-muted-foreground">
            Ask for something in the chat — the preview appears here the moment the files are written.
          </div>
        )}
        {state.kind === "loading" && (
          <div className="flex h-full items-center justify-center gap-2 text-xs text-muted-foreground">
            <Loader2 aria-hidden="true" size={14} className="animate-spin" /> Rendering preview…
          </div>
        )}
        {state.kind === "error" && (
          <div className="flex h-full flex-col items-center justify-center gap-2 p-6 text-center">
            <p className="text-xs text-muted-foreground">{state.message}</p>
            {pagesUrl && (
              <Button variant="outline" size="sm" onClick={() => window.open(pagesUrl, "_blank", "noopener,noreferrer")}>
                <ExternalLink aria-hidden="true" size={13} /> Open the Pages preview
              </Button>
            )}
          </div>
        )}
        {state.kind === "ready" && (() => {
          const d = DEVICES.find((x) => x.id === device)!;
          const framed = d.w > 0;
          const w = framed ? (landscape ? d.h : d.w) : 0;
          const h = framed ? (landscape ? d.w : d.h) : 0;
          return (
            <div className={framed ? "flex min-h-full justify-center bg-muted/40 p-3" : "size-full"}>
              <SandboxFrame
                ref={iframeRef}
                key={nonce}
                html={state.srcdoc}
                sandbox="allow-scripts"
                className={`bg-white ${framed ? "shrink-0 rounded-2xl border border-border shadow-lg" : "size-full border-0"}`}
                style={framed ? { width: w, maxWidth: "none", height: h } : undefined}
                title="Instant app preview"
              />
            </div>
          );
        })()}
      </div>
    </div>
  );
}
