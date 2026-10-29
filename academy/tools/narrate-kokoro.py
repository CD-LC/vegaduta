#!/usr/bin/env python3
"""Narrate a lesson's scenes with Kokoro-82M (local neural TTS, no key).
Produces dist/scene-NN.mp3 + scene-NN.vtt (SRT-shaped cues, one per
synthesised segment) — the same contract as narrate.sh / narrate-elevenlabs.

  narrate-kokoro.py <lessonDir> [voice]

voice defaults to af_heart (warm natural en-US). Requires the lesson venv:
  python3.12 -m venv .venv-tts && .venv-tts/bin/pip install kokoro soundfile
and espeak-ng on PATH (brew install espeak-ng) for en-US phonemization.
"""
import json, os, re, subprocess, sys

import numpy as np
import soundfile as sf
from kokoro import KPipeline

DIR = sys.argv[1]
OUT = os.path.join(DIR, "dist")
os.makedirs(OUT, exist_ok=True)
VOICE = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] else "af_heart"

says = json.loads(subprocess.run(
    ["node", "--input-type=module", "-e",
     "import { pathToFileURL } from 'node:url';"
     f"const s=(await import(pathToFileURL('{os.path.abspath(DIR)}/script.mjs').href)).default;"
     "console.log(JSON.stringify(s.scenes.map(sc=>sc.say)))"],
    capture_output=True, text=True, check=True).stdout)

pipe = KPipeline(lang_code="a")

def stamp(t):
    return f"{int(t//3600):02}:{int(t//60)%60:02}:{int(t%60):02},{int(round(t%1*1000)):03}"

for i, say in enumerate(says, 1):
    n = f"{i:02d}"
    texts, chunks, at = [], [], 0.0
    # one pipeline call per sentence -> caption cue per sentence + natural pause
    for sent in re.findall(r"[^.!?]+[.!?]+(?:['\"\u201d)]*)\s*|[^.!?]+$", say):
        sent = sent.strip()
        if not sent:
            continue
        for gs, _ps, audio in pipe(sent, voice=VOICE, speed=0.98):
            dur = len(audio) / 24000
            texts.append((at, at + dur, re.sub(r"\s+", " ", gs).strip()))
            chunks.append(audio)
            at += dur
        gap = np.zeros(int(24000 * 0.12), dtype=np.float32)  # breath between sentences
        chunks.append(gap)
        at += 0.12
    wav = np.concatenate(chunks)
    sf.write(f"{OUT}/scene-{n}.wav", wav, 24000)
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", f"{OUT}/scene-{n}.wav",
                    "-codec:a", "libmp3lame", "-q:a", "3", f"{OUT}/scene-{n}.mp3"],
                   check=True)
    os.remove(f"{OUT}/scene-{n}.wav")
    cues = "\n\n".join(f"{k+1}\n{stamp(s)} --> {stamp(e)}\n{t}"
                       for k, (s, e, t) in enumerate(texts))
    open(f"{OUT}/scene-{n}.vtt", "w").write(cues + "\n")
    print(f"narrated scene {n} ({at:.1f}s, {len(texts)} cues)")
