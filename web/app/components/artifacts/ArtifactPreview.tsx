"use client";

// Artifact Preview tab. EVERYTHING here treats the artifact body as untrusted:
//  - html  -> sandboxed srcdoc iframe (lib/artifacts/artifactFrame.ts: no
//             allow-same-origin, strict CSP meta). Scripts stay OFF until the
//             person presses "Run preview" for this exact version.
//  - svg   -> <img> with a data: URL (never inlined, so no script runs and no
//             external resource loads).
//  - markdown -> the existing React-node renderer (RichText: no
//             dangerouslySetInnerHTML, scheme-checked links, SafeModelImage).
//  - csv   -> table of React text nodes.
//  mermaid and code have no Preview (source only; no new dependency).

import { useMemo } from "react";
import { Play, RotateCcw } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import RichText from "../RichText";
import { parseCsv } from "../../lib/artifacts/artifactCsv";
import {
  artifactFrameProps,
  buildArtifactDoc,
  previewVerdict,
  svgDataUrl,
  svgPreviewVerdict,
  type PreviewReason,
} from "../../lib/artifacts/artifactFrame";
import type { ArtifactVersion } from "../../lib/artifacts/artifactVersions";

const REFUSAL_KEY: Record<PreviewReason, string> = {
  incomplete: "refusedIncomplete",
  truncated: "refusedTruncated",
  "too-large": "refusedTooLarge",
  empty: "refusedEmpty",
  unsafe: "refusedUnsafe",
  "not-svg": "refusedNotSvg",
};

export default function ArtifactPreview({
  version,
  title,
  ran,
  onRun,
  onReset,
}: {
  version: ArtifactVersion;
  title: string;
  /** Scripts have been enabled for this exact version (the pane tracks this per version + content). */
  ran: boolean;
  onRun: () => void;
  onReset: () => void;
}) {
  const t = useTranslations("artifacts");
  const { kind, content, complete, truncated } = version;

  const verdict = useMemo(
    () =>
      kind === "svg" ? svgPreviewVerdict(content, complete, truncated) : previewVerdict(content, complete, truncated),
    [kind, content, complete, truncated]
  );
  const doc = useMemo(
    () => (kind === "html" && verdict.status === "ok" ? buildArtifactDoc(content, ran) : ""),
    [kind, verdict.status, content, ran]
  );
  const svgUrl = useMemo(
    () => (kind === "svg" && verdict.status === "ok" ? svgDataUrl(content, complete, truncated) : null),
    [kind, verdict.status, content, complete, truncated]
  );
  const csv = useMemo(
    () => (kind === "csv" && verdict.status === "ok" ? parseCsv(content) : null),
    [kind, verdict.status, content]
  );

  if (verdict.status === "refused") {
    return (
      <p
        className="rounded-md border border-dashed border-border px-3 py-6 text-center text-sm text-muted-foreground"
        data-testid="artifact-refused"
      >
        {t(REFUSAL_KEY[verdict.reason])}
      </p>
    );
  }

  if (kind === "html") {
    return (
      <div className="flex h-full min-h-0 flex-col gap-2">
        <div className="flex items-center gap-2 rounded-md border border-border bg-secondary/30 px-2.5 py-1.5 text-xs text-muted-foreground">
          <span className="min-w-0 flex-1">{ran ? t("runningNote") : t("runHint")}</span>
          {ran ? (
            <Button type="button" size="sm" variant="ghost" onClick={onReset} data-testid="artifact-reset">
              <RotateCcw className="size-3" aria-hidden="true" />
              {t("resetPreview")}
            </Button>
          ) : (
            <Button type="button" size="sm" onClick={onRun} data-testid="artifact-run">
              <Play className="size-3" aria-hidden="true" />
              {t("runPreview")}
            </Button>
          )}
        </div>
        {/* key: switching paused <-> run remounts the frame, so a paused document is never "upgraded" in place. */}
        <iframe
          key={ran ? "run" : "paused"}
          data-testid="artifact-frame"
          title={t("previewTitle", { title })}
          {...artifactFrameProps(ran)}
          srcDoc={doc}
          className="min-h-[320px] w-full flex-1 rounded-xl border border-border bg-white"
        />
      </div>
    );
  }

  if (kind === "svg" && svgUrl) {
    return (
      <div className="flex min-h-[240px] items-center justify-center rounded-xl border border-border bg-white p-3">
        {/* Plain <img>: the URL is generated from the body by svgDataUrl, not supplied by the model. */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={svgUrl} alt={t("svgAlt", { title })} className="max-h-[60vh] max-w-full" data-testid="artifact-svg" />
      </div>
    );
  }

  if (kind === "markdown") {
    return (
      <div className="rounded-xl border border-border bg-card p-4" data-testid="artifact-markdown">
        <RichText text={content} />
      </div>
    );
  }

  if (kind === "csv" && csv) {
    if (csv.rows.length === 0) {
      return <p className="text-sm text-muted-foreground">{t("csvEmpty")}</p>;
    }
    const [head, ...body] = csv.rows;
    return (
      <div className="space-y-2" data-testid="artifact-csv">
        <div className="overflow-auto rounded-lg border border-border">
          <table className="w-full border-collapse text-left text-sm">
            <thead>
              <tr className="border-b border-border bg-muted/60">
                {head.map((cell, ci) => (
                  <th key={ci} className="whitespace-nowrap px-3 py-1.5 font-semibold">
                    {cell}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {body.map((row, ri) => (
                <tr key={ri} className={ri > 0 ? "border-t border-border" : undefined}>
                  {row.map((cell, ci) => (
                    <td key={ci} className="px-3 py-1.5 align-top">
                      {cell}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {csv.truncated && <p className="text-xs text-muted-foreground">{t("csvTruncated")}</p>}
      </div>
    );
  }

  return null;
}
