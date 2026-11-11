"use client";

// The artifacts side pane: Preview / Code tabs, version switcher, Copy,
// Download, "Ask to change", resize + collapse. At md+ it is a resizable
// right-hand column next to the chat; below md it is a full-screen sheet
// (collapse = close there). Security lives in ArtifactPreview and
// lib/artifacts/artifactFrame.ts - this component only arranges the controls.

import { useEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent } from "react";
import {
  Check,
  Copy,
  Download,
  FileCode,
  FileText,
  Globe,
  GitBranch,
  Image as ImageIcon,
  PanelRightClose,
  PanelRightOpen,
  Pencil,
  Table,
  X,
} from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { cn } from "../../lib/utils";
import ArtifactPreview from "./ArtifactPreview";
import { copyText, downloadText } from "../../lib/artifacts/artifactDownload";
import { contentFingerprint, downloadInfo, hasPreview, type ArtifactKind } from "../../lib/artifacts/artifactTypes";
import { resolveVersion, type ArtifactEntry, type ArtifactVersion } from "../../lib/artifacts/artifactVersions";
import {
  PANE_DEFAULT_WIDTH,
  PANE_MAX_WIDTH,
  PANE_MIN_WIDTH,
  clampPaneWidth,
  widthAfterDrag,
  widthAfterKey,
} from "../../lib/artifacts/artifactPaneWidth";

const KIND_LABEL_KEY: Record<ArtifactKind, string> = {
  html: "kindHtml",
  svg: "kindSvg",
  markdown: "kindMarkdown",
  code: "kindCode",
  mermaid: "kindMermaid",
  csv: "kindCsv",
};

export function ArtifactKindIcon({ kind, className }: { kind: ArtifactKind; className?: string }) {
  switch (kind) {
    case "html":
      return <Globe className={className} aria-hidden="true" />;
    case "svg":
      return <ImageIcon className={className} aria-hidden="true" />;
    case "markdown":
      return <FileText className={className} aria-hidden="true" />;
    case "csv":
      return <Table className={className} aria-hidden="true" />;
    case "mermaid":
      return <GitBranch className={className} aria-hidden="true" />;
    default:
      return <FileCode className={className} aria-hidden="true" />;
  }
}

export default function ArtifactPane({
  entry,
  versionNumber,
  streaming,
  collapsed,
  onSelectVersion,
  onToggleCollapsed,
  onClose,
  onAskToChange,
}: {
  entry: ArtifactEntry;
  /** Version being viewed; null = follow the latest. */
  versionNumber: number | null;
  /** The reply that carries the latest version is still arriving. */
  streaming: boolean;
  collapsed: boolean;
  onSelectVersion: (n: number | null) => void;
  onToggleCollapsed: () => void;
  onClose: () => void;
  onAskToChange: (entry: ArtifactEntry, version: ArtifactVersion) => void;
}) {
  const t = useTranslations("artifacts");
  const version = resolveVersion(entry, versionNumber);
  const [tab, setTab] = useState<"preview" | "code">("preview");
  const [width, setWidth] = useState(PANE_DEFAULT_WIDTH);
  // Versions whose scripts the person has enabled, keyed by id + number + content fingerprint so a
  // different body can never inherit an earlier "Run" (a new session can reuse title and number).
  const [ranKeys, setRanKeys] = useState<Record<string, true>>({});
  const [copyState, setCopyState] = useState<"idle" | "ok" | "failed">("idle");
  const drag = useRef<{ startX: number; startWidth: number } | null>(null);

  useEffect(() => {
    if (copyState === "idle") return undefined;
    const id = setTimeout(() => setCopyState("idle"), 1600);
    return () => clearTimeout(id);
  }, [copyState]);

  // Keep the stored width inside the limits when the window shrinks.
  useEffect(() => {
    const onResize = () => setWidth((w) => clampPaneWidth(w, window.innerWidth));
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  if (!version) return null;

  const previewable = hasPreview(version.kind);
  const effectiveTab = !previewable || (!version.complete && tab === "preview") ? "code" : tab;
  const runKey = `${entry.id}#${version.number}#${contentFingerprint(version.content)}`;
  const ran = ranKeys[runKey] === true;
  const isLatest = version.number === entry.versions[entry.versions.length - 1].number;
  const info = downloadInfo(version.kind, version.lang, version.title);

  const status = !version.complete ? (streaming && isLatest ? t("chipDrafting") : t("chipIncomplete")) : null;

  async function handleCopy() {
    setCopyState((await copyText(version.content)) ? "ok" : "failed");
  }

  function onHandlePointerDown(e: PointerEvent<HTMLDivElement>) {
    drag.current = { startX: e.clientX, startWidth: width };
    e.currentTarget.setPointerCapture(e.pointerId);
  }
  function onHandlePointerMove(e: PointerEvent<HTMLDivElement>) {
    if (!drag.current) return;
    setWidth(widthAfterDrag(drag.current.startWidth, drag.current.startX, e.clientX, window.innerWidth));
  }
  function onHandlePointerUp() {
    drag.current = null;
  }
  function onHandleKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    const next = widthAfterKey(e.key, width, window.innerWidth);
    if (next === null) return;
    e.preventDefault();
    setWidth(next);
  }

  const rootStyle = { "--artifact-w": `${width}px` } as CSSProperties;

  if (collapsed) {
    return (
      <aside
        aria-label={t("paneLabel")}
        className="hidden shrink-0 md:flex md:w-11 md:flex-col md:items-center md:gap-2 md:rounded-xl md:border md:border-border md:bg-card md:py-2"
        data-testid="artifact-pane-collapsed"
      >
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={onToggleCollapsed}
          aria-label={t("expand")}
          title={t("expand")}
        >
          <PanelRightOpen className="size-4" aria-hidden="true" />
        </Button>
        <ArtifactKindIcon kind={version.kind} className="size-4 text-muted-foreground" />
      </aside>
    );
  }

  return (
    <aside
      aria-label={t("paneLabel")}
      data-testid="artifact-pane"
      style={rootStyle}
      className={cn(
        "fixed inset-0 z-50 flex min-h-0 flex-col bg-background",
        "md:sticky md:top-4 md:z-auto md:h-[min(80vh,52rem)] md:w-[var(--artifact-w)] md:max-w-[70vw] md:shrink-0 md:self-start md:overflow-hidden md:rounded-xl md:border md:border-border md:bg-card"
      )}
    >
      {/* Left-edge resize handle (md+). A separator is focusable so the width is keyboard-adjustable. */}
      {/* eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- a focusable separator is the ARIA window-splitter pattern: arrow keys and drag resize the pane */}
      <div
        role="separator"
        aria-orientation="vertical"
        aria-label={t("resize")}
        aria-valuemin={PANE_MIN_WIDTH}
        aria-valuemax={PANE_MAX_WIDTH}
        aria-valuenow={width}
        tabIndex={0}
        onPointerDown={onHandlePointerDown}
        onPointerMove={onHandlePointerMove}
        onPointerUp={onHandlePointerUp}
        onPointerCancel={onHandlePointerUp}
        onKeyDown={onHandleKeyDown}
        className="absolute inset-y-0 left-0 z-10 hidden w-1.5 cursor-col-resize touch-none hover:bg-primary/30 focus-visible:bg-primary/40 focus-visible:outline-none md:block"
      />

      <header className="flex flex-wrap items-center gap-x-2 gap-y-1 border-b border-border px-3 py-2">
        <ArtifactKindIcon kind={version.kind} className="size-4 shrink-0 text-muted-foreground" />
        <div className="min-w-0 flex-1">
          <h3 className="truncate text-sm font-semibold text-foreground" title={entry.title}>
            {entry.title}
          </h3>
          <p className="truncate text-xs text-muted-foreground">
            {t(KIND_LABEL_KEY[version.kind])}
            {version.kind === "code" && version.lang ? ` · ${version.lang}` : ""}
            {status ? ` · ${status}` : ""}
          </p>
        </div>
        {entry.versions.length > 1 && (
          <select
            aria-label={t("versionSelect")}
            value={version.number}
            onChange={(e) => {
              const n = Number(e.target.value);
              onSelectVersion(n === entry.versions[entry.versions.length - 1].number ? null : n);
            }}
            className="h-8 rounded-md border border-border bg-secondary/40 px-2 text-xs text-foreground focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50"
            data-testid="artifact-version-select"
          >
            {entry.versions.map((v) => (
              <option key={v.number} value={v.number}>
                {t("versionLabel", { n: v.number })}
              </option>
            ))}
          </select>
        )}
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={onToggleCollapsed}
          aria-label={t("collapse")}
          title={t("collapse")}
          className="max-md:hidden"
        >
          <PanelRightClose className="size-4" aria-hidden="true" />
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="icon"
          onClick={onClose}
          aria-label={t("close")}
          title={t("close")}
          data-testid="artifact-close"
        >
          <X className="size-4" aria-hidden="true" />
        </Button>
      </header>

      <div className="flex flex-wrap items-center gap-1.5 border-b border-border px-3 py-1.5">
        {previewable && (
          <div role="tablist" className="me-auto inline-flex rounded-full border border-border p-0.5">
            {(["preview", "code"] as const).map((id) => (
              <button
                key={id}
                type="button"
                role="tab"
                aria-selected={effectiveTab === id}
                onClick={() => setTab(id)}
                data-testid={`artifact-tab-${id}`}
                className={cn(
                  "rounded-full px-3 py-1 text-xs font-medium transition-colors",
                  effectiveTab === id
                    ? "bg-primary text-primary-foreground"
                    : "text-muted-foreground hover:text-foreground"
                )}
              >
                {id === "preview" ? t("tabPreview") : t("tabCode")}
              </button>
            ))}
          </div>
        )}
        {!previewable && <span className="me-auto" />}
        <Button type="button" variant="ghost" size="sm" onClick={() => void handleCopy()} data-testid="artifact-copy">
          {copyState === "ok" ? (
            <Check className="size-3.5" aria-hidden="true" />
          ) : (
            <Copy className="size-3.5" aria-hidden="true" />
          )}
          {copyState === "ok" ? t("copied") : copyState === "failed" ? t("copyFailed") : t("copy")}
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => downloadText(info.filename, info.mime, version.content)}
          disabled={version.content === ""}
          title={info.filename}
          data-testid="artifact-download"
        >
          <Download className="size-3.5" aria-hidden="true" />
          {t("download")}
        </Button>
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => onAskToChange(entry, version)}
          disabled={!version.complete}
          data-testid="artifact-ask"
        >
          <Pencil className="size-3.5" aria-hidden="true" />
          {t("askToChange")}
        </Button>
      </div>

      <div className="min-h-0 flex-1 overflow-auto p-3">
        {effectiveTab === "preview" ? (
          <ArtifactPreview
            version={version}
            title={entry.title}
            ran={ran}
            onRun={() => setRanKeys((k) => ({ ...k, [runKey]: true }))}
            onReset={() =>
              setRanKeys((k) => {
                const next = { ...k };
                delete next[runKey];
                return next;
              })
            }
          />
        ) : (
          <div className="space-y-2">
            {version.kind === "mermaid" && <p className="text-xs text-muted-foreground">{t("mermaidNote")}</p>}
            <pre className="overflow-auto rounded-lg border border-border bg-muted/60 p-3" data-testid="artifact-code">
              <code className="whitespace-pre font-mono text-xs">{version.content}</code>
            </pre>
          </div>
        )}
      </div>
    </aside>
  );
}
