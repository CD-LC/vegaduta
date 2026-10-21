import json, sys, soundfile as sf, numpy as np
from kokoro_onnx import Kokoro
k = Kokoro("/home/user/tts/kokoro-v1.0.onnx", "/home/user/tts/voices-v1.0.bin")
voice = sys.argv[1] if len(sys.argv) > 1 else "af_heart"
lines = json.load(open("/home/user/reels/vo.json"))
out = {}
for reel, items in lines.items():
    for key, text in items:
        text = text.replace("VegaDuta", "Vega Doota")
        s, sr = k.create(text, voice=voice, speed=1.02, lang="en-us")
        s = np.concatenate([np.zeros(int(0.08*sr)), s, np.zeros(int(0.25*sr))])
        p = f"/home/user/reels/audio/vo_{reel}_{key}.wav"; sf.write(p, s, sr)
        out[f"{reel}_{key}"] = round(len(s)/sr, 2); print(reel, key, out[f"{reel}_{key}"], "s")
json.dump(out, open("/home/user/reels/audio/vo_durations.json", "w"), indent=1)
