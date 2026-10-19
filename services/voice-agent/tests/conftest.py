"""
Puts voice-agent/ on sys.path so tests can `from app.x import y` regardless
of where pytest is invoked from (mirrors sidecar/tests/conftest.py's own
one-job docstring/rationale for the exact same pattern).

Also pre-stubs `livekit.local_inference` (LiveKit's own native
ONNX/turn-detector extension, a compiled `_native` module bundled inside the
`livekit-agents` package itself, unrelated to any plugin this repo chooses)
BEFORE `livekit.agents` is ever imported. This is deliberate, not a
workaround for a bug in our code: that native extension failed to load on at
least one real dev machine used while building this service (a clean
access-violation crash on import, confirmed with `python -X faulthandler`,
root-caused to the machine's ONNX runtime native DLLs, nothing in this
repo). None of this test suite exercises that extension anyway - it backs
LiveKit Cloud's optional semantic turn-detector, which this deployment
doesn't use (see main.py: `turn_handling=TurnHandlingOptions(turn_detection=
"vad")` - Silero VAD only) - so stubbing it keeps these tests fast,
deterministic, and runnable without a working native/ONNX toolchain on every
machine, on any platform, while still exercising the REAL `livekit.agents`
STT/LLM base classes for everything these tests actually check.
"""
import sys
import types
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

if "livekit.local_inference" not in sys.modules:
    _fake_native = types.ModuleType("livekit.local_inference._native")
    _fake_native.EOT = object()
    _fake_native.EOT_MAX_SAMPLES = 0
    _fake_native.VAD = object()
    _fake_native.VAD_WINDOW_SAMPLES = 0
    _fake_native.init_eot = lambda *a, **k: None
    _fake_native.init_vad = lambda *a, **k: None

    _fake_pkg = types.ModuleType("livekit.local_inference")
    for _attr in ("EOT", "EOT_MAX_SAMPLES", "VAD", "VAD_WINDOW_SAMPLES", "init_eot", "init_vad"):
        setattr(_fake_pkg, _attr, getattr(_fake_native, _attr))

    sys.modules["livekit.local_inference"] = _fake_pkg
    sys.modules["livekit.local_inference._native"] = _fake_native
