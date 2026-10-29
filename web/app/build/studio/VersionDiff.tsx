"use client";

import { useMemo, useRef, useState } from "react";
import { X } from "lucide-react";
import { Button } from "../../components/ui/button";
import { stripConnections } from "./connect";
import { collapseContext, diffStats, lineDiff } from "./lineDiff";
import type { AppVersion } from "./store";
import { useFocusTrap } from "./useFocusTrap";
import { useTranslations } from "../../lib/i18n";

const LABEL = (v: AppVersion, i: number) => `#${i + 1} · ${v.note.length > 38 ? v.note.slice(0, 36) + "…" : v.note}`;

/** Line diff between two saved versions, shown in a modal. The generated
 * connections block is left out - it is studio-owned code, not the author's. */
export function VersionDiff({
  versions,
  initialFrom,
  onClose,
  onRestore,
  canRestore,
}: {
  versions: AppVersion[];
  initialFrom: number;
  onClose: () => void;
  onRestore: (index: number) => void;
  canRestore: boolean;
}) {
    const tHC = useTranslations("hcBuildStudioVersionDiff");
  const [from, setFrom] = useState(Math.min(Math.max(initialFrom, 0), versions.length - 1));
  const [to, setTo] = useState(versions.length - 1);
  const [full, setFull] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useFocusTrap(ref, onClose);

  const lines = useMemo(
    () => lineDiff(stripConnections(versions[from].html), stripConnections(versions[to].html)),
    [versions, from, to]
  );
  const stats = diffStats(lines);
  const rows = useMemo(() => (full ? lines : collapseContext(lines, 3)), [lines, full]);
  const showRestore = canRestore && from !== versions.length - 1;

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/50 p-4 backdrop-blur-sm">
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby="vdiff-title"
        className="flex max-h-[85vh] w-full max-w-3xl flex-col gap-3 rounded-2xl border border-border bg-card p-4 shadow-2xl"
      >
        <div className="flex items-center gap-2">
          <p id="vdiff-title" className="text-sm font-semibold text-foreground">
            {tHC("hcBuildStudioVersionDiff.s5")}
          </p>
          <button type="button" onClick={onClose} aria-label="Close" className="ms-auto rounded p-1 hover:bg-secondary">
            <X aria-hidden="true" size={16} />
          </button>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-xs">
          <label className="flex items-center gap-1.5 text-muted-foreground">
            From
            <select
              value={from}
              onChange={(e) => setFrom(Number(e.target.value))}
              className="max-w-[14rem] rounded-md border border-border bg-background px-2 py-1 text-foreground"
            >
              {versions.map((v, i) => (
                <option key={v.at + "-" + i} value={i}>
                  {LABEL(v, i)}
                </option>
              ))}
            </select>
          </label>
          <label className="flex items-center gap-1.5 text-muted-foreground">
            To
            <select
              value={to}
              onChange={(e) => setTo(Number(e.target.value))}
              className="max-w-[14rem] rounded-md border border-border bg-background px-2 py-1 text-foreground"
            >
              {versions.map((v, i) => (
                <option key={v.at + "-" + i} value={i}>
                  {LABEL(v, i)}
                </option>
              ))}
            </select>
          </label>
          <span className="ms-auto tabular-nums text-muted-foreground" role="status">
            <span className="text-emerald-600">+{stats.added}</span>{" "}
            <span className="text-destructive">−{stats.removed}</span>
          </span>
        </div>
        <div className="min-h-0 flex-1 overflow-auto rounded-lg border border-border bg-neutral-950 font-mono text-[11px] leading-snug">
          {stats.added + stats.removed === 0 ? (
            <p className="p-3 text-neutral-400">{tHC("hcBuildStudioVersionDiff.s4")}</p>
          ) : (
            <pre className="m-0 min-w-max p-0">
              {rows.map((r, i) =>
                r.kind === "gap" ? (
                  <div key={i} className="bg-neutral-900 px-3 py-0.5 text-neutral-400">
                    ⋯ {r.hidden} {tHC("hcBuildStudioVersionDiff.s3")}{r.hidden === 1 ? "" : "s"}
                  </div>
                ) : (
                  <div
                    key={i}
                    className={`px-3 ${r.kind === "add" ? "bg-emerald-950 text-emerald-200" : r.kind === "del" ? "bg-red-950 text-red-200" : "text-neutral-300"}`}
                  >
                    <span aria-hidden className="me-2 select-none">
                      {r.kind === "add" ? "+" : r.kind === "del" ? "−" : " "}
                    </span>
                    <span className="sr-only">
                      {r.kind === "add" ? "added: " : r.kind === "del" ? "removed: " : ""}
                    </span>
                    {r.text || " "}
                  </div>
                )
              )}
            </pre>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <input type="checkbox" checked={full} onChange={(e) => setFull(e.target.checked)} /> {tHC("hcBuildStudioVersionDiff.s2")}
          </label>
          {showRestore && (
            <Button
              size="sm"
              variant="outline"
              className="ms-auto"
              onClick={() => {
                onRestore(from);
                onClose();
              }}
            >
              {tHC("hcBuildStudioVersionDiff.s1")}{from + 1}
            </Button>
          )}
          <Button
            size="sm"
            variant={showRestore ? "ghost" : "outline"}
            className={showRestore ? "" : "ms-auto"}
            onClick={onClose}
          >
            Close
          </Button>
        </div>
      </div>
    </div>
  );
}
