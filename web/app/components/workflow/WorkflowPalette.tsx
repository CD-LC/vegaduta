"use client";

import { useMemo, useState } from "react";
import type { KeyboardEvent } from "react";
import { ChevronDown } from "lucide-react";
import { SetupGuideCard } from "../setup/SetupLinks";
import { NODE_DEFS, NODE_CATEGORIES, nodeLabelKey, nodeDescriptionKey, categoryLabelKey } from "./nodeDefs";
import type { NodeDefTranslator } from "./useNodeDefTranslations";
import "./workflow-chrome.css";
import { Input } from "../ui/input";
import { workflowNodeOffered } from "../../lib/personalInstall";

export interface WorkflowPaletteProps {
  addNodeByClick: (type: string) => void;
  paletteOpen: boolean;
  setPaletteOpen: (open: boolean) => void;
  tNodeDefs: NodeDefTranslator;
}

export default function WorkflowPalette({
  addNodeByClick,
  paletteOpen,
  setPaletteOpen,
  tNodeDefs,
}: WorkflowPaletteProps) {
  const [query, setQuery] = useState("");
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});

  const q = query.trim().toLowerCase();
  const visibleByCategory = useMemo(() => {
    return NODE_CATEGORIES.map((category) => {
      const defs = NODE_DEFS.filter((d) => {
        if (d.category !== category) return false;
        if (!workflowNodeOffered(d.type)) return false;
        if (!q) return true;
        return (
          tNodeDefs(nodeLabelKey(d.type)).toLowerCase().includes(q) ||
          tNodeDefs(nodeDescriptionKey(d.type)).toLowerCase().includes(q)
        );
      });
      return { category, defs };
    }).filter((g) => g.defs.length > 0);
  }, [q, tNodeDefs]);

  function add(type: string) {
    addNodeByClick(type);
    // Mobile: return to the canvas immediately after tapping a palette item -
    // there's no drag-and-drop on a touchscreen and the added node is
    // invisible behind this drawer until it closes.
    setPaletteOpen(false);
  }

  function onItemKey(e: KeyboardEvent<HTMLElement>, type: string) {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      add(type);
    }
  }

  return (
    <>
      <div className={`ax-wf-palette${paletteOpen ? " ax-wf-palette-open" : ""}`}>
        <div className="wf-pal-search">
          <Input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search nodes…"
            aria-label="Search nodes"
            className="h-8 text-xs"
          />
        </div>
        {visibleByCategory.length === 0 && (
          <div className="px-1 py-4 text-xs text-muted-foreground" role="status">
            No nodes match &ldquo;{query}&rdquo;.
          </div>
        )}
        {visibleByCategory.map(({ category, defs }) => {
          // A search overrides collapse so matches are never hidden.
          const isCollapsed = !q && collapsed[category] === true;
          const listId = `wf-pal-${category}`;
          return (
            <div key={category} className="mb-1">
              <button
                type="button"
                className="wf-pal-cat"
                aria-expanded={!isCollapsed}
                aria-controls={listId}
                onClick={() => setCollapsed((c) => ({ ...c, [category]: !c[category] }))}
              >
                <ChevronDown size={11} aria-hidden="true" />
                {tNodeDefs(categoryLabelKey(category))}
              </button>
              <div id={listId} className="space-y-1" hidden={isCollapsed}>
                {defs.map((def) => {
                  const Icon = def.icon;
                  const label = tNodeDefs(nodeLabelKey(def.type));
                  const desc = tNodeDefs(nodeDescriptionKey(def.type));
                  return (
                    <div
                      key={def.type}
                      role="button"
                      tabIndex={def.supported ? 0 : -1}
                      aria-disabled={!def.supported}
                      aria-label={def.supported ? `Add ${label} node. ${desc}` : `${label} (not available yet)`}
                      draggable={def.supported}
                      onDragStart={(e) => e.dataTransfer.setData("application/workflow-node-type", def.type)}
                      onClick={() => def.supported && add(def.type)}
                      onKeyDown={(e) => def.supported && onItemKey(e, def.type)}
                      title={desc}
                      className={
                        def.supported
                          ? "wf-pal-item group flex cursor-grab items-center gap-2.5 rounded-lg border border-transparent px-2 py-1.5 text-xs font-medium text-muted-foreground transition-all hover:border-border hover:bg-card hover:text-foreground hover:shadow-sm active:cursor-grabbing"
                          : "wf-pal-item flex cursor-not-allowed items-center gap-2.5 rounded-lg border border-dashed border-border px-2 py-1.5 text-xs font-medium text-muted-foreground"
                      }
                    >
                      <span
                        className="flex size-6 shrink-0 items-center justify-center rounded-md shadow-sm ring-1 ring-black/5 transition-transform group-hover:scale-105"
                        style={{ background: def.color }}
                      >
                        <Icon size={12} className="text-white" strokeWidth={2.25} aria-hidden="true" />
                      </span>
                      <span className="truncate">{label}</span>
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })}
        <div className="mt-4 border-t border-border px-1 pt-3">
          <SetupGuideCard
            title="Setup guide"
            steps={["providers", "tools", "mcp", "secrets", "knowledge", "channels"]}
            newTab
          />
        </div>
      </div>
    </>
  );
}
