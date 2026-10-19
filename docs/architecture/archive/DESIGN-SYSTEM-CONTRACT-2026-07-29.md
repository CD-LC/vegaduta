# Dark-mode redesign — token contract (2026-07-29)

Binding contract for every agent working the visual redesign. The foundation
(tokens + CSS utilities) is already built and verified in
`web/app/globals.css`. **Do not re-derive colours, and do not edit
`globals.css` — one session owns it.** Build on what is below.

## Why this exists

The old dark theme measured as the direct cause of "too dark, flat,
monochromatic": base surface at **L\*=2.47** (effectively black — below the
`#0E`–`#1A` floor every current dark-mode guide converges on), consecutive
elevation steps only ~1.5–2.4 L\* apart, and chroma of 4–9/255, i.e. grey.
Shadows do not read on dark surfaces, so **lightness is the only depth cue** —
and there wasn't enough of it.

## 1. Elevation ladder (dark)

Use these instead of inventing background colours. Higher = lighter = closer.

| Token | Hex | L\* | Use for |
|---|---|---|---|
| `--surface-sunken` | `#080a10` | 2.77 | recessed wells, chat logs, code blocks |
| `--surface-page` | `#0d0f17` | 4.42 | app canvas |
| `--surface-card-alt` | `#12151f` | 6.90 | a well *inside* a card |
| `--surface-card` | `#171b28` | 9.95 | default card / panel |
| `--surface-raised` | `#1d2231` | 13.39 | popovers, hover state of a card |
| `--surface-hover` | `#232839` | 16.36 | hovered rows, active nav |
| `--surface-border` | `#2b3145` | 20.60 | default border |
| `--surface-border-strong` | `#3a4159` | 27.85 | inputs, emphasis border |

Light mode has the same rungs (`--surface-sunken`/`--surface-raised` added);
light inverts the cue — recessed gets darker, raised stays white + shadow.

**Every `--text-*` token is AA (≥4.5:1) on every rung.** Worst case is
`--text-muted` at 4.60:1 on `--surface-hover`. `--text-faint` was lifted to
`#8b93a3` to keep AA on the new hover rung. Do not hand-pick text greys.

## 2. Category hues — identity ("what kind of thing is this")

Six hues, each ≥35° apart, all AA on every rung (worst 5.92:1).

| Token | Dark | Light | Meaning |
|---|---|---|---|
| `--cat-assistant` | `#a99bff` | `#5643e6` | conversational / general agents |
| `--cat-comms` | `#5ba8ff` | `#0b6bc4` | channels, messaging, delivery |
| `--cat-data` | `#3fe6cf` | `#0f7a70` | knowledge, retrieval, storage |
| `--cat-analysis` | `#8fe36a` | `#467324` | evals, reporting, quality |
| `--cat-automation` | `#ffb24d` | `#9a5b08` | workflows, triggers, schedules |
| `--cat-creative` | `#ff8fbf` | `#be2a76` | image / voice / video generation |

### The one hard rule

**Colour is never the only cue. A category MUST also carry its own icon.**
This is not a style preference — simulating protanopia collapses
`--cat-assistant` and `--cat-comms` to a perceptual distance of **6.7**
(deuteranopia 18.4; tritanopia puts automation/creative at 21.2). A user with
the most common CVD cannot tell those two apart by hue. Use `.cat-chip`, which
exists to force the icon+tint pairing.

## 3. State hues — execution status ("what is it doing")

| Token | Dark | Light |
|---|---|---|
| `--state-running` | `#3fe6cf` | `#0a7188` |
| `--state-success` | `#45dda0` | `#047857` |
| `--state-warning` | `#ffb24d` | `#9a5b08` |
| `--state-error` | `#ff6b81` | `#cc2130` |
| `--state-idle` | `#94a0bb` | `#636a78` |
| `--state-queued` | `#a99bff` | `#5643e6` |

State and category deliberately share some hues (data/cyan vs running;
automation/amber vs warning). They are separated by **form, not hue**:

- a **category** is a rounded **square** chip with an icon (`.cat-chip`)
- a **state** is a **circle** dot or a pill (`.state-dot` / `.state-pill`), and
  animates when live

That distinction survives greyscale and CVD; hue alone does not.

## 4. CSS utilities (already in `globals.css`)

Each derives its wash/border/ring from a single custom property via
`color-mix`, so a call site sets **one** value:

```tsx
// category: set --cat, put an icon inside. Never a bare coloured dot.
<span className="cat-chip" style={{ ["--cat" as string]: "var(--cat-data)" }}>
  <Database size={15} />
</span>

// state: set --state. Add state-dot-live for running/in-progress.
<span className="state-dot state-dot-live"
      style={{ ["--state" as string]: "var(--state-running)" }} />

<span className="state-pill" style={{ ["--state" as string]: "var(--state-error)" }}>
  <span className="state-dot" /> Failed
</span>
```

| Class | What it does |
|---|---|
| `.elev-sunken` / `.elev-base` / `.elev-raised` | background + border for a depth level |
| `.card-interactive` | hover lift, border tint, accent hairline on top edge; tints to `--cat` if set |
| `.cat-chip` (+ `-sm` / `-lg`) | rounded-square icon chip, tinted from `--cat` |
| `.cat-tint` | text/icon colour only |
| `.state-dot` (+ `.state-dot-live`) | status circle; `-live` adds an expanding ring |
| `.state-pill` | tinted pill with border |
| `.state-meter` | progress bar inheriting the state hue |

All animation is wrapped in `prefers-reduced-motion: reduce`.

## 5. Rules for every agent

1. **Never edit `web/app/globals.css`.** One session owns it. If you need a
   new shared utility, report it back instead of adding it.
2. **Never run `npm install`.** Dependencies are installed; a concurrent
   install rolls back other agents' work.
3. **No raw hex, no Tailwind palette colours** (`bg-emerald-500`, `text-sky-400`
   …) in anything you touch. Use the tokens/utilities above. Replacing existing
   raw-palette usage in your scope with tokens is in scope and wanted.
4. **Every category colour ships with an icon.** See §2.
5. **Don't gut working behaviour to restyle it.** This is a visual redesign;
   data flow, props, and i18n keys stay as they are. `web/app/lib/i18n`'s
   `en.json` is fragile — use targeted edits, never reformat it.
6. **Verify.** A dev server is running at **http://localhost:60777**. Check the
   routes in your scope render and the browser console is clean.
7. **Stay in your file scope.** Scopes are disjoint on purpose so four sessions
   can run at once. If you need a file outside it, report the dependency.
