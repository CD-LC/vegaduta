# VegaDūta Academy — video pipeline

How-to videos generated from single-source lesson scripts: the same file
that feeds screen capture also feeds narration and the written lesson.
Nothing here is manual screen recording — a UI change means re-running the
script, not re-booking a presenter.

## Lesson anatomy

```
academy/lessons/NN-slug/
  script.mjs     scenes: [{ say: "narration", do: [[action...]] }]  (also
                 the voice/title config) — the single source of truth
  lesson.md      the written version (renders in the docs site)
  dist/          generated: clips/, scene-NN.mp3/.vtt, <slug>.mp4/.srt
                 (gitignored — never commit rendered media)
```

## Producing one episode

```bash
# prerequisites: the personal stack running (scripts/vegaduta personal),
# ffmpeg on PATH, `pip install edge-tts`, and an owner password you control
# (scripts/vegaduta reset-password prints a one-time setup link;
# POST /api/local-auth/setup {token, password} mints it).

export ACADEMY_PASSWORD=<owner-password>

# 1. capture screen clips            (cd tests/e2e — playwright lives here)
cd tests/e2e && node video/record.mjs ../../academy/lessons/00-orientation

# 2. narrate                          (edge-tts → mp3 + word-timed .vtt)
academy/tools/narrate.sh academy/lessons/00-orientation

# 3. assemble                         (ffmpeg → ep00-orientation.mp4 + .srt)
academy/tools/assemble.sh academy/lessons/00-orientation
```

Output: `academy/lessons/00-orientation/dist/ep00-orientation.mp4` +
`.srt` captions, ready to upload to the academy topic. Title/end cards are
generated; brand color 0x3fe6cf on 0x0b0e14.

## Authoring a new lesson

1. Copy an existing `script.mjs`. Each scene: `say` (narrated text — keep
   to 1–3 sentences) and `do` (the on-screen action list).
2. Action verbs: `goto`, `click`, `fill`, `hover`, `highlight` (pulsing
   outline — use it on the control you're talking about), `waitFor`,
   `scroll`, `keys`, `wait`. Selectors are Playwright locators
   (`text=…`, `css=…`, `role=…`).
3. Write `lesson.md` alongside — it's the searchable text twin.
4. Run the three commands above; watch the result once before publishing.

## Conventions

- 1280×720, 30 fps, h264, AAC. Voice `en-US-ChristopherNeural`, rate −4%.
- One concept per scene; the clip auto-freezes on the last frame while
  narration finishes, so never end a scene mid-animation.
- Secrets never appear on camera: scenes that touch `/secrets` blur or
  skip values; the recorder runs on the personal stack where values are
  already write-only.
- Episode target: 5–8 minutes (roughly 8–12 scenes).

## Series map

The flagship series **"The Ledger"** builds an AI newspaper across the
episodes — see `academy/lessons/INDEX.md` for the full curriculum covering
every console surface.
