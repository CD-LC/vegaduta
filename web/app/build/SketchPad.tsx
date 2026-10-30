"use client";

// SketchPad — the drag-and-drop half of "bring your own design". The user
// drags blocks (header, hero, cards, ...) onto a page outline; the result
// serializes into a text wireframe spec (designInputs.serializeSketch)
// that rides along with the chat instruction as DESIGN CONTEXT. No LLM is
// involved in the pad itself — it is a deterministic input device, free
// for everyone, and it works identically on the edge and hosted paths.

import { useCallback, useEffect, useRef, useState } from "react";
import { ChevronDown, ChevronUp, GripVertical, LayoutTemplate, Trash2, X } from "lucide-react";
import { Button } from "../components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "../components/ui/card";
import { Input } from "../components/ui/input";
import { useTranslations } from "../lib/i18n";

export interface SketchBlock {
  id: string;
  type: string;
  label: string;
}

const PALETTE: { type: string; hint: string }[] = [
  { type: "header", hint: "logo + nav" },
  { type: "hero", hint: "big title + call to action" },
  { type: "nav", hint: "menu bar" },
  { type: "text", hint: "paragraph section" },
  { type: "image", hint: "picture / gallery" },
  { type: "cards", hint: "grid of cards" },
  { type: "form", hint: "inputs + submit" },
  { type: "button", hint: "call-to-action" },
  { type: "map", hint: "location map" },
  { type: "footer", hint: "bottom links" },
];

let nextId = 1;
const mkBlock = (type: string): SketchBlock => ({
  id: `b${nextId++}`,
  type,
  label: "",
});

export default function SketchPad({
  blocks,
  onChange,
  onClose,
}: {
  blocks: SketchBlock[];
  onChange: (blocks: SketchBlock[]) => void;
  onClose: () => void;
}) {
    const tHC = useTranslations("hcBuildSketchPad");
  const dragIndex = useRef<number | null>(null);
  const [draggingType, setDraggingType] = useState<string | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  // Index (in the NEW list) to focus once a Remove has re-rendered.
  const focusAfter = useRef<number | null>(null);

  useEffect(() => {
    const at = focusAfter.current;
    if (at === null) return;
    focusAfter.current = null;
    const root = rootRef.current;
    if (!root) return;
    const target =
      root.querySelector<HTMLElement>(`[data-sketch-note="${at}"]`) ??
      root.querySelector<HTMLElement>("[data-sketch-palette]");
    target?.focus();
  }, [blocks]);

  const add = useCallback(
    (type: string, at?: number) => {
      const block = mkBlock(type);
      const next = [...blocks];
      next.splice(at ?? next.length, 0, block);
      onChange(next);
    },
    [blocks, onChange]
  );

  const move = useCallback(
    (from: number, to: number) => {
      if (from === to) return;
      const next = [...blocks];
      const [b] = next.splice(from, 1);
      next.splice(to, 0, b);
      onChange(next);
    },
    [blocks, onChange]
  );

  const update = (id: string, label: string) => onChange(blocks.map((b) => (b.id === id ? { ...b, label } : b)));

  const remove = (id: string, index: number) => {
    focusAfter.current = Math.min(index, blocks.length - 2);
    if (focusAfter.current < 0) focusAfter.current = null;
    onChange(blocks.filter((b) => b.id !== id));
  };

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-3">
        <CardTitle className="flex items-center gap-2 text-sm">
          <LayoutTemplate aria-hidden="true" size={15} className="text-primary" /> {tHC("s6")}
        </CardTitle>
        <Button variant="ghost" size="sm" onClick={onClose} aria-label={tHC("s5")}>
          <X aria-hidden="true" size={14} />
        </Button>
      </CardHeader>
      <CardContent ref={rootRef} className="grid gap-4 sm:grid-cols-[160px_1fr]">
        {/* Block palette — drag onto the canvas, or click to append. */}
        <div>
          <p className="mb-2 text-xs text-muted-foreground">{tHC("s4")}</p>
          <div className="flex flex-wrap gap-1.5 sm:flex-col sm:items-stretch">
            {PALETTE.map((p) => (
              <button
                key={p.type}
                type="button"
                data-sketch-palette=""
                draggable
                onDragStart={(e) => {
                  setDraggingType(p.type);
                  e.dataTransfer.setData("text/sketch-block", p.type);
                }}
                onDragEnd={() => setDraggingType(null)}
                onClick={() => add(p.type)}
                className="cursor-grab rounded-md border border-border bg-card px-2 py-1.5 text-start text-xs text-foreground transition-colors hover:bg-accent/60"
                title={p.hint}
              >
                {p.type}
              </button>
            ))}
          </div>
        </div>

        {/* Canvas — the page outline, top to bottom. */}
        <div
          className="min-h-[220px] rounded-lg border border-dashed border-border bg-muted/20 p-3"
          onDragOver={(e) => e.preventDefault()}
          onDrop={(e) => {
            e.preventDefault();
            const type = e.dataTransfer.getData("text/sketch-block") || draggingType;
            if (type) add(type);
            setDraggingType(null);
          }}
        >
          {blocks.length === 0 ? (
            <p className="py-12 text-center text-xs text-muted-foreground">
              {tHC("s3")}
            </p>
          ) : (
            <ol className="space-y-1.5">
              {blocks.map((b, i) => (
                <li
                  key={b.id}
                  onDragOver={(e) => e.preventDefault()}
                  onDrop={(e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    const fromStr = e.dataTransfer.getData("text/sketch-move");
                    if (fromStr !== "") {
                      move(Number(fromStr), i);
                    } else {
                      const type = e.dataTransfer.getData("text/sketch-block") || draggingType;
                      if (type) add(type, i);
                    }
                    dragIndex.current = null;
                    setDraggingType(null);
                  }}
                  className="flex items-center gap-2 rounded-md border border-border bg-card px-2 py-1.5"
                >
                  {/* Only the grip starts a drag, so text in the note field stays selectable. */}
                  <span
                    draggable
                    onDragStart={(e) => {
                      dragIndex.current = i;
                      e.dataTransfer.setData("text/sketch-move", String(i));
                      const row = e.currentTarget.closest("li");
                      if (row) e.dataTransfer.setDragImage(row, 8, 8);
                    }}
                    className="flex size-8 shrink-0 cursor-grab items-center justify-center text-muted-foreground"
                    title={tHC("s2")}
                    aria-hidden="true"
                  >
                    <GripVertical aria-hidden="true" size={14} />
                  </span>
                  <span className="w-16 shrink-0 text-xs font-medium text-foreground">{b.type}</span>
                  <Input
                    value={b.label}
                    onChange={(e) => update(b.id, e.target.value)}
                    placeholder={tHC("s1")}
                    aria-label={`Note for ${b.type} block ${i + 1}`}
                    data-sketch-note={i}
                    className="h-8 flex-1 border-0 bg-transparent px-1 text-xs shadow-none focus-visible:ring-0"
                  />
                  {/* Keyboard/touch alternative to the HTML5 drag reorder
                      above - drag events never fire from touch input on
                      mobile browsers, and there was previously no non-mouse
                      way to reorder at all. */}
                  <button
                    type="button"
                    onClick={() => move(i, i - 1)}
                    disabled={i === 0}
                    className="flex size-8 shrink-0 items-center justify-center border-0 bg-transparent p-0 text-muted-foreground shadow-none hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
                    aria-label={`Move ${b.type} block ${i + 1} up`}
                  >
                    <ChevronUp aria-hidden="true" size={15} />
                  </button>
                  <button
                    type="button"
                    onClick={() => move(i, i + 1)}
                    disabled={i === blocks.length - 1}
                    className="flex size-8 shrink-0 items-center justify-center border-0 bg-transparent p-0 text-muted-foreground shadow-none hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
                    aria-label={`Move ${b.type} block ${i + 1} down`}
                  >
                    <ChevronDown aria-hidden="true" size={15} />
                  </button>
                  <button
                    type="button"
                    onClick={() => remove(b.id, i)}
                    className="flex size-8 shrink-0 items-center justify-center border-0 bg-transparent p-0 text-muted-foreground shadow-none hover:text-destructive"
                    aria-label={`Remove ${b.type} block ${i + 1}`}
                  >
                    <Trash2 aria-hidden="true" size={14} />
                  </button>
                </li>
              ))}
            </ol>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
