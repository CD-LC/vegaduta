#!/usr/bin/env bash
# Frame-level leak scan: sample frames from each mp4, OCR with macOS Vision,
# flag personal-data patterns. Usage: leak-scan.sh <dir-of-mp4s>
set -u
DIR="${1:-$HOME/vegaduta-academy}"
WORK=$(mktemp -d)
PATTERN='919845|\+91[0-9]|/Users/|mroads|eyJhbGci|api[_-]?key|BEGIN.*PRIVATE|password:|AKIA[0-9A-Z]{16}'

cat > "$WORK/ocr.swift" <<'SWIFT'
import Vision
import AppKit
let url = URL(fileURLWithPath: CommandLine.arguments[1])
guard let img = NSImage(contentsOf: url)?.cgImage(forProposedRect: nil, context: nil, hints: nil) else { exit(2) }
let req = VNRecognizeTextRequest(); req.recognitionLevel = .fast
try? VNImageRequestHandler(cgImage: img).perform([req])
let text = (req.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: " ")
print(text)
SWIFT

swiftc -O "$WORK/ocr.swift" -o "$WORK/ocr" 2>/dev/null

fail=0
for v in "$DIR"/*.mp4; do
  n=$(basename "$v" .mp4)
  dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$v" | cut -d. -f1)
  hits=""
  for ((t=2; t<dur; t+=5)); do
    f="$WORK/f-$n-$t.png"
    ffmpeg -y -v error -ss "$t" -i "$v" -frames:v 1 "$f" 2>/dev/null
    txt=$("$WORK/ocr" "$f" 2>/dev/null)
    m=$(echo "$txt" | grep -oiE "$PATTERN" | sort -u | tr '\n' ',')
    [ -n "$m" ] && hits="$hits t=${t}s[$m]"
    rm -f "$f"
  done
  if [ -n "$hits" ]; then echo "LEAK $n:$hits"; fail=1; else echo "clean $n (${dur}s)"; fi
done
exit $fail
