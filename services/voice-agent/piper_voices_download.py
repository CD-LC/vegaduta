#!/usr/bin/env python3
"""Download a Piper voice pair (.onnx + .onnx.json) with sha256 verification.

Used by the Dockerfile to bake the default voice into the image; also usable
at runtime to add voices under PIPER_MODEL_DIR:

    python piper_voices_download.py en_US-lessac-medium /opt/piper-voices
    python piper_voices_download.py en_GB-alan-medium /opt/piper-voices \
        --onnx-sha256 <sha> --json-sha256 <sha>

Hashes for the baked default are hardcoded below - the same pin-everything
posture as every other external fetch in this repo's images.
"""
import hashlib
import pathlib
import sys
import urllib.request

HF_BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0"
# voice-name -> path inside the HF repo's en/<lang>/<name>/<quality>/ tree.
VOICE_PATHS = {
    "en_US-lessac-medium": "en/en_US/lessac/medium/en_US-lessac-medium",
}
KNOWN_SHA256 = {
    "en_US-lessac-medium": (
        "5efe09e69902187827af646e1a6e9d269dee769f9877d17b16b1b46eeaaf019f",
        "efe19c417bed055f2d69908248c6ba650fa135bc868b0e6abb3da181dab690a0",
    ),
}


def fetch(url: str, want: str | None) -> bytes:
    data = urllib.request.urlopen(url, timeout=120).read()
    if want is not None:
        got = hashlib.sha256(data).hexdigest()
        if got != want:
            raise SystemExit(f"{url}: sha256 mismatch ({got} != {want})")
    return data


def main() -> None:
    voice = sys.argv[1]
    out_dir = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else "/opt/piper-voices")
    onnx_sha = json_sha = None
    for i, a in enumerate(sys.argv):
        if a == "--onnx-sha256":
            onnx_sha = sys.argv[i + 1]
        if a == "--json-sha256":
            json_sha = sys.argv[i + 1]
    if onnx_sha is None:
        onnx_sha, json_sha = KNOWN_SHA256.get(voice, (None, None))
        if onnx_sha is None:
            raise SystemExit(f"no baked sha256 for {voice} - pass --onnx-sha256/--json-sha256")
    if voice in VOICE_PATHS:
        path = VOICE_PATHS[voice]
    else:
        # <lang>-<locale>_<name>-<quality> -> <lang>/<lang>_<locale>/<name>/<quality>/
        locale, rest = voice.split("-", 1)
        name, quality = rest.rsplit("-", 1)
        path = f"{locale.split('_')[0]}/{locale}/{name}/{quality}/{voice}"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / f"{voice}.onnx").write_bytes(fetch(f"{HF_BASE}/{path}.onnx", onnx_sha))
    (out_dir / f"{voice}.onnx.json").write_bytes(fetch(f"{HF_BASE}/{path}.json", json_sha))
    print(f"baked {voice} into {out_dir}")


if __name__ == "__main__":
    main()
