# Landing-page media generators

Everything in `web/public/media/landing/` is produced from the HTML in this folder — no paid APIs, no
design tools, just Playwright (already in `web/node_modules`) and a local ffmpeg. All data in the
scenes is fictional ("Sharma General Store", "priya.k@example.in"); every video frame carries the
"Product walkthrough · simulated data" pill.

| Source | Output | Notes |
|---|---|---|
| `storyboard.html` | `demo-60s.mp4`, `demo-60s.webm`, `demo-poster.jpg` | 1280×720, 30 fps, 60 s, silent; six ~10 s scenes driven by `window.__start()` |
| `og.html` | `og-image.png` | 1200×630, ≤ 350 KB (palette PNG) |
| `hero-still.html` | `hero-still.png` | 1600×1000, ≤ 500 KB — scene-2 composition, no captions |

`common.mjs` holds the shared bits (browser launch, logo injection, font wait, ffmpeg/sharp paths);
`preview.mjs` is a fast still-frame checker for the storyboard. Nothing here is imported by the
Next.js app — these are authoring scripts only.

## Regenerate

```powershell
cd web/scripts/landing-media
node record.mjs                    # ~2.5 min: renders the storyboard frame-by-frame, encodes mp4/webm, poster, review frames
node render-images.mjs             # og-image.png + hero-still.png (add "og" or "hero" to do one)
node preview.mjs 5 16 26 36 47 55  # optional: fast stills of the storyboard at given seconds (fake clock)
Copy-Item out\demo-60s.mp4,out\demo-60s.webm,out\demo-poster.jpg,out\hero-still.png,out\og-image.png ..\..\public\media\landing\
```

Outputs land in `./out/` (git-ignored by intent — copy the five files above into
`web/public/media/landing/`). `record.mjs --encode-only` re-runs only the ffmpeg stage on the last
raw screencast.

## Toolchain assumptions (override with env vars)

- **Browser:** Playwright launches `chromium.launch({ channel: "chromium", headless: true })` — the
  full Chromium build, because the `headless_shell` variant is not installed on the authoring
  machine. If that errors it retries headed. `WEB_ROOT` points at `web/` (auto-detected).
- **ffmpeg:** `FFMPEG` env var, default `ffmpeg` on `PATH` (needs libx264 + libvpx-vp9; the
  static build bundled with the `imageio-ffmpeg` Python package works too). There is no ffprobe; `ffmpeg -i <file>` prints durations.
- **Logo:** `LOGO` env var, default `web/public/logo-mark.png`, injected as a data URI into every
  `img.logo` after page load so the HTML files stay path-independent.
- **Fonts:** Sora / Inter / Noto Sans Devanagari / JetBrains Mono are self-hosted in `fonts/`
  (`fonts/fonts.css`, woff2, OFL). `node fetch-fonts.mjs` re-downloads them from Google Fonts if the
  folder is ever lost. Rendering never touches the network; `openPage()` force-loads every face and
  warns if one is missing (a missing face silently falls back to Segoe UI / Nirmala UI / Consolas and
  changes line wrapping, which is how the first drafts of the Hindi bubbles went wrong).
- `OUT` env var changes the output directory.

## How the video recorder works

`record.mjs` renders the storyboard **frame by frame** rather than trusting a screen recording:

1. Opens `storyboard.html` at 1280×720, injects the logo, force-loads fonts.
2. Overrides `performance.now` and `requestAnimationFrame` in the page with a stepped clock (the
   storyboard's timeline is rAF-driven, `at(seconds, fn)` at the bottom of the HTML).
3. For each of the 1800 frames: advances the clock by exactly 1/30 s, runs the due rAF callbacks, then
   syncs every CSS transition/animation to that clock through the Web Animations API
   (`document.getAnimations()` → `pause()` + `currentTime`), and captures the frame over CDP.
4. Pipes the PNGs into ffmpeg as a lossless `out/cut.mkv`, from which the mp4 (`libx264 crf 22 slow`),
   the webm (`libvpx-vp9 crf 34`), the poster (t = 15 s), `frame-1..6.png` and the seam frames are cut.
5. Prints the worst timeline lag (should be ≤ 1 frame) and where the black tail starts (must be < 59.6 s).

Playwright's `recordVideo` screencast is still available as `node record.mjs --screencast` (25 fps,
software-rendered, and its clock drifted 0.1–0.9 s per minute between runs on this machine, which is
why it was retired as the default). `--encode-only` re-runs step 4–5 on the existing `cut.mkv`.

Scene 1 fades in from the canvas and scene 6 fades out to it (fade starts at 58.5 s), so the muted
autoplay loop has a clean seam. Edit copy or timing in `storyboard.html` and re-run `record.mjs`
(~2.5 min). `preview.mjs` shows stills of any second without a full render.
