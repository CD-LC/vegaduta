"use client";

// McpAppFrame - renders an MCP App (a ui:// HTML resource an MCP server attached to a tool) under the tool
// step that produced it. Same sandbox model as build/SandboxPreview.tsx (srcDoc iframe, sandbox="allow-scripts",
// opaque origin) plus a CSP meta (supplied by core, re-validated in lib/mcpApps/mcpApps.ts) and the postMessage
// bridge in lib/mcpApps/appBridge.ts. Renders nothing unless NEXT_PUBLIC_MCP_APPS=1; core separately needs
// app.mcp.apps.enabled for any step event to carry an app pointer at all.
//
// Posture: the app is untrusted third-party code. It is collapsed by default (nothing runs until the person
// opens it), always labelled with the server it came from, and the person can turn apps off entirely. The frame
// gets no credentials, cookies or tokens; everything it asks the host to do (call a tool, open a link, add a chat
// message) is confirmed by the person here, outside the frame, and tool calls are additionally subject to the
// platform's normal server-side approvals. If anything is invalid, too large or unavailable, nothing is rendered
// beyond a muted note: the plain tool-result row in the trace is always still there.

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { AppWindow, ChevronRight } from "lucide-react";
import { api } from "../lib/api";
import type { McpAppView } from "../lib/api/mcpApps";
import { useTranslations } from "../lib/i18n";
import {
  APP_FRAME_SANDBOX,
  MCP_APPS_ENABLED,
  buildAppDoc,
  parseUiResource,
  type McpAppPointer,
} from "../lib/mcpApps/mcpApps";
import { createAppBridge } from "../lib/mcpApps/appBridge";
import { useAppsDisabled } from "../lib/mcpApps/appsPref";
import { Button } from "./ui/button";

type Pending = { kind: "tool" | "link" | "message"; detail: string; resolve: (ok: boolean) => void };
type Load =
  | { state: "idle" }
  | { state: "loading" }
  | { state: "failed" }
  | { state: "ready"; view: McpAppView; html: string };

function parseToolInput(json: string): Record<string, unknown> {
  try {
    const v = JSON.parse(json);
    return v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}

export default function McpAppFrame({
  pointer,
  agentId,
  onAddMessage,
}: {
  pointer: McpAppPointer;
  /** The agent whose turn produced this result: the server re-checks access and its tool allow-list per call. */
  agentId: string;
  /** Where a user-confirmed `ui/message` goes (the composer draft; it is never auto-sent). */
  onAddMessage?: (text: string) => void;
}) {
  const t = useTranslations("mcpApps");
  const [disabled, setDisabled] = useAppsDisabled();
  const [open, setOpen] = useState(false);
  const [load, setLoad] = useState<Load>({ state: "idle" });
  const [height, setHeight] = useState(240);
  const [pending, setPending] = useState<Pending | null>(null);
  const frameRef = useRef<HTMLIFrameElement>(null);
  const pendingRef = useRef<Pending | null>(null);
  const hostRef = useRef({ agentId, onAddMessage });
  hostRef.current = { agentId, onAddMessage };

  // Fetch on first open only, so a trace with many results does not load (or run) a single app uninvited.
  // One request per card: collapsing mid-flight just hides the result when it lands.
  const startedRef = useRef(false);
  const mountedRef = useRef(true);
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);
  useEffect(() => {
    if (!MCP_APPS_ENABLED || !open || disabled || startedRef.current) return;
    startedRef.current = true;
    setLoad({ state: "loading" });
    void api.getMcpApp(pointer.appId).then((view) => {
      if (!mountedRef.current) return;
      if (!view) return setLoad({ state: "failed" });
      const parsed = parseUiResource({ uri: view.uri, mimeType: view.mimeType, text: view.html });
      // "=== false", not "!parsed.ok": tsconfig has strict off, so only the literal comparison narrows.
      if (parsed.ok === false || parsed.uri !== pointer.uri) return setLoad({ state: "failed" });
      setLoad({ state: "ready", view, html: parsed.html });
    });
  }, [open, disabled, pointer.appId, pointer.uri]);

  const ask = useCallback(
    (kind: Pending["kind"], detail: string) =>
      new Promise<boolean>((resolve) => {
        const p: Pending = { kind, detail, resolve };
        pendingRef.current = p;
        setPending(p);
      }),
    []
  );

  const view = load.state === "ready" ? load.view : null;
  useEffect(() => {
    if (!MCP_APPS_ENABLED || !open || disabled || !view) return undefined;
    const bridge = createAppBridge({
      post: (m) => frameRef.current?.contentWindow?.postMessage(m, "*"),
      frameWindow: () => frameRef.current?.contentWindow,
      toolInput: () => parseToolInput(view.toolInput),
      toolResultText: () => ({ text: view.toolResult, truncated: view.toolResultTruncated }),
      callTool: (name, args) => api.callMcpAppTool(pointer.appId, name, args),
      confirmTool: (name, args) => ask("tool", `${name} ${JSON.stringify(args).slice(0, 300)}`),
      confirmLink: (url) => ask("link", url),
      confirmMessage: (text) => ask("message", text),
      openLink: (url) => {
        window.open(url, "_blank", "noopener,noreferrer");
      },
      addMessage: (text) => hostRef.current.onAddMessage?.(text),
      onResize: setHeight,
      theme: () => (document.documentElement.classList.contains("dark") ? "dark" : "light"),
    });
    const handler = (e: MessageEvent) => {
      void bridge.handle(e);
    };
    window.addEventListener("message", handler);
    return () => {
      // Teardown: everything after this is ignored, and an unanswered confirmation counts as "no".
      bridge.dispose();
      window.removeEventListener("message", handler);
      pendingRef.current?.resolve(false);
      pendingRef.current = null;
      setPending(null);
    };
  }, [view, open, disabled, pointer.appId, ask]);

  const settle = (ok: boolean) => {
    const p = pendingRef.current;
    pendingRef.current = null;
    setPending(null);
    p?.resolve(ok);
  };

  const srcDoc = useMemo(() => (load.state === "ready" ? buildAppDoc(load.html, load.view.csp) : ""), [load]);

  if (!MCP_APPS_ENABLED) return null;

  if (disabled) {
    return (
      <div
        className="mt-1 flex items-center gap-2 rounded-lg border border-dashed border-border px-2 py-1 text-[11px] text-muted-foreground"
        data-testid="mcp-app-disabled"
      >
        <span className="min-w-0 flex-1 truncate">{t("disabledNote")}</span>
        <Button type="button" variant="ghost" size="sm" className="h-auto px-1.5 py-0.5 text-[11px]" onClick={() => setDisabled(false)}>
          {t("enable")}
        </Button>
      </div>
    );
  }

  return (
    <div className="mt-1 w-full rounded-lg border border-border bg-secondary/20 text-xs" data-testid="mcp-app-card">
      <div className="flex items-center gap-1.5 px-2 py-1">
        <AppWindow size={12} className="size-3 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span className="min-w-0 flex-1 truncate text-muted-foreground" title={t("labelLong", { server: pointer.server })}>
          {t("label", { server: pointer.server })}
        </span>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          aria-expanded={open}
          onClick={() => setOpen((o) => !o)}
          className="h-auto gap-1 px-1.5 py-0.5 text-[11px] font-normal"
        >
          <ChevronRight size={12} className={open ? "size-3 rotate-90 transition-transform" : "size-3 transition-transform"} aria-hidden="true" />
          {open ? t("hide") : t("show")}
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => setDisabled(true)}
          className="h-auto px-1.5 py-0.5 text-[11px] font-normal text-muted-foreground"
        >
          {t("disable")}
        </Button>
      </div>
      {open && load.state === "loading" && <p className="px-2 pb-2 text-[11px] text-muted-foreground">{t("loading")}</p>}
      {open && load.state === "failed" && (
        <p className="px-2 pb-2 text-[11px] text-muted-foreground" data-testid="mcp-app-failed">
          {t("unavailable")}
        </p>
      )}
      {open && load.state === "ready" && (
        <div className="px-2 pb-2">
          <iframe
            ref={frameRef}
            srcDoc={srcDoc}
            sandbox={APP_FRAME_SANDBOX}
            referrerPolicy="no-referrer"
            allow=""
            title={t("frameTitle", { server: pointer.server })}
            style={{ height }}
            className="w-full rounded-md border border-border bg-white"
          />
          {pending && (
            // Rendered by the host, outside the frame: the app cannot draw over, answer or dismiss it.
            <div role="alertdialog" aria-label={t("confirmTitle")} className="mt-2 space-y-1.5 rounded-md border border-border bg-background p-2" data-testid="mcp-app-confirm">
              <p className="text-[11px] text-foreground">
                {pending.kind === "tool"
                  ? t("confirmTool", { server: pointer.server })
                  : pending.kind === "link"
                    ? t("confirmLink", { server: pointer.server })
                    : t("confirmMessage", { server: pointer.server })}
              </p>
              <p className="break-all rounded bg-secondary/40 px-1.5 py-1 font-mono text-[11px] text-muted-foreground">{pending.detail}</p>
              <div className="flex gap-2">
                <Button type="button" size="sm" className="h-7 px-3 text-[11px]" onClick={() => settle(true)}>
                  {t("allow")}
                </Button>
                <Button type="button" size="sm" variant="outline" className="h-7 px-3 text-[11px]" onClick={() => settle(false)}>
                  {t("deny")}
                </Button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
