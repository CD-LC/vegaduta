"""
Environment configuration for the voice-agent worker.

Deliberately mirrors naming already used elsewhere in this repo rather than
inventing new conventions - see each constant's comment for exactly which
existing var it mirrors and why the default differs where it does.
"""

from __future__ import annotations

import os


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, "").strip() or default)
    except ValueError:
        return default


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, "").strip() or default)
    except ValueError:
        return default


# --- LiveKit worker registration -------------------------------------------
# Same three var names core/voice's LiveKitTokenService and
# deploy/docker-compose.yml's `core` service already use
# (LIVEKIT_URL/LIVEKIT_API_KEY/LIVEKIT_API_SECRET), and also exactly what
# livekit-agents' own WorkerOptions/AgentServer reads from the environment
# when a value isn't passed explicitly - so simply exporting these three in
# deploy/.env (already true today for `core`) is enough to wire this worker
# up too, no new secret plumbing needed.
#
# The default below is deliberately the in-network compose DNS name
# (ws://livekit:7880), NOT core's own LIVEKIT_URL default
# (ws://localhost:7880, see deploy/docker-compose.yml's comment on that var -
# it's the BROWSER-facing address baked into issued tokens). This worker
# never hands its URL to a browser; it only ever dials LiveKit itself as a
# second server-side client, same network relationship LiveKitEgressService
# has to LiveKit's Egress API (http://livekit:7880/twirp/...) - so per
# CLAUDE.md's RULE 0, this is one of the internal-only exceptions that
# correctly stays off the public domain.
LIVEKIT_URL = _env("LIVEKIT_URL", "ws://livekit:7880")
#
# SEC-044: NO default for the key/secret. They used to fall back to
# "devkey"/"devsecret-change-me-32-bytes-min", so a deployment that forgot to
# set them silently registered with a publicly-known credential pair (and any
# LiveKit server still carrying that pair would accept anyone who read this
# file). require_livekit_credentials() below is the fail-closed startup rule
# for every way this worker is launched.
LIVEKIT_API_KEY = _env("LIVEKIT_API_KEY").strip()
LIVEKIT_API_SECRET = _env("LIVEKIT_API_SECRET").strip()

# The two former hard-coded defaults. Still refused if set explicitly - they
# are published in this repo's history, so they are not secrets.
_KNOWN_PLACEHOLDER_KEYS = frozenset({"devkey"})
_KNOWN_PLACEHOLDER_SECRETS = frozenset({"devsecret-change-me-32-bytes-min"})


class ConfigError(RuntimeError):
    """Required configuration is missing or unsafe; the worker must not start."""


def require_livekit_credentials(api_key: str | None = None, api_secret: str | None = None) -> None:
    """Fail closed at startup when the LiveKit key/secret are unset or placeholders.

    Arguments default to the module-level values; they exist so tests can
    check the rule without mutating the environment and reloading this module.
    Error messages name the variable but NEVER echo its value.
    """
    key = LIVEKIT_API_KEY if api_key is None else api_key.strip()
    secret = LIVEKIT_API_SECRET if api_secret is None else api_secret.strip()
    missing = [n for n, v in (("LIVEKIT_API_KEY", key), ("LIVEKIT_API_SECRET", secret)) if not v]
    if missing:
        raise ConfigError(
            f"{' and '.join(missing)} must be set: the voice-agent worker has no "
            "default LiveKit credentials. Use the same key/secret pair as the "
            "LiveKit server's LIVEKIT_KEYS (deploy/.env)."
        )
    if key in _KNOWN_PLACEHOLDER_KEYS or secret in _KNOWN_PLACEHOLDER_SECRETS:
        raise ConfigError(
            "LIVEKIT_API_KEY/LIVEKIT_API_SECRET are set to the old published "
            "placeholder values (devkey/devsecret-...); generate a real pair."
        )


# CLI subcommands that never connect to LiveKit, so need no credentials
# (download-files only fetches model weights).
_NO_CREDENTIALS_COMMANDS = frozenset({"download-files", "--help", "-h"})


def check_startup_config(argv: list[str]) -> None:
    """Startup gate run by main.py's __main__ before cli.run_app.

    Lives here rather than in main.py so it is testable without importing the
    livekit plugins (importing app.main pulls in native livekit/silero code).
    """
    if len(argv) > 1 and argv[1] in _NO_CREDENTIALS_COMMANDS:
        return
    require_livekit_credentials()


# Empty string = automatic dispatch: the worker is offered every new room's
# job, exactly matching today's flow where VoiceCallController#issueToken
# mints a token for a plain "voice-<agentId>-<shortId>" room with no explicit
# agent-dispatch request attached. Only set LIVEKIT_AGENT_NAME if a future
# change moves to explicit dispatch (see LiveKit's AgentDispatch API) -
# leaving it set here while the token side stays on implicit dispatch would
# silently stop this worker from ever being offered a job.
AGENT_NAME = _env("LIVEKIT_AGENT_NAME", "")

# --- sidecar (Whisper STT) ---------------------------------------------------
# Same var name `SIDECAR_BASE_URL` core already uses to reach the sidecar
# (see deploy/docker-compose.yml's `core` service env block) - kept
# byte-identical rather than inventing a `SIDECAR_URL` so one env value in
# deploy/.env covers both callers.
SIDECAR_BASE_URL = _env("SIDECAR_BASE_URL", "http://sidecar:8001")

# --- core (bridged "LLM" leg) ------------------------------------------------
# Mirrors NEXT_PUBLIC_API_BASE_URL's PURPOSE (where to find core's API), but
# NOT its default: that var is browser-facing (defaults to
# http://localhost:8080, see deploy/docker-compose.yml's WEB_PUBLIC_API_BASE_URL)
# and per RULE 0 must never point a browser at localhost on a real
# deployment. This worker is server-to-server only - it never runs in a
# browser - so the correct default is the in-network compose DNS name core
# itself listens on, same relationship SidecarClient (core -> sidecar) and
# this file's SIDECAR_BASE_URL above already have.
CORE_API_BASE_URL = _env("CORE_API_BASE_URL", "http://core:8080")

# --- ElevenLabs TTS ----------------------------------------------------------
# KNOWN GAP, flagged rather than silently worked around (see README.md's
# "Known gaps" section): every other ElevenLabs call path in this platform
# (SpeakToolCallback, VoiceCallController#turn/#speak) resolves a
# PER-TENANT credential + the tenant's own cloned voice via
# ElevenLabsVoiceService/ElevenLabsClient - both Java-only, DB-backed, and
# out of this worker's reach (no core endpoint exists to hand a Python
# worker a tenant's decrypted key, and adding one would mean touching
# core/, out of this pass's scope per the build brief). Until that exists,
# every real-time call uses ONE platform-level key/voice, regardless of
# tenant. ELEVEN_API_KEY is also read directly by the livekit-plugins-
# elevenlabs package itself if this is left unset - ELEVENLABS_API_KEY here
# is just this repo's own naming convention layered on top.
ELEVENLABS_API_KEY = _env("ELEVENLABS_API_KEY") or _env("ELEVEN_API_KEY")
# ElevenLabs' public "Rachel" premade voice - byte-identical default to
# VoiceCallController.DEFAULT_ELEVENLABS_VOICE_ID, so a fresh deployment
# with no tenant-specific setup still gets a working voice rather than a
# hard TTS failure.
ELEVENLABS_VOICE_ID = _env("ELEVENLABS_VOICE_ID", "21m00Tcm4TlvDq8ikWAM")
ELEVENLABS_MODEL = _env("ELEVENLABS_MODEL", "eleven_turbo_v2_5")

# --- Sarvam (TTS, tts_sarvam.py) ---------------------------------------------
# Same "one platform-level key, not per-tenant" limitation as ElevenLabs
# above (see that comment) - same reason (no core endpoint hands a Python
# worker a tenant's decrypted credential yet).
SARVAM_API_KEY = _env("SARVAM_API_KEY")
SARVAM_TARGET_LANGUAGE_CODE = _env("SARVAM_TARGET_LANGUAGE_CODE", "en-IN")
# bulbul:v3, NOT v2. Sarvam DEPRECATED v2 server-side and now rejects it
# outright: POST /text-to-speech returns 400 "Model 'bulbul:v2' has been
# deprecated. Please use 'bulbul:v3' instead." Confirmed live 2026-09-17,
# not inferred. core/sarvam's SarvamTextToSpeechClient.DEFAULT_MODEL,
# core/providers' TtsModelProvider and V196__agent_tts_provider.sql had all
# moved to v3 already - the Python services and deploy/docker-compose.yml
# were missed, which left every Sarvam TTS call from them failing.
# NOTE: speaker names are per-model (anushka is v2, shubh is v3), so an
# explicit SARVAM_SPEAKER carried over from v2 will also 400. Leaving it
# unset lets Sarvam apply its own per-model default.
SARVAM_MODEL = _env("SARVAM_MODEL", "bulbul:v3")
SARVAM_SPEAKER = _env("SARVAM_SPEAKER", "")

# --- TTS provider selection ---------------------------------------------------
# "not bounded to one provider" - mirrors SpeakToolCallback's own "ElevenLabs
# first, Sarvam otherwise" preference order (core/agents/.../audio/
# SpeakToolCallback.java's javadoc), applied here to the real-time pipeline
# instead of hardcoding a single TTS backend into main.py. "auto" picks
# whichever key is actually set (ElevenLabs preferred if both are); an
# explicit "elevenlabs"/"sarvam" forces one regardless of what else is
# configured - set this when a configured provider is unusable for a reason
# that isn't "no key set" (e.g. ElevenLabs free-tier accounts get a real 402
# "Free users cannot use library voices via the API" on every synthesis
# call despite a valid key - observed live, not hypothetical - which "auto"
# alone can't detect since key-presence isn't the same as usability).
TTS_PROVIDER = _env("VOICE_AGENT_TTS_PROVIDER", "auto").strip().lower()

# --- perception ("sight") mode -----------------------------------------------
# See perception.py's module docstring. These three envs are the worker-side
# knobs; frame QUOTA (per-user-per-day) is core's, not this worker's -
# app.vision.* in core's application.yml.
#
# Hard cap on concurrently-active sight rooms per worker container - every
# active perception room spends an LLM vision turn every few seconds for its
# whole duration, a strictly heavier cost profile than an audio-only call.
# Enforced cross-process (jobs run in child processes) - see perception.py's
# slot-file comment. Rooms beyond the cap run audio-only with a spoken
# refusal, they are never dropped.
PERCEPTION_MAX_ROOMS = _env_int("PERCEPTION_MAX_ROOMS", 4)
# Hard floor between two analyzed frames, seconds - holds even when the
# scene-change gate would fire faster (camera shake, walking). The research
# doc's server-enforced "max 1 frame per 2s" ceiling.
PERCEPTION_MIN_FRAME_INTERVAL_S = _env_float("PERCEPTION_MIN_FRAME_INTERVAL_S", 2.0)
# Scene-change gate threshold: mean absolute grayscale difference vs. the
# last-ANALYZED frame's 64x36 thumbnail, normalized to [0,1] - 0.10 = the
# scene changed by ~10% of full-scale brightness on average. Raise to make
# the agent less chatty about small movements, lower to catch subtler changes.
PERCEPTION_SCENE_DIFF_THRESHOLD = _env_float("PERCEPTION_SCENE_DIFF_THRESHOLD", 0.10)

# --- VAD / turn-detection latency tuning (2026-08-05) ------------------------
# main.py's prewarm() previously called silero.VAD.load() with every
# parameter left at the plugin's own default - in particular
# min_silence_duration=0.55s, i.e. the agent waits 550ms of silence after the
# caller stops talking before it decides they're done and starts replying.
# That delay is added to EVERY single turn's perceived latency, on top of
# STT/LLM/TTS time - live voice-agent latency research (LiveKit's own writeup
# + community guides) consistently names turn-detection/endpointing tuning as
# one of the highest-leverage, zero-infrastructure latency wins available,
# ahead of codec-level tweaks. Trimmed to 350ms here: still comfortably above
# normal mid-sentence pause lengths (avoids cutting someone off for taking a
# breath), but shaves ~200ms off every reply's start compared to the
# untuned default - a real, consumer-visible difference on every single turn,
# not a one-time fix. Override via env if a deployment finds this too
# aggressive (e.g. slower speakers or noisier environments).
VAD_MIN_SILENCE_DURATION_S = _env_float("VOICE_AGENT_VAD_MIN_SILENCE_DURATION_S", 0.35)

# --- misc --------------------------------------------------------------------
LOG_LEVEL = _env("VOICE_AGENT_LOG_LEVEL", "INFO")

# Standalone/local testing only (see main.py's _extract_auth_token) - lets a
# developer run this worker against a real room without the browser-side
# metadata wiring in place yet. NEVER set in a real deployment: it would
# make every call authenticate as whichever single account minted this
# token, ignoring who actually joined the room.
DEV_STATIC_BEARER_TOKEN = _env("VOICE_AGENT_DEV_STATIC_BEARER_TOKEN", "")
