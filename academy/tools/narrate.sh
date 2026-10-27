#!/usr/bin/env bash
# Narrate a lesson's scenes with edge-tts (Microsoft neural voices, local,
# no key). Produces dist/scene-NN.mp3 + scene-NN.vtt word-timed captions.
#
#   academy/tools/narrate.sh <lessonDir> [voice]
#
# voice defaults to the script's `voice` field or en-US-ChristopherNeural.
set -euo pipefail
DIR="$1"; OUT="$DIR/dist"
mkdir -p "$OUT"
VOICE="${2:-$(node -e "console.log((await import('$DIR/script.mjs')).default.voice||'')" --input-type=module 2>/dev/null || true)}"
VOICE="${VOICE:-en-US-ChristopherNeural}"
EDGE_TTS="$(command -v edge-tts || echo "$HOME/Library/Python/3.9/bin/edge-tts")"

# Prefer ElevenLabs when the key is present — real expressive voice with
# per-word timing for captions. edge-tts stays the zero-key fallback.
if [ -n "${ELEVENLABS_API_KEY:-}" ]; then
  node "$(dirname "$0")/narrate-elevenlabs.mjs" "$DIR"
  exit 0
fi

i=0
node --input-type=module -e "
  const s = (await import('$DIR/script.mjs')).default;
  s.scenes.forEach((sc, i) => console.log(i + '\t' + JSON.stringify(sc.say)));
" | while IFS=$'\t' read -r i say; do
  n=$(printf "%02d" $((i + 1)))
  text=$(node -e "console.log(JSON.parse(process.argv[1]))" "$say")
  "$EDGE_TTS" --voice "$VOICE" --rate=-4% --text "$text" \
    --write-media "$OUT/scene-$n.mp3" --write-subtitles "$OUT/scene-$n.vtt"
  echo "narrated scene $n"
done
