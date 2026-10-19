"use client";

// Step 1 + 2 of the flow: pick a folder, then index it — with real counts.
//
// The progress bar is driven by buildLocalIndex's own IndexStatus callback
// (files/chunks as they land), NOT by a synthetic timer. A fake bar on an
// operation that genuinely takes minutes on a large repo is worse than no bar:
// it tells people the wrong thing about when they can start asking.
//
// There is no "auto-index on pick". Indexing walks every file in the folder,
// and doing that the instant someone clicks a directory — before they have
// seen how many files we found — is a surprise, not a convenience. Same rule,
// stricter, for the embedding model: the download below starts on an explicit
// click and on nothing else. (What else moves bytes on this route, including
// the public model-manifest lookup this card does not make, is enumerated in
// LocalCodePanel's header.)

import { Download, FolderOpen, RefreshCw, RotateCcw, Trash2, X } from "lucide-react";
import { cn } from "../../lib/utils";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import type { IndexStatus, LocalRepoHandle, LocalWalkSummary, RememberedLocalRepo } from "../../lib/localcode/types";

/** Where the one-time embedding-model download has got to. `cancelled` is
 * separate from `failed` for the same reason a refused write grant is
 * separate from a failed write: the person stopped it, nothing went wrong.
 * Which of the two a given install gets is decided in LocalCodePanel, from
 * the abort signal it owns — never from the installer's bare false, which
 * covers a refusal and a failure as well as a cancel. */
export type EmbedderInstallState =
  | { kind: "idle" }
  | { kind: "running"; loaded: number; total: number; detail: string }
  | { kind: "done" }
  | { kind: "cancelled" }
  | { kind: "failed"; reason: string };

function formatIndexedAt(epochMs: number | null, neverLabel: string): string {
  if (!epochMs) return neverLabel;
  try {
    return new Date(epochMs).toLocaleTimeString();
  } catch {
    return neverLabel;
  }
}

/** Decimal MB (1 MB = 1,000,000 bytes), the unit browsers and download UIs
 * report transfers in. Called only with byte counts the transfer itself
 * reported — the `measured` guard below is what enforces that, so an unknown
 * size renders as no number rather than as a guess. */
function mb(bytes: number): string {
  return (bytes / 1_000_000).toFixed(1);
}

/**
 * The embedder install affordance. Rendered whether or not a folder is open,
 * because the download is about this device, not about this folder.
 *
 * `cancellable` is passed in rather than assumed: the cancellable installer is
 * the one the cross-module contract defines, and where the console is running
 * against the older non-cancellable entry the Cancel button is HIDDEN. A
 * Cancel that cannot cancel is a worse lie than no Cancel at all.
 */
function EmbedderSetup({
  embedderReady,
  edgeOff,
  install,
  cancellable,
  onInstall,
  onCancel,
}: {
  embedderReady: boolean | null;
  edgeOff: boolean;
  install: EmbedderInstallState;
  cancellable: boolean;
  onInstall: () => void;
  onCancel: () => void;
}) {
  const t = useTranslations("localCode");

  // Off for this deployment: the indexer refuses to build at all, so offering
  // the download would spend someone's bandwidth on a model this build will
  // never load.
  if (edgeOff) {
    return <p className="mt-3 text-xs text-muted-foreground">{t("index.embedderOff")}</p>;
  }
  // null is "still probing" — no claim either way, and no button yet.
  if (embedderReady !== false && install.kind !== "running" && install.kind !== "done") return null;

  if (install.kind === "running") {
    // Only bytes the transfer itself weighed earn a bar position or a figure.
    // The indexer's contract (EmbedderInstallProgress) is that a zero total
    // means "unknown — render no size"; any positive pair came from the
    // library actually moving the bytes. So the test is both-positive, and
    // loaded === total is a legitimate value (a file that finished), not a
    // placeholder to filter out. Anything else renders as an indeterminate bar
    // with no number, which is the honest way to say "still going, size
    // unknown".
    const measured = install.loaded > 0 && install.total > 0 && install.loaded <= install.total;
    const pct = measured ? Math.round((install.loaded / install.total) * 100) : null;
    return (
      <div className="mt-3 rounded-lg border border-border bg-secondary/30 p-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <span className="text-xs font-medium text-foreground">{t("index.installing")}</span>
          {cancellable && (
            <Button size="sm" variant="ghost" onClick={onCancel}>
              <X size={13} aria-hidden="true" />
              {t("index.installCancel")}
            </Button>
          )}
        </div>
        <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-secondary">
          <div
            className={cn(
              "h-full rounded-full bg-primary",
              pct == null ? "w-1/3 animate-pulse" : "transition-[width] duration-300"
            )}
            style={pct == null ? undefined : { width: `${pct}%` }}
          />
        </div>
        {/* Only a measured transfer earns a number. Until one is in flight,
            the bar is indeterminate and this line says nothing about size. */}
        {measured && (
          <p className="mt-1.5 text-[11px] text-muted-foreground">
            {t("index.installMb", { done: mb(install.loaded), total: mb(install.total) })}
          </p>
        )}
        {install.detail && <p className="mt-1 text-[11px] text-muted-foreground">{install.detail}</p>}
      </div>
    );
  }

  if (install.kind === "done") {
    return <p className="mt-3 text-xs text-foreground">{t("index.installedNow")}</p>;
  }

  return (
    <div className="mt-3">
      <p className="text-xs text-muted-foreground">{t("index.needEmbedder")}</p>
      <Button size="sm" variant="secondary" className="mt-2" onClick={onInstall}>
        <Download size={13} aria-hidden="true" />
        {t("index.enableEmbedder")}
      </Button>
      <p className="mt-2 max-w-2xl text-[11px] leading-relaxed text-muted-foreground">
        {t("index.enableEmbedderHint")}
      </p>
      {install.kind === "cancelled" && (
        <p className="mt-1.5 text-[11px] text-muted-foreground">{t("index.installCancelled")}</p>
      )}
      {install.kind === "failed" && (
        <p className="mt-1.5 text-[11px] text-destructive">{t("index.installFailed", { reason: install.reason })}</p>
      )}
    </div>
  );
}

export default function LocalCodeIndexCard({
  repo,
  fileCount,
  walk,
  remembered,
  embedderReady,
  edgeOff,
  install,
  installCancellable,
  status,
  picking,
  pickError,
  onPick,
  onReopen,
  onIndex,
  onCancel,
  onForget,
  onInstallEmbedder,
  onCancelInstall,
}: {
  repo: LocalRepoHandle | null;
  /** Files discovered by listCodeFiles — the honest denominator BEFORE
   * indexing starts, so "0 of 1,284" means something on the first render.
   * null means the walk is still running, which is NOT the same as zero. */
  fileCount: number | null;
  /** What the walk actually did. `capped` MUST be visible: a silently partial
   * index is what turns a wrong answer into a trusted wrong answer. */
  walk: LocalWalkSummary | null;
  /** A folder from a previous visit, offered as a one-click reopen. The
   * browser drops folder permission on reload, so this is a button and never
   * something that happens by itself. */
  remembered: RememberedLocalRepo | null;
  /** Whether the on-device EMBEDDING model is present — a different model
   * from the one that answers questions. null while still checking. */
  embedderReady: boolean | null;
  /** On-device AI is switched off for this build, so there is no point
   * offering the download at all. */
  edgeOff: boolean;
  install: EmbedderInstallState;
  installCancellable: boolean;
  status: IndexStatus;
  picking: boolean;
  pickError: string | null;
  onPick: () => void;
  onReopen: () => void;
  onIndex: () => void;
  onCancel: () => void;
  onForget: () => void;
  onInstallEmbedder: () => void;
  onCancelInstall: () => void;
}) {
  const t = useTranslations("localCode");
  const indexing = status.state === "indexing";
  const ready = status.state === "ready";
  const scanning = fileCount == null;

  // Percent is only honest once listCodeFiles has told us the denominator and
  // the indexer has started reporting files. Without both, the bar renders
  // indeterminate (striped/pulsing) instead of lying with a number.
  const pct =
    fileCount && fileCount > 0 && status.files > 0 ? Math.min(100, Math.round((status.files / fileCount) * 100)) : null;

  const embedderSetup = (
    <EmbedderSetup
      embedderReady={embedderReady}
      edgeOff={edgeOff}
      install={install}
      cancellable={installCancellable}
      onInstall={onInstallEmbedder}
      onCancel={onCancelInstall}
    />
  );

  if (!repo) {
    return (
      <section className="rounded-xl border border-border bg-card p-5">
        <h2 className="text-base font-semibold text-foreground">{t("repo.title")}</h2>
        <p className="mt-1 max-w-2xl text-sm leading-relaxed text-muted-foreground">{t("repo.body")}</p>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <Button onClick={onPick} disabled={picking}>
            <FolderOpen size={15} aria-hidden="true" />
            {picking ? t("repo.picking") : t("repo.pick")}
          </Button>
          {remembered && (
            <Button variant="secondary" onClick={onReopen} disabled={picking}>
              <RotateCcw size={15} aria-hidden="true" />
              {t("repo.reopen", { name: remembered.repo.name })}
            </Button>
          )}
          <span className="text-xs text-muted-foreground">{t("repo.stayHere")}</span>
        </div>
        {remembered && <p className="mt-2 text-xs text-muted-foreground">{t("repo.rememberedHint")}</p>}
        {/* The index outlives the folder handle, so questions can be answered
            before anything is reopened. Saying so is the difference between
            "the Ask box works for no visible reason" and a working feature. */}
        {ready && status.chunks > 0 && (
          <p className="mt-2 text-xs text-foreground">
            {t("index.restored", {
              files: status.files.toLocaleString(),
              chunks: status.chunks.toLocaleString(),
            })}
          </p>
        )}
        {pickError && <p className="mt-3 text-xs text-destructive">{pickError}</p>}
        {/* Offered before a folder is picked too: the model belongs to the
            device, not to the folder, and the download is the slow part. */}
        {embedderSetup}
      </section>
    );
  }

  return (
    <section className="rounded-xl border border-border bg-card p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="truncate text-base font-semibold text-foreground">{repo.name}</h2>
          <p className="mt-0.5 text-xs text-muted-foreground">
            {scanning ? t("repo.scanning") : t("repo.fileCount", { count: fileCount.toLocaleString() })}
            {" · "}
            {t("repo.stayHere")}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {indexing ? (
            <Button size="sm" variant="ghost" onClick={onCancel}>
              <X size={13} aria-hidden="true" />
              {t("index.cancel")}
            </Button>
          ) : (
            <Button
              size="sm"
              variant={ready ? "secondary" : "default"}
              onClick={onIndex}
              // `scanning` (fileCount === null) has to disable this too. It
              // used to read `fileCount === 0`, which is false during the
              // walk — so a click while "Looking for code files…" was still
              // on screen kicked off a build that re-walked the whole folder,
              // against this file's own no-surprise-indexing rule. Same for
              // embedderReady !== true: null is "still probing", and there is
              // nothing to index with until it answers.
              disabled={scanning || fileCount === 0 || embedderReady !== true}
            >
              {ready && <RefreshCw size={13} aria-hidden="true" />}
              {ready ? t("index.reindex") : t("index.start")}
            </Button>
          )}
          <Button size="sm" variant="ghost" onClick={onForget} disabled={indexing}>
            <Trash2 size={13} aria-hidden="true" />
            {t("repo.forget")}
          </Button>
        </div>
      </div>

      {(indexing || ready || status.state === "error") && (
        <div className="mt-4">
          {indexing && (
            <div className="h-1.5 w-full overflow-hidden rounded-full bg-secondary">
              <div
                className={cn(
                  "h-full rounded-full bg-primary",
                  pct == null ? "w-1/3 animate-pulse" : "transition-[width] duration-300"
                )}
                style={pct == null ? undefined : { width: `${pct}%` }}
              />
            </div>
          )}
          <div className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] text-muted-foreground">
            <span>{indexing ? t("index.indexing") : ready ? t("index.ready") : t("index.failed")}</span>
            <span>{t("index.filesDone", { count: status.files.toLocaleString() })}</span>
            <span>{t("index.chunks", { count: status.chunks.toLocaleString() })}</span>
            {ready && (
              <span>{t("index.indexedAt", { time: formatIndexedAt(status.indexedAt, t("index.never")) })}</span>
            )}
          </div>
          {status.detail && (
            <p
              className={cn(
                "mt-1.5 text-[11px] leading-relaxed",
                status.state === "error" ? "text-destructive" : "text-muted-foreground"
              )}
            >
              {status.detail}
            </p>
          )}
        </div>
      )}

      {/* The block above only covers indexing/ready/error. A build cancelled
          before any chunk landed comes back IDLE carrying "Indexing
          cancelled." — without this line the button just flipped back to
          "Index this folder" and the person was told nothing at all. The
          counters are deliberately not repeated: they are all zero here. */}
      {status.state === "idle" && status.detail && (
        <p className="mt-3 text-xs text-muted-foreground">{status.detail}</p>
      )}

      {status.state === "idle" && fileCount === 0 && (
        <p className="mt-3 text-xs text-muted-foreground">{t("repo.noCodeFiles")}</p>
      )}

      {embedderSetup}

      {walk?.capped && (
        <p className="mt-3 text-xs text-foreground">{t("repo.capped", { count: walk.files.toLocaleString() })}</p>
      )}
      {/* Only the SURPRISING exclusions. Binary files and unknown types are
          expected in any repo and listing them is noise; a file we should have
          been able to read and could not is the one a missing answer traces
          back to. */}
      {walk && walk.skippedLarge + walk.unreadable > 0 && (
        <p className="mt-1.5 text-xs text-muted-foreground">
          {t("repo.skipped", {
            large: walk.skippedLarge.toLocaleString(),
            unreadable: walk.unreadable.toLocaleString(),
          })}
        </p>
      )}
    </section>
  );
}
