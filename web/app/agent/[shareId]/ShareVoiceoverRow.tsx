"use client";

// The share page's voice-over row: the visitor's reply-voice pick plus the
// spoken-replies speaker toggle - the playground-parity audio controls an
// anonymous caller can legitimately touch (the console panel's ElevenLabs
// call-voice and agent-provider rows need credentials, so they don't exist
// here). Owns its own picker state; the parent owns the speaker toggle (the
// ref is read by the audio queue).

import { useEffect, useState } from "react";
import { Volume2, VolumeX } from "lucide-react";
import { Button } from "../../components/ui/button";
import Select from "../../components/Select";
import { edgeEnabled } from "../../lib/edge/capabilities";
import { isTtsModelCached } from "../../lib/edge/audio/kokoro";
import {
  getVoiceoverPref,
  setVoiceoverPref,
  KOKORO_VOICEOVER_OPTIONS,
  listWebSpeechVoices,
  parseVoiceoverOptionValue,
  voiceoverOptionValue,
  type VoiceoverChoice,
} from "../../lib/voice/voiceover";
import { useTranslations } from "../../lib/i18n";

export default function ShareVoiceoverRow({
  repliesMuted,
  onToggleRepliesMuted,
}: {
  repliesMuted: boolean;
  onToggleRepliesMuted: () => void;
}) {
  const tHC = useTranslations("hcAgentShareIdShareVoiceoverRow");
  // Hydrate the persisted pick + voice lists after mount (localStorage and
  // speechSynthesis.getVoices() are not safe in the pre-rendered HTML).
  const [voiceover, setVoiceover] = useState<VoiceoverChoice>({ kind: "auto" });
  const [browserVoices, setBrowserVoices] = useState<SpeechSynthesisVoice[]>([]);
  const [kokoroInstalled, setKokoroInstalled] = useState(false);
  useEffect(() => {
    setVoiceover(getVoiceoverPref());
    try {
      setKokoroInstalled(edgeEnabled() && isTtsModelCached());
    } catch {
      // Stays false.
    }
    const refresh = () => setBrowserVoices(listWebSpeechVoices());
    refresh();
    try {
      window.speechSynthesis?.addEventListener?.("voiceschanged", refresh);
      return () => window.speechSynthesis?.removeEventListener?.("voiceschanged", refresh);
    } catch {
      return undefined;
    }
  }, []);

  const voiceoverValue = voiceoverOptionValue(voiceover);
  const options = [
    { value: "auto", label: "Auto voice" },
    ...(kokoroInstalled
      ? KOKORO_VOICEOVER_OPTIONS.map((v) => ({ value: `kokoro:${v.voiceId}`, label: `On-device · ${v.label}` }))
      : []),
    ...browserVoices.map((v) => ({ value: `web-speech:${v.voiceURI}`, label: `${v.name} (${v.lang})` })),
  ];
  // A persisted pick whose leg is currently unavailable stays listed so it
  // doesn't silently vanish; the runners already fall back gracefully.
  if (!options.some((o) => o.value === voiceoverValue)) {
    options.push({
      value: voiceoverValue,
      label:
        voiceover.kind === "kokoro"
          ? `On-device · ${voiceover.voiceId}`
          : voiceover.kind === "web-speech"
            ? (voiceover.label ?? voiceover.voiceURI)
            : "Auto voice",
    });
  }

  return (
    <div className="flex items-center gap-2">
      <div className="min-w-0 flex-1">
        <Select
          aria-label={tHC("s1")}
          value={voiceoverValue}
          options={options}
          searchable={options.length > 12}
          onChange={(e) => {
            const choice = parseVoiceoverOptionValue(e.target.value);
            setVoiceover(choice);
            setVoiceoverPref(choice);
          }}
        />
      </div>
      <Button
        type="button"
        variant="outline"
        size="icon"
        onClick={onToggleRepliesMuted}
        aria-label={repliesMuted ? "Speak replies aloud" : "Mute spoken replies"}
        title={repliesMuted ? "Speak replies aloud" : "Mute spoken replies"}
      >
        {repliesMuted ? <VolumeX size={14} /> : <Volume2 size={14} />}
      </Button>
    </div>
  );
}
