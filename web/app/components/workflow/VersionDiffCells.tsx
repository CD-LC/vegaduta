"use client";

// Small presentational pieces of the version compare panel: id/author labels
// and the before/after field table with its word-level highlights.
import { Lock } from "lucide-react";
import { formatWhen, hasRedaction, isRedacted, wordDiff, type WordSegment, type WorkflowVersionRow } from "./versionDiff";
import { useTranslations } from "../../lib/i18n";

export function shortId(id: string | null | undefined): string {
  if (!id) return "unknown";
  return id.length > 12 ? `${id.slice(0, 8)}…` : id;
}

/**
 * Core stores createdBy as the Keycloak subject UUID. Show a resolved name
 * when the host can supply one; the option label then drops an unresolved
 * author entirely (a UUID would swamp a native select at 375px).
 */
export function versionOptionLabel(v: WorkflowVersionRow, personLabel?: (userId: string) => string | undefined): string {
  const when = formatWhen(v.createdAt);
  const who = v.createdBy ? personLabel?.(v.createdBy) : undefined;
  return [`v${v.versionNumber}`, when, who ?? ""].filter(Boolean).join(" · ");
}

export function authorLabel(id: string | null | undefined, personLabel?: (userId: string) => string | undefined): string | null {
  if (!id) return null;
  return personLabel?.(id) || shortId(id);
}

function Segments({ segs, kind }: { segs: WordSegment[]; kind: "del" | "ins" }) {
  return (
    <>
      {segs.map((s, i) =>
        s.changed ? (
          kind === "del" ? (
            <del key={i} className="wf-vdiff-hl" data-kind="del">
              {s.text}
            </del>
          ) : (
            <ins key={i} className="wf-vdiff-hl" data-kind="ins">
              {s.text}
            </ins>
          )
        ) : (
          <span key={i}>{s.text}</span>
        ),
      )}
    </>
  );
}

export function ValueCell({ value, segs, side }: { value: string | null; segs: WordSegment[] | null; side: "before" | "after" }) {
    const tHC = useTranslations("hcComponentsWorkflowVersionDiffCells");
  if (value == null) return <span className="wf-vdiff-none">(none)</span>;
  if (isRedacted(value)) {
    return (
      <span className="wf-vdiff-lock" title={tHC("hcComponentsWorkflowVersionDiffCells.s3")}>
        <Lock size={11} aria-hidden="true" />
        redacted
      </span>
    );
  }
  return (
    <span className="wf-vdiff-val">
      {segs ? <Segments segs={segs} kind={side === "before" ? "del" : "ins"} /> : value}
      {hasRedaction(value) && (
        <span className="wf-vdiff-lock" data-inline="true" title={tHC("hcComponentsWorkflowVersionDiffCells.s2")}>
          <Lock size={11} aria-hidden="true" />
          <span className="wf-vdiff-sr">{tHC("hcComponentsWorkflowVersionDiffCells.s1")}</span>
        </span>
      )}
    </span>
  );
}

export function ChangeTable({ rows, caption }: { rows: { key: string; label: string; before: string | null; after: string | null }[]; caption: string }) {
  return (
    <table className="wf-vdiff-table">
      <caption className="wf-vdiff-sr">{caption}</caption>
      <thead>
        <tr>
          <th scope="col">Field</th>
          <th scope="col">Before</th>
          <th scope="col">After</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => {
          const wd = wordDiff(r.before, r.after);
          return (
            <tr key={r.key}>
              <th scope="row" className="wf-vdiff-path">
                {r.label}
              </th>
              <td data-label="Before">
                <ValueCell value={r.before} segs={wd?.before ?? null} side="before" />
              </td>
              <td data-label="After">
                <ValueCell value={r.after} segs={wd?.after ?? null} side="after" />
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
