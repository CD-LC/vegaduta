# PWA install assets — what exists, and what a human still has to export

`web/public/manifest.webmanifest` deliberately references **only files that
exist on disk today**. A manifest entry pointing at a missing file is worse
than no entry: Chrome drops the whole icon set and the install prompt falls
back to a screenshot of the favicon.

This file is the export brief for the gaps. It contains no binaries — the
agent that wrote the manifest is not allowed to generate images.

## What already exists (verified 2026-09-12)

Sizes below were read out of each PNG's IHDR header (`od -An -tu4 -j16 -N4`),
not inferred from the filename.

| File (in `web/public/`) | Real pixels | Used by the manifest as |
|---|---|---|
| `favicon-16.png` | 16×16 | — (linked from `layout.tsx` only) |
| `favicon-32.png` | 32×32 | `purpose: any` |
| `favicon-48.png` | 48×48 | `purpose: any` |
| `favicon-64.png` | 64×64 | `purpose: any` |
| `apple-touch-icon.png` | 180×180 | `purpose: any` |
| `icon-192.png` | 192×192 | `purpose: any` |
| `icon-512.png` | 512×512 | `purpose: any` |
| `maskable-192.png` | 192×192 | `purpose: maskable` |
| `maskable-512.png` | 512×512 | `purpose: maskable` |
| `mstile-150.png` | 150×150 | — (Windows tile, `layout.tsx` `other`) |
| `og-image.png` | 1200×630 | — (link unfurls, not installability) |

Source art available for re-exports:

- `web/public/logo-monogram.png` — **512×512, already square.** This is the
  correct source for any new square icon size.
- `web/public/logo-mark.png` — 1013×740, **not square**. Exporting a square
  icon from this needs deliberate padding, so prefer the monogram.
- `web/public/logo-mark-orange.png` — 1013×740, also not square.

`icon-192.png` and `maskable-192.png` were opened and visually confirmed to be
the winged human-in-helmet emblem (the maskable variant carries visibly more
padding, i.e. a real safe zone). That matches the standing brand rule, so they
do not need re-exporting.

## Gap 1 — screenshots (blocks the richer desktop install dialog)

There are **no** `screenshots` entries in the manifest because no screenshot
files exist. Without at least one `form_factor: "wide"` screenshot, Chrome on
desktop shows the plain install bubble instead of the richer app-install
dialog.

A human should capture and drop in:

| Wanted file | Size | Content |
|---|---|---|
| `web/public/icons/screenshot-wide.png` | 1280×800 | Desktop console, signed-out, on a route that needs no account |
| `web/public/icons/screenshot-narrow.png` | 750×1334 | The same surface at phone width |

Then add to `manifest.webmanifest`:

```json
"screenshots": [
  { "src": "/icons/screenshot-wide.png",   "sizes": "1280x800", "type": "image/png", "form_factor": "wide" },
  { "src": "/icons/screenshot-narrow.png", "sizes": "750x1334", "type": "image/png", "form_factor": "narrow" }
]
```

Do not add those entries before the files land.

## Gap 2 — monochrome icon (themed launcher icons)

Android 13+ and some desktop launchers tint a `purpose: "monochrome"` icon to
match the system theme. No such asset exists.

Wanted: `web/public/icons/icon-monochrome-512.png`, 512×512, a single-colour
(white) silhouette of the emblem on full transparency, exported from
`logo-monogram.png`. Then append to `icons`:

```json
{ "src": "/icons/icon-monochrome-512.png", "sizes": "512x512", "type": "image/png", "purpose": "monochrome" }
```

## Gap 3 — shortcut icon

`manifest.webmanifest` declares one shortcut ("Try without an account" → `/try`)
with no `icons` array, because no 96×96 shortcut art exists. A shortcut without
an icon is valid and still appears in the launcher's context menu.

If someone wants art: `web/public/icons/shortcut-try-96.png`, 96×96, exported
from `logo-monogram.png`, then add `"icons": [{ "src": "/icons/shortcut-try-96.png", "sizes": "96x96", "type": "image/png" }]`
to that shortcut entry.

## Gap 4 — "opens offline" is NOT true yet

Installing gives the app a dock/launcher icon and its own window. It does
**not** yet make it open without a network.

`web/public/webllm-sw.js` is the only service worker in this app (registered
from `app/lib/edge/tier1.ts` as `TIER1_SERVICE_WORKER_URL = "/webllm-sw.js"`).
It registers exactly two listeners, `install` and `activate` — there is **no
`fetch` handler**, so no document or asset is ever served from cache. Launching
the installed window with the network down therefore shows the browser's
offline error page.

Nothing in this track changed that file, and a *second* service worker must not
be added — two workers claiming the same scope fight over control. The fix is a
`fetch` handler inside the existing `webllm-sw.js`. It is not in this track's
scope and has not been done.

## Gap 5 — not a gap, deliberately

- **384×384** is commonly listed in PWA checklists. It is omitted on purpose:
  Android picks the splash icon from the 512 entry, and adding a size with no
  file behind it would break the set.
- **SVG icon.** `web/public/favicon.svg` is 135 bytes and its entire body is
  `<image width="1024" height="1024"></image>` — an `<image>` element with no
  `href`, i.e. it renders nothing. (The comment block in `web/app/layout.tsx`
  says the same about the copy in the brand handoff bundle.) It is therefore
  **not** referenced from the manifest, and should not be added until someone
  exports a real one.
