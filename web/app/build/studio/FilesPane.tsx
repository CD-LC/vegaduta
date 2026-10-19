"use client";

import { AppCheckPanel } from "./AppCheckPanel";
import { DataPanel } from "./DataPanel";
import { findInSource } from "./runtime";
import { currentHtml } from "./store";
import { describeTarget } from "./scope";
import { DesignPanel, ElementPanel } from "./StudioDesignPanels";
import CodePanel from "./StudioCodePanel";
import type { ProjectSession } from "./useProjectSession";

/** Panels that edit the app's content: design, tapped element, code, app check and data. */
export default function FilesPane({ s }: { s: ProjectSession }) {
  const { applyData, busy, commit, directEdit, editable, html, panel, project, pushLog, runEdit, selected, setInspect, setSelected } = s;
  if (!["design", "element", "code", "check", "data"].includes(panel)) return null;
  return (
    <fieldset disabled={!editable} className="contents">
            {panel === "design" && <DesignPanel html={html} onChange={(next, note) => commit(next, note, "design")} />}
            {panel === "element" && (
              <ElementPanel
                selected={selected}
                inSource={
                  !!selected &&
                  !!findInSource(new DOMParser().parseFromString(html, "text/html"), selected)
                }
                onStartSelect={() => setInspect(true)}
                directEdit={directEdit}
                busy={!!busy}
                onAsk={(r) => {
                  pushLog({ kind: "user", text: `${r}  (on the tapped <${selected?.tag}>)` });
                  void runEdit(r, { target: selected ? describeTarget(selected, currentHtml(project)) : null });
                }}
                onSelectedChange={(patch) => setSelected((s) => (s ? { ...s, ...patch } : s))}
              />
            )}
            {panel === "code" && <CodePanel html={html} onApply={(next) => commit(next, "Edited the code by hand", "manual")} />}
            {panel === "check" && (
              <AppCheckPanel
                html={html}
                busy={!!busy}
                onFix={(req, n) => {
                  pushLog({ kind: "user", text: `Fix ${n} app-check finding${n === 1 ? "" : "s"}` });
                  void runEdit(req);
                }}
              />
            )}
            {panel === "data" && <DataPanel name={project.name} data={project.appData || {}} onApply={applyData} />}
    </fieldset>
  );
}
