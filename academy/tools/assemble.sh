#!/usr/bin/env bash
# Assemble a finished episode: title card + per-scene (clip frozen/trimmed to
# narration length + audio) + end card → dist/<slug>.mp4 and dist/<slug>.srt.
#
#   academy/tools/assemble.sh <lessonDir>
#
# Requires ffmpeg + ffprobe on PATH.
set -euo pipefail
DIR="$1"; DIST="$DIR/dist"
SLUG=$(node --input-type=module -e "console.log((await import('$DIR/script.mjs')).default.slug)")
TITLE=$(node --input-type=module -e "console.log((await import('$DIR/script.mjs')).default.title)")
W=1280; H=720; PAD=0.7
WORK=$(mktemp -d)
[ -f "$DIST/cards/title.png" ] || (cd "$(dirname "$DIR")/../../tests/e2e" && node video/cards.mjs "$DIR")

dur() { ffprobe -v error -show_entries format=duration -of csv=p=0 "$1"; }

card() { # $1 png $2 out $3 dur — still image + silent audio
  ffmpeg -y -loop 1 -t "$3" -i "$1" -f lavfi -i "anullsrc=d=$3" \
    -vf "scale=$W:$H,fps=30,setsar=1" -c:v libx264 -pix_fmt yuv420p \
    -c:a aac -ar 44100 -ac 2 -shortest "$2" 2>/dev/null
}

card "$DIST/cards/title.png" "$WORK/title.mp4" 3.5
echo "[academy] title card"

i=0; list="$WORK/concat.txt"; : > "$list"
for clip in "$DIST"/clips/scene-*.webm; do
  i=$((i+1)); n=$(printf "%02d" $i)
  adur=$(dur "$DIST/scene-$n.mp3"); vdur=$(dur "$clip")
  target=$(awk "BEGIN{print $adur + $PAD}")
  freeze=$(awk "BEGIN{d=$target-$vdur; if(d>0) print d; else print 0}")
  ffmpeg -y -i "$clip" -i "$DIST/scene-$n.mp3" \
    -filter_complex "[0:v]scale=$W:$H,fps=30,tpad=stop_mode=clone:stop=$freeze,setsar=1[v];[1:a]apad=whole_dur=$target[a]" \
    -map "[v]" -map "[a]" -t "$target" -c:v libx264 -pix_fmt yuv420p \
    -c:a aac -ar 44100 -ac 2 "$WORK/scene-$n.mp4" 2>/dev/null
  echo "file '$WORK/scene-$n.mp4'" >> "$list"
  echo "[academy] scene $n (audio ${adur}s -> ${target}s)"
done

card "$DIST/cards/end.png" "$WORK/end.mp4" 3
{ echo "file '$WORK/title.mp4'"; cat "$list"; echo "file '$WORK/end.mp4'"; } > "$WORK/all.txt"
# Re-encode audio at the merge: -c copy would splice mismatched AAC headers
# (cards are 44.1k stereo, narration starts 24k mono) into a corrupt track.
ffmpeg -y -f concat -safe 0 -i "$WORK/all.txt" \
  -c:v copy -c:a aac -ar 44100 -ac 2 -b:a 160k "$DIST/$SLUG.mp4" 2>/dev/null

# word-timed captions -> single .srt with cumulative offsets (edge-tts
# .vtt output is actually SRT-shaped: comma decimals, numbered cues)
python3 - "$DIST" "$DIST/$SLUG.srt" <<'EOF'
import glob, re, subprocess, sys, os
TITLE_DUR = 3.5
out, idx, off = [], 1, TITLE_DUR
for srt in sorted(glob.glob(os.path.join(sys.argv[1], "scene-*.vtt"))
                  + glob.glob(os.path.join(sys.argv[1], "scene-*.srt"))):
    # edge-tts writes scene-NN.vtt (SRT-shaped); the ElevenLabs narrator
    # writes scene-NN.srt directly - same cue format either way
    audio = re.sub(r"\.(vtt|srt)$", ".mp3", srt)
    d = subprocess.run(["ffprobe","-v","error","-show_entries","format=duration","-of","csv=p=0",audio],
                       capture_output=True, text=True).stdout.strip()
    scene_dur = float(d) + 0.7
    for cue in re.split(r"\n\s*\n", open(srt).read().strip()):
        m = re.search(r"(\d+):(\d+):(\d+)[,.](\d+)\s*-->\s*(\d+):(\d+):(\d+)[,.](\d+)", cue)
        if not m: continue
        g = [int(x) for x in m.groups()]
        start = g[0]*3600+g[1]*60+g[2]+g[3]/1000+off; end = g[4]*3600+g[5]*60+g[6]+g[7]/1000+off
        fmt = lambda s: f"{int(s//3600):02}:{int(s//60)%60:02}:{int(s%60):02},{int(s%1*1000):03}"
        text = cue.strip().split("\n", 2)[-1]
        out.append(f"{idx}\n{fmt(start)} --> {fmt(end)}\n{text}\n"); idx += 1
    off += scene_dur
open(sys.argv[2], "w").write("\n".join(out))
EOF
rm -rf "$WORK"
echo "[academy] -> $DIST/$SLUG.mp4 (+ .srt)"
