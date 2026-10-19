"use client";

import type { DragEvent } from "react";
import { NODE_CATEGORIES, NODE_DEFS, nodeLabelKey } from "./nodeDefs";
import type { NodeDefTranslator } from "./useNodeDefTranslations";

/**
 * Collapsed-rail contents for the node library. A collapsed library stays
 * USEFUL, not just a re-open button: every available node type as an icon,
 * grouped by category, draggable onto the canvas exactly like a palette row
 * (same `application/workflow-node-type` payload) or added with a click.
 * (The inspector has no rail: it is shown only while it has something to
 * show - see useStudioLayout's rightNeeded.)
 */

export function StudioPaletteRail({
  addNodeByClick,
  tNodeDefs,
}: {
  addNodeByClick: (type: string) => void;
  tNodeDefs: NodeDefTranslator;
}) {
  return (
    <>
      {NODE_CATEGORIES.map((category, i) => {
        const defs = NODE_DEFS.filter((d) => d.category === category && d.supported);
        if (defs.length === 0) return null;
        return (
          <div key={category} className="wf-studio-rail-group">
            {i > 0 && <span className="wf-studio-rail-sep" aria-hidden="true" />}
            {defs.map((def) => {
              const Icon = def.icon;
              const label = tNodeDefs(nodeLabelKey(def.type));
              return (
                <button
                  key={def.type}
                  type="button"
                  className="wf-studio-rail-node"
                  title={label}
                  aria-label={label}
                  draggable
                  onDragStart={(e: DragEvent<HTMLButtonElement>) => e.dataTransfer.setData("application/workflow-node-type", def.type)}
                  onClick={() => addNodeByClick(def.type)}
                  style={{ ["--node-color" as string]: def.color }}
                >
                  <Icon size={14} className="text-white" strokeWidth={2.25} aria-hidden="true" />
                </button>
              );
            })}
          </div>
        );
      })}
    </>
  );
}
