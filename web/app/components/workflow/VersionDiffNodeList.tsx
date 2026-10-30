"use client";

// The "Nodes" list of the version compare panel: one row per added, removed or
// changed node, roving-tabindex keyboard navigation (Arrow Up/Down/Home/End
// move, Enter shows the node on canvas, Right/Left/Space expand a changed node)
// and the per-group field tables of an expanded row.
import { useRef, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { ChevronRight, Crosshair, Minus, Pencil, Plus } from "lucide-react";
import { groupFields, type DiffChangedNode, type DiffNodeBrief } from "./versionDiff";
import { ChangeTable, shortId } from "./VersionDiffCells";
import { useTranslations } from "../../lib/i18n";

export type RowKind = "changed" | "added" | "removed";
export type NodeRow = { kind: RowKind; node: DiffNodeBrief; changed: DiffChangedNode | null };

const KIND_META: Record<RowKind, { word: string; Icon: typeof Plus }> = {
  added: { word: "Added", Icon: Plus },
  removed: { word: "Removed", Icon: Minus },
  changed: { word: "Changed", Icon: Pencil },
};

type VersionDiffNodeListProps = {
  uid: string;
  rows: NodeRow[];
  activeIdx: number;
  setActive: (idx: number) => void;
  expanded: Set<string>;
  toggle: (id: string, open?: boolean) => void;
  onFocusNode: (nodeId: string) => void;
};

export default function VersionDiffNodeList({
  uid,
  rows,
  activeIdx,
  setActive,
  expanded,
  toggle,
  onFocusNode,
}: VersionDiffNodeListProps) {
  const tHC = useTranslations("hcComponentsWorkflowVersionDiffNodeList");
  const rowRefs = useRef<(HTMLLIElement | null)[]>([]);

  function moveTo(i: number) {
    const next = Math.max(0, Math.min(rows.length - 1, i));
    setActive(next);
    rowRefs.current[next]?.focus();
  }

  function onRowKey(e: ReactKeyboardEvent<HTMLLIElement>, idx: number, row: NodeRow) {
    if (e.target !== e.currentTarget) return;
    switch (e.key) {
      case "ArrowDown":
        e.preventDefault();
        moveTo(idx + 1);
        break;
      case "ArrowUp":
        e.preventDefault();
        moveTo(idx - 1);
        break;
      case "Home":
        e.preventDefault();
        moveTo(0);
        break;
      case "End":
        e.preventDefault();
        moveTo(rows.length - 1);
        break;
      case "ArrowRight":
        if (row.changed) {
          e.preventDefault();
          toggle(row.node.id, true);
        }
        break;
      case "ArrowLeft":
        if (row.changed) {
          e.preventDefault();
          toggle(row.node.id, false);
        }
        break;
      case " ":
        if (row.changed) {
          e.preventDefault();
          toggle(row.node.id);
        }
        break;
      case "Enter":
        if (row.kind !== "removed") {
          e.preventDefault();
          onFocusNode(row.node.id);
        }
        break;
    }
  }

  return (
    <ul className="wf-vdiff-list">
      {rows.map((row, idx) => {
        const { word, Icon } = KIND_META[row.kind];
        const label = row.node.label || shortId(row.node.id);
        const isOpen = expanded.has(row.node.id);
        const panelId = `${uid}-n-${idx}`;
        return (
          // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions -- row arrow-key navigation bubbles from the row's own toggle button
          <li
            key={`${row.kind}-${row.node.id}`}
            ref={(el) => {
              rowRefs.current[idx] = el;
            }}
            className="wf-vdiff-row"
            data-kind={row.kind}
            tabIndex={idx === activeIdx ? 0 : -1}
            aria-label={`${word}: ${label}${row.node.type ? `, ${row.node.type}` : ""}${row.changed ? `, ${row.changed.fields.length} field${row.changed.fields.length === 1 ? "" : "s"}` : ""}`}
            onFocus={(e) => {
              if (e.target === e.currentTarget) setActive(idx);
            }}
            onKeyDown={(e) => onRowKey(e, idx, row)}
          >
            <div className="wf-vdiff-line">
              {row.changed ? (
                <button
                  type="button"
                  className="wf-vdiff-disc"
                  aria-expanded={isOpen}
                  aria-controls={panelId}
                  aria-label={`${isOpen ? "Hide" : "Show"} changes to ${label}`}
                  onClick={() => toggle(row.node.id)}
                >
                  <ChevronRight size={12} aria-hidden="true" />
                </button>
              ) : (
                <span className="wf-vdiff-disc" aria-hidden="true" />
              )}
              <span className="wf-vdiff-badge" data-kind={row.kind}>
                <Icon size={10} aria-hidden="true" />
                {word}
              </span>
              <span className="wf-vdiff-name" title={row.node.id}>
                {label}
              </span>
              {row.node.type && <span className="wf-vdiff-type">{row.node.type}</span>}
              {row.kind !== "removed" && (
                <button
                  type="button"
                  className="wf-vdiff-icon"
                  aria-label={`Show ${label} on canvas`}
                  title={tHC("s1")}
                  onClick={() => onFocusNode(row.node.id)}
                >
                  <Crosshair size={12} aria-hidden="true" />
                </button>
              )}
            </div>
            {row.changed && isOpen && (
              <div id={panelId} className="wf-vdiff-fields">
                {row.changed.fields.length === 0 ? (
                  <p className="wf-vdiff-none">{row.changed.changes.join(", ") || "Changed"}</p>
                ) : (
                  groupFields(row.changed.fields).map((g) => (
                    <div key={g.key} className="wf-vdiff-group">
                      <h4 className="wf-vdiff-gh">{g.title}</h4>
                      <ChangeTable
                        caption={`${label}: ${g.title} changes`}
                        rows={g.fields.map((f) => ({
                          key: f.path,
                          label: f.display,
                          before: f.before,
                          after: f.after,
                        }))}
                      />
                    </div>
                  ))
                )}
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}
