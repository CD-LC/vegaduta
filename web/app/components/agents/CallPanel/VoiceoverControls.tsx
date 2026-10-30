"use client";

// The call panel's "voice over" controls - the playground-parity audio
// controls the push-to-talk/live-call surfaces were missing:
//
//   1. Reply voice (this device) - which voice speaks replies played here:
//      Auto (the chain's own order), a Kokoro voice (only listed when the
//      on-device model is installed - picking it never downloads anything),
//      or a browser voice. Persisted via lib/voice/voiceover.ts, honoured by
//      every playback leg (speakFragment, speakReply, replay).
//   2. Speaker toggle - mutes spoken replies without muting the live call's
//      remote track (the in-call speaker button's separate job). Manual
//      replay of a transcript line still works while muted, matching the
//      playground's auto-speak toggle semantics.
//   3. Call voice - the caller's per-call pick for the LIVE call's own voice
//      (the tenant's ElevenLabs voices, minted onto the room token's metadata
//      as ttsVoice/ttsVoiceProvider - the voice-agent worker applies it).
//      Only rendered when the tenant has cloned voices to pick from.
//   4. Agent voice (provider) - the agent's configured TTS backend
//      (agent.ttsProvider), editable in place through the same selector the
//      workbench's Transcription section uses.
//
// Everything here degrades quietly: no ElevenLabs voices -> no call-voice
// row; no speechSynthesis -> no browser voices in the list; the tenant
// settings read failing -> the provider editor stays locked (fail closed,
// same posture as the workbench's isFreePlan).

import { useEffect, useRef, useState } from "react";
import { Loader2, Volume2, VolumeX } from "lucide-react";
import { Button } from "../../ui/button";
import Select from "../../Select";
import { api } from "../../../lib/api";
import { errorMessage } from "../../../lib/errors";
import { isTtsModelCached } from "../../../lib/edge/audio/kokoro";
import {
  getVoiceoverPref,
  setVoiceoverPref,
  KOKORO_VOICEOVER_OPTIONS,
  listWebSpeechVoices,
  parseVoiceoverOptionValue,
  voiceoverOptionValue,
  type VoiceoverChoice,
} from "../../../lib/voice/voiceover";
import { defaultModelFor, useModalityCatalogData } from "../useModalityCatalog";
import TextToSpeechProviderSelector from "../TextToSpeechProviderSelector";
import type { CallPanelAgent } from "../call/callContext";
import { useTranslations } from "../../../lib/i18n";

export interface CallVoicePick {
  voiceId: string;
  provider: string;
  label?: string;
}

interface VoiceoverControlsProps {
  agent: CallPanelAgent;
  repliesMuted: boolean;
  onToggleRepliesMuted: () => void;
  callVoice: CallVoicePick | null;
  onCallVoiceChange: (pick: CallVoicePick | null) => void;
}

export default function VoiceoverControls({
  agent,
  repliesMuted,
  onToggleRepliesMuted,
  callVoice,
  onCallVoiceChange,
}: VoiceoverControlsProps) {
    const tHC = useTranslations("hcComponentsAgentsCallPanelVoiceoverControls");
  // Hydrate the persisted pick after mount (localStorage is not SSR-safe for
  // every consumer of this panel; the effect also re-reads so a pick made on
  // another surface is reflected on next mount).
  const [voiceover, setVoiceover] = useState<VoiceoverChoice>({ kind: "auto" });
  const [browserVoices, setBrowserVoices] = useState<SpeechSynthesisVoice[]>([]);
  const [kokoroInstalled, setKokoroInstalled] = useState(false);
  useEffect(() => {
    setVoiceover(getVoiceoverPref());
    setKokoroInstalled(isTtsModelCached());
    const refresh = () => setBrowserVoices(listWebSpeechVoices());
    refresh();
    try {
      window.speechSynthesis?.addEventListener?.("voiceschanged", refresh);
      return () => window.speechSynthesis?.removeEventListener?.("voiceschanged", refresh);
    } catch {
      return undefined;
    }
  }, []);

  function pickVoiceover(value: string) {
    const choice = parseVoiceoverOptionValue(value);
    setVoiceover(choice);
    setVoiceoverPref(choice);
  }

  // Flattened options for the shared Select (it has no optgroups - the group
  // name is folded into the label). A persisted pick whose leg is currently
  // unavailable (Kokoro uninstalled, a removed browser voice) stays listed so
  // it doesn't silently vanish; the runners already fall back gracefully.
  const voiceoverOptions = [
    { value: "auto", label: "Auto" },
    ...(kokoroInstalled
      ? KOKORO_VOICEOVER_OPTIONS.map((v) => ({
          value: `kokoro:${v.voiceId}`,
          label: `On-device · ${v.label}`,
        }))
      : []),
    ...browserVoices.map((v) => ({
      value: `web-speech:${v.voiceURI}`,
      label: `Browser · ${v.name} (${v.lang})`,
    })),
  ];
  const voiceoverValue = voiceoverOptionValue(voiceover);
  if (!voiceoverOptions.some((o) => o.value === voiceoverValue)) {
    voiceoverOptions.push({
      value: voiceoverValue,
      label:
        voiceover.kind === "kokoro"
          ? `On-device · ${voiceover.voiceId}`
          : voiceover.kind === "web-speech"
            ? `Browser · ${voiceover.label ?? voiceover.voiceURI}`
            : "Auto",
    });
  }

  // ---- Live-call voice (the tenant's ElevenLabs voices) ------------------
  const [callVoices, setCallVoices] = useState<{ voiceId: string; name: string }[] | null>(null);
  useEffect(() => {
    let cancelled = false;
    api
      .listElevenLabsVoices()
      .then((list) => {
        if (cancelled) return;
        const voices = (Array.isArray(list) ? list : [])
          .map((v: any) => ({ voiceId: v?.elevenLabsVoiceId, name: v?.name }))
          .filter((v) => typeof v.voiceId === "string" && v.voiceId);
        setCallVoices(voices);
      })
      .catch(() => {
        // Quiet: the row simply doesn't render - a tenant with no cloned
        // voices has nothing to pick anyway.
        if (!cancelled) setCallVoices([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // ---- Agent TTS provider -----------------------------------------------
  // Same editor contract the workbench's Transcription section uses; the
  // plan gate is a one-shot quiet read (fail closed while unresolved).
  const [isFreePlan, setIsFreePlan] = useState(true);
  const [ttsEditing, setTtsEditing] = useState(false);
  const [ttsBusy, setTtsBusy] = useState(false);
  const [ttsError, setTtsError] = useState<string | null>(null);
  const [ttsDraft, setTtsDraft] = useState<{ provider: string | null; model: string | null }>({
    provider: null,
    model: null,
  });
  // The saved value this panel believes in: the agent prop's own value until
  // a successful save here overrides the display (the parent may not refetch).
  const [ttsSaved, setTtsSaved] = useState<{ provider: string | null; model: string | null } | null>(null);
  const agentIdRef = useRef(agent.id);
  useEffect(() => {
    if (agentIdRef.current === agent.id) return;
    agentIdRef.current = agent.id;
    setTtsSaved(null);
    setTtsEditing(false);
    setTtsError(null);
  }, [agent.id]);
  const shownTts = ttsSaved ?? { provider: agent.ttsProvider ?? null, model: agent.ttsModel ?? null };
  const modalityCatalog = useModalityCatalogData();
  useEffect(() => {
    let cancelled = false;
    api
      .getTenantSettings()
      .then((s) => {
        if (!cancelled) setIsFreePlan(s?.plan === "FREE");
      })
      .catch(() => {
        // Fail closed - same posture as the workbench's tenantSettingsError.
        if (!cancelled) setIsFreePlan(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function saveTtsProvider() {
    if (ttsBusy) return;
    setTtsBusy(true);
    setTtsError(null);
    try {
      const provider = ttsDraft.provider;
      const model = ttsDraft.model || defaultModelFor(modalityCatalog?.textToSpeech, provider) || null;
      await api.updateAgentTtsModel(agent.id, provider, model);
      setTtsSaved({ provider, model });
      setTtsEditing(false);
    } catch (err) {
      setTtsError(errorMessage(err, "Could not update the voice provider."));
    } finally {
      setTtsBusy(false);
    }
  }

  return (
    <div className="space-y-1.5 rounded-lg border border-border/60 bg-secondary/20 p-2" data-voiceover-controls>
      <div className="flex items-center gap-2">
        <span className="shrink-0 text-xs text-muted-foreground" id="voiceover-pick-label">
          {tHC("s3")}
        </span>
        <div className="min-w-0 flex-1">
          <Select
            aria-labelledby="voiceover-pick-label"
            value={voiceoverValue}
            options={voiceoverOptions}
            searchable={voiceoverOptions.length > 12}
            onChange={(e) => pickVoiceover(e.target.value)}
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

      {callVoices && callVoices.length > 0 && (
        <div className="flex items-center gap-2">
          <span className="shrink-0 text-xs text-muted-foreground" id="call-voice-pick-label">
            {tHC("s2")}
          </span>
          <div className="min-w-0 flex-1">
            <Select
              aria-labelledby="call-voice-pick-label"
              value={callVoice?.provider === "elevenlabs" ? callVoice.voiceId : ""}
              options={[
                { value: "", label: "Agent default" },
                ...callVoices.map((v) => ({ value: v.voiceId, label: v.name })),
              ]}
              onChange={(e) => {
                const v = callVoices.find((c) => c.voiceId === e.target.value);
                onCallVoiceChange(v ? { voiceId: v.voiceId, provider: "elevenlabs", label: v.name } : null);
              }}
            />
          </div>
        </div>
      )}

      <div className="space-y-1.5">
        {!ttsEditing ? (
          <div className="flex items-center justify-between gap-2">
            <span className="text-xs text-muted-foreground">
              {tHC("s1")}{" "}
              {shownTts.provider ? `${shownTts.provider}${shownTts.model ? ` · ${shownTts.model}` : ""}` : "Platform default"}
            </span>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={isFreePlan}
              title={isFreePlan ? "Voice provider selection is a Premium feature" : "Change the agent's voice provider"}
              onClick={() => {
                setTtsDraft({ provider: shownTts.provider, model: shownTts.model });
                setTtsError(null);
                setTtsEditing(true);
              }}
            >
              Change
            </Button>
          </div>
        ) : (
          <div className="space-y-1.5">
            <TextToSpeechProviderSelector
              value={ttsDraft.provider}
              onChange={(provider, model) => setTtsDraft({ provider, model })}
              isFreePlan={isFreePlan}
              disabled={ttsBusy}
              showLabel={false}
              showDescription={false}
            />
            <div className="flex items-center gap-1.5">
              <Button type="button" size="sm" disabled={ttsBusy} onClick={() => void saveTtsProvider()}>
                {ttsBusy ? <Loader2 size={12} className="animate-spin" /> : null}
                Save
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                disabled={ttsBusy}
                onClick={() => {
                  setTtsEditing(false);
                  setTtsError(null);
                }}
              >
                Cancel
              </Button>
            </div>
            {ttsError && <p className="text-xs text-destructive">{ttsError}</p>}
          </div>
        )}
      </div>
    </div>
  );
}
