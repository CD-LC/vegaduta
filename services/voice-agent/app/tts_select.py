"""TTS provider selection for the voice pipeline - split out of main.py to
keep the entrypoint under the file-size ratchet.

Resolve order: explicit VOICE_AGENT_TTS_PROVIDER, else "auto" prefers
ElevenLabs (matches SpeakToolCallback's platform-wide preference order) and
falls back to Sarvam; Piper is a local, keyless pick chosen explicitly, never
part of "auto". Callers may pass a per-call voice+provider pair from
participant metadata (_extract_tts_voice) - the provider tag guards against
feeding an ElevenLabs voice id to a different backend.
"""

from __future__ import annotations

import json
import logging

from livekit import rtc
from livekit.plugins import elevenlabs

from . import config
from .tts_piper import PiperTTS
from .tts_sarvam import SarvamTTS

logger = logging.getLogger("voice-agent.tts_select")


def extract_tts_voice(participant: rtc.RemoteParticipant) -> tuple[str | None, str | None]:
    """Optional per-call reply voice, carried on the SAME participant metadata
    JSON `_extract_auth_token`/`_extract_share_context` already read
    (`{"ttsVoice": "<id>", "ttsVoiceProvider": "elevenlabs"|"sarvam"}` -
    VoiceCallController#issueToken adds it when the caller picked one on the
    call panel; see VoiceTokenRequest). Anonymous share callers have no way to
    list voices, so this only ever arrives on authenticated calls today, but
    the field is honoured on share metadata identically for forward-compat.

    Returns (voice, provider) - both None when absent/unparseable. `provider`
    tags which backend the id belongs to: the caller cannot know which TTS
    plugin this worker will resolve (auto order), so a mismatched override
    (e.g. an ElevenLabs voice id while this worker is on Sarvam) is ignored
    rather than misapplied - see build_tts_plugin.
    """
    metadata = (participant.metadata or "").strip()
    if not metadata.startswith("{"):
        return None, None

    try:
        parsed = json.loads(metadata)
    except (ValueError, AttributeError):
        return None, None
    if not isinstance(parsed, dict):
        return None, None
    voice = parsed.get("ttsVoice")
    provider = parsed.get("ttsVoiceProvider")
    # Voice ids are opaque provider strings; cap them so a junk field can't
    # bloat metadata handling, and keep them printable for logs.
    voice = voice.strip() if isinstance(voice, str) else None
    provider = provider.strip() if isinstance(provider, str) else None
    if voice and len(voice) > 120:
        voice = None
    if provider and len(provider) > 40:
        provider = None
    return voice or None, provider or None


def tts_voiced() -> bool:
    """Whether build_tts_plugin below can produce a WORKING plugin on this
    deployment - same provider resolution, plus the key check. Reported to
    the browser on agent transcript lines (the `voiced` field): False means
    "no server-side voice exists", and the caller speaks replies through
    its own on-device/browser voice chain instead - a keyless install still
    answers in voice.
    """
    provider = config.TTS_PROVIDER
    if provider not in ("auto", "elevenlabs", "sarvam", "piper"):
        provider = "auto"
    if provider == "piper":
        # No credential to check - the model file existing is its own check,
        # and a missing one fails loud at synthesize() anyway.
        return True
    use_elevenlabs = provider == "elevenlabs" or (
        provider == "auto" and bool(config.ELEVENLABS_API_KEY)
    )
    if use_elevenlabs:
        return bool(config.ELEVENLABS_API_KEY)
    return bool(config.SARVAM_API_KEY)


def build_tts_plugin(voice_override: str | None = None, provider_override: str | None = None):
    """TTS provider selection - see config.TTS_PROVIDER's comment for why
    this exists instead of hardcoding one backend. "auto" prefers ElevenLabs
    (matches SpeakToolCallback's platform-wide preference order) but falls
    back to Sarvam if no ElevenLabs key is set at all; it can NOT detect an
    ElevenLabs key that's present but unusable (e.g. a free-tier 402) - use
    an explicit VOICE_AGENT_TTS_PROVIDER=sarvam for that case.

    `voice_override`/`provider_override` come from extract_tts_voice: the
    caller's own per-call voice pick. Applied only when provider_override is
    absent or names the provider this worker actually resolved to - an
    ElevenLabs voice id fed to Sarvam's `speaker` field would just fail
    server-side, which is why the override is provider-tagged.

    Returns None when the resolved provider has no credential at all: a
    session with a guaranteed-failing TTS node is strictly worse than one
    with tts=None (text-only turns still stream, the caller's browser speaks
    them locally - see tts_voiced's comment).
    """
    provider = config.TTS_PROVIDER
    if provider not in ("auto", "elevenlabs", "sarvam", "piper"):
        logger.warning("unknown VOICE_AGENT_TTS_PROVIDER=%r, falling back to auto", provider)
        provider = "auto"

    if provider == "piper":
        voice = config.PIPER_VOICE
        if voice_override and provider_override in (None, "piper"):
            logger.info("caller picked Piper voice %s for this call", voice_override)
            voice = voice_override
        logger.info("TTS provider: piper (voice=%s)", voice)
        return PiperTTS(voice=voice, speaker_id=config.PIPER_SPEAKER_ID)

    use_elevenlabs = provider == "elevenlabs" or (
        provider == "auto" and bool(config.ELEVENLABS_API_KEY)
    )
    if use_elevenlabs:
        if not config.ELEVENLABS_API_KEY:
            logger.warning(
                "TTS provider 'elevenlabs' has no ELEVENLABS_API_KEY - "
                "no plugin; callers speak replies in the browser instead"
            )
            return None
        voice_id = config.ELEVENLABS_VOICE_ID
        if voice_override and provider_override in (None, "elevenlabs"):
            logger.info("caller picked ElevenLabs voice %s for this call", voice_override)
            voice_id = voice_override
        logger.info("TTS provider: elevenlabs (voice=%s)", voice_id)
        return elevenlabs.TTS(
            voice_id=voice_id,
            model=config.ELEVENLABS_MODEL,
            api_key=config.ELEVENLABS_API_KEY or None,
        )

    if not config.SARVAM_API_KEY:
        logger.warning(
            "no SARVAM_API_KEY (and TTS_PROVIDER did not resolve to elevenlabs) - "
            "no plugin; callers speak replies in the browser instead"
        )
        return None
    speaker = config.SARVAM_SPEAKER or None
    if voice_override and provider_override in (None, "sarvam"):
        logger.info("caller picked Sarvam speaker %s for this call", voice_override)
        speaker = voice_override
    logger.info("TTS provider: sarvam (speaker=%s)", speaker or "<model default>")
    return SarvamTTS(
        api_key=config.SARVAM_API_KEY,
        target_language_code=config.SARVAM_TARGET_LANGUAGE_CODE,
        model=config.SARVAM_MODEL,
        speaker=speaker,
    )
