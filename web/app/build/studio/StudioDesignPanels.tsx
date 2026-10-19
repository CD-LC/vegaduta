"use client";

// Split out of StudioSurface.tsx (behaviour unchanged).
import { useEffect, useState } from "react";
import { MousePointerClick, Sparkles, Trash2 } from "lucide-react";
import { Button } from "../../components/ui/button";
import { Textarea } from "../../components/ui/textarea";
import { Input } from "../../components/ui/input";
import { type SelectedElement } from "./runtime";
import { FONT_CHOICES, THEME_PRESETS, contrastRatio, readTheme, writeTheme, type ThemeTokens } from "./theme";

function rgbToHex(c: string): string {
  const m = c.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
  if (!m) return c.startsWith("#") ? c : "#000000";
  return "#" + [m[1], m[2], m[3]].map((v) => Number(v).toString(16).padStart(2, "0")).join("");
}

export function DesignPanel({ html, onChange }: { html: string; onChange: (next: string, note: string) => void }) {
  const theme = readTheme(html);
  // Colour pickers fire on every drag step - preview locally, commit on release.
  const [draft, setDraft] = useState<Partial<ThemeTokens>>({});
  const t = { ...(theme || {}), ...draft } as ThemeTokens;
  const commitTokens = (patch: Partial<ThemeTokens>, note: string) => {
    setDraft({});
    onChange(writeTheme(html, patch), note);
  };
  const ratio = t.text && t.bg ? contrastRatio(t.text, t.bg) : null;
  const radiusPx = parseInt(t.radius || "16", 10) || 0;

  return (
    <div className="flex flex-col gap-4">
      {!theme && (
        <p className="rounded-lg bg-amber-500/10 p-2 text-xs text-amber-700 dark:text-amber-300">
          This app has no design tokens yet. Changing anything here adds them.
        </p>
      )}
      <div>
        <p className="mb-2 text-xs font-semibold text-foreground">Themes</p>
        <div className="grid grid-cols-4 gap-2">
          {THEME_PRESETS.map((p) => (
            <button
              key={p.name}
              type="button"
              onClick={() => commitTokens(p.tokens, `Theme: ${p.name}`)}
              className="flex flex-col items-center gap-1 rounded-lg p-1 text-[10px] text-muted-foreground hover:bg-secondary"
              title={p.name}
            >
              <span className="relative size-8 overflow-hidden rounded-full border border-border" style={{ background: p.tokens.bg }}>
                <span className="absolute bottom-0 right-0 size-4 rounded-tl-full" style={{ background: p.tokens.primary }} />
              </span>
              {p.name}
            </button>
          ))}
        </div>
      </div>
      <div className="grid grid-cols-2 gap-2">
        {(
          [
            ["primary", "Accent"],
            ["bg", "Background"],
            ["surface", "Cards"],
            ["text", "Text"],
            ["muted", "Subtle text"],
          ] as const
        ).map(([k, label]) => (
          <label key={k} className="flex items-center gap-2 rounded-lg border border-border p-1.5 text-xs text-foreground">
            <input
              type="color"
              value={/^#[0-9a-f]{6}$/i.test(t[k] || "") ? t[k] : "#000000"}
              onChange={(e) => setDraft((d) => ({ ...d, [k]: e.target.value }))}
              onBlur={() => draft[k] && commitTokens({ [k]: draft[k] }, `${label} colour`)}
              className="size-7 cursor-pointer rounded border-0 bg-transparent p-0"
            />
            {label}
          </label>
        ))}
      </div>
      {Object.keys(draft).length > 0 && (
        <Button size="sm" onClick={() => commitTokens(draft, "Colours")}>
          Apply colours
        </Button>
      )}
      {ratio !== null && ratio < 4.5 && (
        <p className="text-xs text-amber-600 dark:text-amber-400">
          Text vs background contrast is {ratio.toFixed(1)}:1. Aim for 4.5:1 or more so it&apos;s readable in sunlight.
        </p>
      )}
      <label className="flex flex-col gap-1 text-xs text-foreground">
        Corner roundness · {radiusPx}px
        <input
          type="range"
          min={0}
          max={28}
          value={radiusPx}
          onChange={(e) => setDraft((d) => ({ ...d, radius: `${e.target.value}px` }))}
          onMouseUp={() => draft.radius && commitTokens({ radius: draft.radius }, "Corner roundness")}
          onTouchEnd={() => draft.radius && commitTokens({ radius: draft.radius }, "Corner roundness")}
          onKeyUp={() => draft.radius && commitTokens({ radius: draft.radius }, "Corner roundness")}
          className="accent-primary"
        />
      </label>
      <div className="flex flex-col gap-1 text-xs text-foreground">
        Font
        <div className="grid grid-cols-2 gap-1.5">
          {FONT_CHOICES.map((f) => (
            <button
              key={f.label}
              type="button"
              onClick={() => commitTokens({ font: f.value }, `Font: ${f.label}`)}
              style={{ fontFamily: f.value }}
              className={`rounded-lg border px-2 py-1.5 text-sm ${t.font === f.value ? "border-primary bg-primary/10" : "border-border"}`}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>
      <p className="text-[11px] text-muted-foreground">Design changes are instant and free. No AI is involved, so the layout never shifts.</p>
    </div>
  );
}

export function ElementPanel({
  selected,
  inSource,
  onStartSelect,
  directEdit,
  busy,
  onAsk,
  onSelectedChange,
}: {
  selected: SelectedElement | null;
  inSource: boolean;
  onStartSelect: () => void;
  directEdit: (fn: (el: HTMLElement) => void, note: string) => boolean;
  busy: boolean;
  onAsk: (request: string) => void;
  /** Keep the host's snapshot of the element current after a direct edit. */
  onSelectedChange: (patch: Partial<SelectedElement>) => void;
}) {
  const [text, setText] = useState("");
  const [ask, setAsk] = useState("");
  // Reset the drafts when a DIFFERENT element is picked - not when a direct
  // edit merely refreshes the snapshot (that would wipe a half-typed question).
  const pickKey = selected ? selected.path.join(".") + "|" + selected.tag : "";
  useEffect(() => {
    setText(selected?.text || "");
    setAsk("");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pickKey, selected?.text]);

  if (!selected) {
    return (
      <div className="flex flex-col items-center gap-3 py-6 text-center">
        <MousePointerClick aria-hidden="true" size={22} className="text-primary" />
        <p className="text-sm text-foreground">Tap any part of your app to change it</p>
        <Button size="sm" variant="outline" onClick={onStartSelect}>
          Start selecting
        </Button>
      </div>
    );
  }
  // Only a true text leaf may have its text replaced: writing textContent on
  // an element with child tags (<b>, <br>, <img>) would flatten them. The
  // snapshot HTML is cut at 1800 chars with "...", which also rules it out.
  const leaf = !selected.html.endsWith("...") && !/<[a-z]/i.test(selected.html.replace(/^<[^>]*>/, ""));
  return (
    <div className="flex flex-col gap-3">
      <p className="text-xs text-muted-foreground">
        Selected <code className="rounded bg-secondary px-1">&lt;{selected.tag}&gt;</code>
      </p>
      {inSource ? (
        <>
          {leaf && (
            <div className="flex flex-col gap-1 text-xs text-foreground">
              <label htmlFor="studio-direct-edit-text">Text</label>
              <div className="flex gap-1.5">
                <Input id="studio-direct-edit-text" value={text} onChange={(e) => setText(e.target.value)} className="h-8" />
                <Button
                  size="sm"
                  variant="outline"
                  disabled={text === selected.text}
                  onClick={() => {
                    // textContent, full length: nothing is truncated or case-transformed on the way in.
                    if (directEdit((el) => { if (el.children.length === 0) el.textContent = text; }, `Text: "${text.slice(0, 30)}"`)) onSelectedChange({ text });
                  }}
                >
                  Set
                </Button>
              </div>
            </div>
          )}
          <div className="grid grid-cols-2 gap-2">
            <label className="flex items-center gap-2 rounded-lg border border-border p-1.5 text-xs text-foreground">
              <input
                type="color"
                defaultValue={rgbToHex(selected.color)}
                onBlur={(e) => {
                  const v = e.target.value;
                  if (v !== e.target.defaultValue && directEdit((el) => el.style.setProperty("color", v), "Text colour")) onSelectedChange({ color: v });
                }}
                className="size-7 cursor-pointer rounded border-0 bg-transparent p-0"
              />
              Text colour
            </label>
            <label className="flex items-center gap-2 rounded-lg border border-border p-1.5 text-xs text-foreground">
              <input
                type="color"
                defaultValue={rgbToHex(selected.background)}
                onBlur={(e) => {
                  const v = e.target.value;
                  if (v !== e.target.defaultValue && directEdit((el) => el.style.setProperty("background", v), "Background colour")) onSelectedChange({ background: v });
                }}
                className="size-7 cursor-pointer rounded border-0 bg-transparent p-0"
              />
              Fill
            </label>
          </div>
          <div className="flex items-center gap-1.5 text-xs text-foreground">
            Size
            {[
              ["A−", 0.85],
              ["A+", 1.2],
            ].map(([l, f]) => (
              <Button
                key={l as string}
                size="sm"
                variant="outline"
                onClick={() => {
                  // Computed from the CURRENT size and then refreshed, so repeated taps compound.
                  const next = Math.min(96, Math.max(8, Math.round((parseFloat(selected.fontSize) || 16) * (f as number))));
                  if (directEdit((el) => el.style.setProperty("font-size", `${next}px`), "Text size")) onSelectedChange({ fontSize: `${next}px` });
                }}
              >
                {l}
              </Button>
            ))}
            <Button size="sm" variant="ghost" className="ml-auto text-destructive" onClick={() => directEdit((el) => el.remove(), `Removed <${selected.tag}>`)}>
              <Trash2 aria-hidden="true" size={13} />
            </Button>
          </div>
        </>
      ) : (
        <p className="rounded-lg bg-secondary p-2 text-xs text-muted-foreground">
          The app&apos;s code draws this part while it runs, so it can only be changed by asking the AI below.
        </p>
      )}
      <div className="flex flex-col gap-1.5 border-t border-border pt-3">
        <p className="text-xs font-medium text-foreground">Ask the AI about this element</p>
        <Textarea value={ask} onChange={(e) => setAsk(e.target.value)} rows={2} className="resize-none text-sm" placeholder="e.g. turn this into a card with an icon" />
        <Button size="sm" disabled={!ask.trim() || busy} onClick={() => onAsk(ask.trim())} className="gap-1.5">
          <Sparkles aria-hidden="true" size={13} /> Change it
        </Button>
      </div>
    </div>
  );
}
