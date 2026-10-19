"use client";

// Tree of what the upstream nodes produced (from pins, else the last run), in
// the config drawer. Clicking any row inserts its path expression
// (`$node["Fetch"].items[0].price`) into whichever ExpressionField was focused
// last - the hand-off goes through ExpressionFocusProvider, not global state.
// With no upstream schema (older core, nothing run yet) it says so and stays out
// of the way.

import { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import type { UpstreamSchemaNode } from "../../lib/api/workflowExpressions";
import { buildNodeRef, buildSchemaTree, type SchemaTreeNode } from "./expressionLang";
import { useExpressionFocus } from "./ExpressionFocus";
import { useUpstreamSchema } from "./useExpressionData";
import "./expression-field.css";
import BrandText from "../BrandText";

type Translator = (key: string, values?: Record<string, string | number>) => string;

const MAX_SAMPLE = 40;

function TreeRows({
  nodes,
  refOf,
  canInsert,
  onInsert,
  t,
}: {
  nodes: SchemaTreeNode[];
  refOf: (path: string) => string;
  canInsert: boolean;
  onInsert: (expr: string) => void;
  t: Translator;
}) {
  const [openPaths, setOpenPaths] = useState<Record<string, boolean>>({});
  return (
    <ul role="group" style={{ listStyle: "none", margin: 0, padding: 0 }}>
      {nodes.map((n) => {
        const kids = n.children.length > 0;
        const open = !!openPaths[n.path];
        return (
          <li key={n.path} role="treeitem" aria-expanded={kids ? open : undefined} aria-selected={false}>
            <div className="wfx-dp-row">
              {kids ? (
                <button
                  type="button"
                  className="wfx-dp-twist"
                  aria-label={open ? t("collapse", { name: n.name }) : t("expand", { name: n.name })}
                  onClick={() => setOpenPaths((p) => ({ ...p, [n.path]: !open }))}
                >
                  {open ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
                </button>
              ) : (
                <span className="wfx-dp-spacer" />
              )}
              <button
                type="button"
                className="wfx-dp-pick"
                disabled={!canInsert}
                title={canInsert ? t("insertTitle", { path: n.path }) : t("focusFirst")}
                onClick={() => onInsert(refOf(n.path))}
              >
                <span className="wfx-dp-name">{n.name}</span>
                {n.type && <span className="wfx-dp-type">{n.type}</span>}
                {n.sample != null && n.sample !== "" && (
                  <span className="wfx-dp-sample" title={n.sample}>
                    {n.sample.length > MAX_SAMPLE ? `${n.sample.slice(0, MAX_SAMPLE)}…` : n.sample}
                  </span>
                )}
              </button>
            </div>
            {kids && open && (
              <div className="wfx-dp-kids">
                <TreeRows nodes={n.children} refOf={refOf} canInsert={canInsert} onInsert={onInsert} t={t} />
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function NodeBlock({
  node,
  ambiguous,
  canInsert,
  onInsert,
  t,
}: {
  node: UpstreamSchemaNode;
  ambiguous: boolean;
  canInsert: boolean;
  onInsert: (expr: string) => void;
  t: Translator;
}) {
  const [open, setOpen] = useState(false);
  const tree = buildSchemaTree(node.paths);
  const name = node.label || node.nodeId;
  return (
    <div className="wfx-dp-node">
      <div className="wfx-dp-row">
        <button
          type="button"
          className="wfx-dp-twist"
          aria-label={open ? t("collapse", { name }) : t("expand", { name })}
          aria-expanded={open}
          onClick={() => setOpen(!open)}
        >
          {open ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
        </button>
        <button
          type="button"
          className="wfx-dp-pick"
          disabled={!canInsert}
          title={canInsert ? t("insertTitle", { path: name }) : t("focusFirst")}
          onClick={() => onInsert(buildNodeRef(node, "", ambiguous))}
        >
          <span className="wfx-dp-name wfx-dp-nodename">{name}</span>
          <span className="wfx-dp-src">{node.source === "pinned" ? t("srcPinned") : node.source === "lastRun" ? t("srcLastRun") : t("srcNone")}</span>
        </button>
      </div>
      {open &&
        (tree.length ? (
          <div className="wfx-dp-kids">
            <TreeRows nodes={tree} refOf={(p) => buildNodeRef(node, p, ambiguous)} canInsert={canInsert} onInsert={onInsert} t={t} />
          </div>
        ) : (
          <p className="wfx-dp-empty">{t("noSample")}</p>
        ))}
    </div>
  );
}

export default function DataPicker({ workflowId, nodeId }: { workflowId: string; nodeId: string }) {
  const t = useTranslations("dataPicker") as Translator;
  const focus = useExpressionFocus();
  const [open, setOpen] = useState(false);
  // Only fetch once the panel is opened: most drawer visits never use it.
  const { nodes, loading, refresh } = useUpstreamSchema(open ? workflowId : undefined, nodeId);
  const canInsert = !!focus && !!focus.activeField;

  if (!focus) return null;

  return (
    <section className="wfx-dp" aria-label={t("title")}>
      <button type="button" className="wfx-dp-head" aria-expanded={open} onClick={() => setOpen(!open)}>
        {open ? <ChevronDown size={14} aria-hidden="true" /> : <ChevronRight size={14} aria-hidden="true" />}
        <BrandText text={t("title")} />
        {open && nodes.length > 0 && <span className="wfx-dp-count">{nodes.length}</span>}
      </button>
      {open && (
        <div className="wfx-dp-body">
          <p className="wfx-dp-hint">{canInsert ? t("hintReady", { field: focus.activeField ?? "" }) : t("hintFocus")}</p>
          {nodes.length === 0 ? (
            <p className="wfx-dp-empty">
              {loading ? t("loading") : t("none")}{" "}
              {!loading && (
                <button type="button" className="wfx-dp-pick" style={{ display: "inline-flex", flex: "none", padding: "0 4px" }} onClick={refresh}>
                  {t("refresh")}
                </button>
              )}
            </p>
          ) : (
            <div role="tree" aria-label={t("title")}>
              {nodes.map((n) => (
                <NodeBlock key={n.nodeId} node={n} ambiguous={!!n.label && nodes.filter((o) => o.label === n.label).length > 1} canInsert={canInsert} onInsert={(expr) => focus.insert(expr)} t={t} />
              ))}
            </div>
          )}
        </div>
      )}
    </section>
  );
}
