import json, os, sys, soundfile as sf, numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import paths as P
from kokoro_onnx import Kokoro
k = Kokoro(f"{P.TTS}/kokoro-v1.0.onnx", f"{P.TTS}/voices-v1.0.bin")
voice = sys.argv[1] if len(sys.argv) > 1 else "af_heart"
lines = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "vo.json")))
out = {}
for reel, items in lines.items():
    for key, text in items:
        text = text.replace("VegaDuta", "Vega Doota")
        s, sr = k.create(text, voice=voice, speed=1.02, lang="en-us")
        s = np.concatenate([np.zeros(int(0.08*sr)), s, np.zeros(int(0.25*sr))])
        p = f"{P.AUDIO}/vo_{reel}_{key}.wav"; sf.write(p, s, sr)
        out[f"{reel}_{key}"] = round(len(s)/sr, 2); print(reel, key, out[f"{reel}_{key}"], "s")
json.dump(out, open(f"{P.AUDIO}/vo_durations.json", "w"), indent=1)
