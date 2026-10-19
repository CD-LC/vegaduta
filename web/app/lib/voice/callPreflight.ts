// Call preflight: what is missing for a voice call to work on THIS device
// right now, said in one plain line each, with at most one action each.
// PURE (tested in callPreflight.test.ts); the browser probing lives in
// callEnvironment.ts and the rendering in components/agents/call/.
//
// WHY (founder, 2026-09-19): "if call is not working because of missing
// [pieces], why is it not suggesting the user to download the other on-device
// things?" Production that day: the speech-to-text sidecar had been scaling
// to zero, so with no Sarvam key (consumers have none) a held-to-talk turn
// failed with the server's admin-facing "No speech-to-text provider is
// configured for this agent. Add a Sarvam credential..." - and the panel
// offered nothing, although in-browser Whisper (lib/edge/audio/whisper.ts)
// was sitting one download away. Same story for voice: no tenant voice key,
// Kokoro one download away, no hint.
//
// NO-FICTION rules for every string in this file (PlatformLedger.tsx's
// do-not-claim list): the in-browser Whisper build is whisper-tiny.EN and
// kokoro-js speaks English - both lines say "English", never a language
// count. Sizes come from the modules' own constants. Nothing here promises
// when a server service will be back.
//
// Two more, added after review (2026-09-19):
//  - The health probe only sees the speech-to-text SIDECAR. The server's real
//    chain is agent provider -> sidecar Whisper -> the tenant's Sarvam key,
//    and this client cannot see whether a tenant has that key. So a probe
//    that says "down" is a HINT ("may be waking up"), never "is not
//    responding", never blocking, and never a reason to reroute a turn. Only
//    a turn that actually failed at transcription is evidence.
//  - In-browser Whisper is whisper-tiny.EN. Fed Hindi or Kannada speech it
//    produces confident English nonsense, which would be sent to the agent as
//    the person's message. It is therefore only used, or offered, when every
//    language signal we have says English (likelyEnglishSpeaker).

export type MicState =
  | "unknown" // not asked yet and the Permissions API can't tell us
  | "granted"
  | "prompt"
  | "denied"
  | "no-device"
  | "busy"
  | "insecure" // not a secure context - getUserMedia is unavailable
  | "unsupported"; // no navigator.mediaDevices.getUserMedia at all

export type DownloadStatus = "idle" | "downloading" | "failed";

export interface DownloadState {
  status: DownloadStatus;
  /** 0-100 while downloading, when the library reports totals. */
  percent?: number | null;
  /** Display-safe failure reason (ensure*Model rejects with one). */
  error?: string | null;
}

export type PreflightActionKind = "download-stt" | "download-tts" | "recheck-server" | "test-mic";

export interface PreflightIssue {
  id:
    | "mic-unsupported"
    | "mic-insecure"
    | "mic-denied"
    | "mic-no-device"
    | "mic-busy"
    | "recorder-unsupported"
    | "stt-unavailable"
    | "stt-maybe-waking"
    | "stt-on-device"
    | "voice-missing"
    | "voice-downloading";
  /** "blocking": hold-to-talk cannot work until this is dealt with.
   *  "degraded": it works, but worse than it could.
   *  "info": something already switched over; nothing to do. */
  severity: "blocking" | "degraded" | "info";
  line: string;
  action?: { kind: PreflightActionKind; label: string };
  /** Present while this issue's own download is running. */
  progressPercent?: number | null;
}

export interface CallPreflightInput {
  mic: MicState;
  mediaRecorderSupported: boolean;

  /** useSystemHealth().checked - before the first probe we know nothing and
   * must say nothing (sidecarAvailable is optimistic by design). */
  serverSttChecked: boolean;
  /** useSystemHealth().sidecarAvailable */
  serverSttAvailable: boolean;
  /** A turn in this call actually failed at the server's transcription step. */
  serverSttFailedThisCall: boolean;
  /** The agent has its own speech-to-text provider selected (a paid-plan
   * setting). The sidecar being down says nothing about THAT provider, so
   * only a real failed turn counts as evidence then. */
  agentHasOwnSttProvider: boolean;

  /** likelyEnglishSpeaker(...) - in-browser Whisper is English-only, so it is
   * neither used nor offered to anyone else. */
  speechLikelyEnglish: boolean;

  /** edgeEnabled() */
  edgeOn: boolean;
  localSttSupported: boolean;
  localSttInstalled: boolean;
  localTtsSupported: boolean;
  localTtsInstalled: boolean;

  /** A reply in this call came back with no server-synthesized audio AND was
   * in a script the on-device voice can speak (the caller checks
   * isLatinScriptText) - the English voice is never offered for a Hindi,
   * Kannada, Arabic or CJK reply. */
  serverVoiceMissing: boolean;
  webSpeechPresent: boolean;

  sttDownload: DownloadState;
  ttsDownload: DownloadState;

  sttSizeBytes: number;
  ttsSizeBytes: number;
}

/** "about 45 MB" - decimal megabytes, rounded to the nearest 5 so the line
 * doesn't pretend to a precision the size constants don't have. */
export function formatApproxMegabytes(bytes: number): string {
  const mb = Math.max(0, bytes) / 1_000_000;
  const rounded = mb < 10 ? Math.max(1, Math.round(mb)) : Math.round(mb / 5) * 5;
  return `about ${rounded} MB`;
}

/** Map a getUserMedia rejection to a MicState. Anything unrecognised stays
 * "unknown" - we never tell someone their microphone is blocked on a guess. */
export function micStateFromError(errorName: string | null | undefined): MicState {
  switch (errorName) {
    case "NotAllowedError":
    case "PermissionDeniedError":
      return "denied";
    case "NotFoundError":
    case "DevicesNotFoundError":
    case "OverconstrainedError":
      return "no-device";
    case "NotReadableError":
    case "TrackStartError":
    case "AbortError":
      return "busy";
    case "SecurityError":
      return "insecure";
    default:
      return "unknown";
  }
}

/**
 * Did this voice turn fail at the SERVER'S TRANSCRIPTION step (as opposed to
 * sign-in, plan limits, or the model)? Only such a failure may be retried
 * through in-browser Whisper + the text chat endpoint.
 *
 * Deliberately narrow. 401/403/402/429 are the platform saying "no" (session,
 * plan, voice quota) - re-sending the same turn as text would be a quota
 * bypass, not a recovery. "No speech was detected" is a true statement about
 * the recording, not an outage. After the transcript event the failure is the
 * model's, and a transcript already exists.
 */
export function isServerSttFailure(failure: {
  message?: string | null;
  status?: number | null;
  transcriptReceived: boolean;
}): boolean {
  if (failure.transcriptReceived) return false;
  const status = failure.status ?? null;
  if (status === 401 || status === 402 || status === 403 || status === 429) return false;
  const message = (failure.message || "").toLowerCase();
  if (message.includes("no speech was detected")) return false;
  if (status === 502 || status === 503 || status === 504) return true;
  return /speech-to-text|transcri/.test(message);
}

/**
 * Does everything we know say this person speaks ENGLISH to the agent?
 * Signals are BCP-47-ish tags (navigator.language, the UI locale, an agent
 * language if it has one); empty ones are ignored. Every signal present must
 * be English, and at least one must be present - "unknown" is not English.
 * A heuristic: someone with an all-English setup who speaks Hindi is still
 * misjudged, which is why the on-device lines keep saying "English only".
 */
export function likelyEnglishSpeaker(signals: (string | null | undefined)[]): boolean {
  const present = signals.map((s) => (s || "").trim().toLowerCase()).filter(Boolean);
  if (present.length === 0) return false;
  return present.every((tag) => tag === "en" || tag.startsWith("en-") || tag.startsWith("en_"));
}

/** Should this turn skip the server's speech-to-text and be transcribed in
 * the browser BEFORE any upload? Only on EVIDENCE: a turn in this call really
 * failed at the server's transcription step (and that mark is still live).
 * The health probe alone never reroutes a turn - it cannot see a tenant's
 * Sarvam key, which keeps server transcription working while the sidecar
 * sleeps. Whisper must already be installed (this never downloads anything)
 * and the person must look like an English speaker (whisper-tiny.en). */
export function shouldTranscribeOnDeviceFirst(input: {
  edgeOn: boolean;
  localSttInstalled: boolean;
  serverSttFailedThisCall: boolean;
  speechLikelyEnglish: boolean;
}): boolean {
  if (!input.edgeOn || !input.localSttInstalled) return false;
  if (!input.speechLikelyEnglish) return false;
  return input.serverSttFailedThisCall;
}

/** May a turn whose SERVER transcription just failed be redone with
 * in-browser Whisper? Same English gate as above: for anyone else the honest
 * answer is "type your message", not a tiny.en transcript. */
export function canRetryTurnOnDevice(input: {
  edgeOn: boolean;
  localSttInstalled: boolean;
  speechLikelyEnglish: boolean;
}): boolean {
  return input.edgeOn && input.localSttInstalled && input.speechLikelyEnglish;
}

/** How long a parked utterance (server transcription failed, Whisper not yet
 * installed) stays eligible for the "download, then finish THIS turn" replay.
 * Past this the conversation has moved on and replaying it would drop an old
 * message into the chat out of order. */
export const PARKED_UTTERANCE_MAX_AGE_MS = 2 * 60_000;

/** Replay the parked utterance now? Only when it is still fresh and the
 * panel is idle - never on top of a recording or a turn in flight (two turns
 * at once would overwrite each other's transcript, reply and stage). */
export function parkedUtteranceIsReplayable(input: {
  parkedAt: number | null | undefined;
  now: number;
  recording: boolean;
  pipelineBusy: boolean;
}): boolean {
  if (typeof input.parkedAt !== "number") return false;
  if (input.recording || input.pipelineBusy) return false;
  const age = input.now - input.parkedAt;
  return age >= 0 && age < PARKED_UTTERANCE_MAX_AGE_MS;
}

function downloadLine(what: string, d: DownloadState): string {
  const pct = typeof d.percent === "number" && d.percent > 0 ? ` ${Math.min(100, Math.round(d.percent))}%` : "";
  return `Downloading ${what}…${pct} It is used as soon as it is ready; nothing else on this screen stops working meanwhile.`;
}

/**
 * Every missing piece, most blocking first. An empty list means "nothing to
 * say" - the component renders nothing, so a working call stays quiet.
 */
export function evaluateCallPreflight(input: CallPreflightInput): PreflightIssue[] {
  const issues: PreflightIssue[] = [];

  // --- Microphone ---------------------------------------------------------
  if (input.mic === "unsupported") {
    issues.push({
      id: "mic-unsupported",
      severity: "blocking",
      line: "This browser cannot give a web page a microphone, so voice is unavailable here. Type your message in the chat instead, or open VegaDūta in a current Chrome, Edge, Firefox or Safari.",
    });
  } else if (input.mic === "insecure") {
    issues.push({
      id: "mic-insecure",
      severity: "blocking",
      line: "Browsers only allow the microphone on a secure (https) page. Open VegaDūta from its https address, or type your message in the chat instead.",
    });
  } else if (input.mic === "denied") {
    issues.push({
      id: "mic-denied",
      severity: "blocking",
      line: "The microphone is blocked for this site. Allow it from the lock or microphone icon in the address bar, then hold to talk again.",
      action: { kind: "test-mic", label: "Test microphone" },
    });
  } else if (input.mic === "no-device") {
    issues.push({
      id: "mic-no-device",
      severity: "blocking",
      line: "No microphone was found on this device. Plug one in or pair a headset, then try again - or type your message in the chat.",
      action: { kind: "test-mic", label: "Test microphone" },
    });
  } else if (input.mic === "busy") {
    issues.push({
      id: "mic-busy",
      severity: "blocking",
      line: "The microphone could not be opened - another app or tab may be using it. Close that app, then try again.",
      action: { kind: "test-mic", label: "Test microphone" },
    });
  }

  if (!input.mediaRecorderSupported && input.mic !== "unsupported" && input.mic !== "insecure") {
    issues.push({
      id: "recorder-unsupported",
      severity: "blocking",
      line: "This browser cannot record audio for hold-to-talk. Type your message in the chat instead, or open VegaDūta in a current Chrome, Edge, Firefox or Safari.",
    });
  }

  // --- Speech-to-text -----------------------------------------------------
  // (a) EVIDENCE: a turn in this call failed at the server's transcription.
  // (b) HINT: only the sidecar health probe says "down" - see the file header
  //     for why that is never stated as fact and never blocks.
  const canLocal = input.edgeOn && input.localSttSupported && input.speechLikelyEnglish;
  const localReady = input.edgeOn && input.localSttInstalled && input.speechLikelyEnglish;
  const size = formatApproxMegabytes(input.sttSizeBytes);
  if (input.serverSttFailedThisCall) {
    if (localReady) {
      issues.push({
        id: "stt-on-device",
        severity: "info",
        line: "The platform could not turn your last recording into text, so for the next few minutes your speech is transcribed on this device first (English only). When that works, only the text is sent; if this device cannot make out a recording, the audio is sent to the platform as usual.",
      });
    } else if (canLocal && input.sttDownload.status === "downloading") {
      issues.push({
        id: "stt-unavailable",
        severity: "blocking",
        line: downloadLine("on-device speech-to-text", input.sttDownload),
        progressPercent: input.sttDownload.percent ?? null,
      });
    } else if (canLocal && input.sttDownload.status === "failed") {
      issues.push({
        id: "stt-unavailable",
        severity: "blocking",
        line: `The on-device speech-to-text download did not finish${
          input.sttDownload.error ? ` (${input.sttDownload.error})` : ""
        }. Try it again, or type your message in the chat.`,
        action: { kind: "download-stt", label: "Try the download again" },
      });
    } else if (canLocal) {
      issues.push({
        id: "stt-unavailable",
        severity: "blocking",
        line: `The platform could not turn your last recording into text (its speech-to-text may be waking up after being idle). You can download on-device speech-to-text (${size}, one time, English only). With it, for a few minutes after a recording fails on the platform your speech is turned into text in this browser and only the text is sent; after that the platform is tried again with your audio.`,
        action: { kind: "download-stt", label: `Download (${size})` },
      });
    } else {
      issues.push({
        id: "stt-unavailable",
        severity: "blocking",
        line: "The platform could not turn your last recording into text (its speech-to-text may be waking up after being idle). Type your message in the chat meanwhile, then check again.",
        action: { kind: "recheck-server", label: "Check again" },
      });
    }
  } else if (!input.agentHasOwnSttProvider && input.serverSttChecked && !input.serverSttAvailable) {
    if (canLocal && input.sttDownload.status === "downloading") {
      issues.push({
        id: "stt-maybe-waking",
        severity: "degraded",
        line: downloadLine("on-device speech-to-text", input.sttDownload),
        progressPercent: input.sttDownload.percent ?? null,
      });
    } else if (canLocal && input.sttDownload.status === "failed") {
      issues.push({
        id: "stt-maybe-waking",
        severity: "degraded",
        line: `The on-device speech-to-text download did not finish${
          input.sttDownload.error ? ` (${input.sttDownload.error})` : ""
        }. Hold-to-talk still uses the platform's speech-to-text.`,
        action: { kind: "download-stt", label: "Try the download again" },
      });
    } else if (localReady) {
      issues.push({
        id: "stt-maybe-waking",
        severity: "info",
        line: "The platform's speech-to-text may be waking up after being idle. If it cannot transcribe a recording, this device transcribes it instead (English only).",
      });
    } else if (canLocal) {
      issues.push({
        id: "stt-maybe-waking",
        severity: "degraded",
        line: `The platform's speech-to-text may be waking up after being idle, so your first hold-to-talk can be slow or fail. Hold to talk as usual. To have a backup ready, you can download on-device speech-to-text (${size}, one time, English only); it takes over only if the platform fails to transcribe a recording.`,
        action: { kind: "download-stt", label: `Download (${size})` },
      });
    } else {
      issues.push({
        id: "stt-maybe-waking",
        severity: "degraded",
        line: "The platform's speech-to-text may be waking up after being idle, so your first hold-to-talk can be slow or fail. If it does, try once more or type your message in the chat.",
        action: { kind: "recheck-server", label: "Check again" },
      });
    }
  }

  // --- Voice --------------------------------------------------------------
  // A download that is running or has failed is always shown, whatever the
  // latest reply looked like - progress must not vanish mid-download.
  const kokoroUsable = input.edgeOn && input.localTtsInstalled;
  if ((input.serverVoiceMissing || input.ttsDownload.status !== "idle") && !kokoroUsable) {
    const canLocalVoice = input.edgeOn && input.localTtsSupported;
    const voiceSize = formatApproxMegabytes(input.ttsSizeBytes);
    const current = input.webSpeechPresent
      ? "replies use this browser's built-in voice"
      : "replies are text-only on this browser";
    if (canLocalVoice && input.ttsDownload.status === "downloading") {
      issues.push({
        id: "voice-downloading",
        severity: "info",
        line: downloadLine(`the on-device voice (${voiceSize}, one time)`, input.ttsDownload),
        progressPercent: input.ttsDownload.percent ?? null,
      });
    } else if (canLocalVoice && input.ttsDownload.status === "failed") {
      issues.push({
        id: "voice-missing",
        severity: "degraded",
        line: `The on-device voice download did not finish${
          input.ttsDownload.error ? ` (${input.ttsDownload.error})` : ""
        }, so ${current}. Your call still works.`,
        action: { kind: "download-tts", label: "Try the download again" },
      });
    } else if (canLocalVoice && input.serverVoiceMissing) {
      issues.push({
        id: "voice-missing",
        severity: "degraded",
        line: `No voice came with this reply, so ${current}. Download the on-device voice (${voiceSize}, one time, English) to have English replies spoken by it instead.`,
        action: { kind: "download-tts", label: `Download (${voiceSize})` },
      });
    } else if (input.serverVoiceMissing && !input.webSpeechPresent) {
      issues.push({
        id: "voice-missing",
        severity: "degraded",
        line: "Replies are text-only here: no voice came with this reply, and this browser has no built-in voice. The reply text always appears in the transcript.",
      });
    }
    // else: Web Speech is speaking and nothing better can run on this
    // device - there is no action to offer, so there is nothing to say.
  }

  const rank = { blocking: 0, degraded: 1, info: 2 } as const;
  return issues
    .map((issue, index) => ({ issue, index }))
    .sort((a, b) => rank[a.issue.severity] - rank[b.issue.severity] || a.index - b.index)
    .map((entry) => entry.issue);
}

/** How long a failed server transcription keeps turns on the on-device
 * route before the server is tried again. */
export const SERVER_STT_RETRY_AFTER_MS = 5 * 60_000;

export function serverSttFailureIsLive(failedAt: number | null | undefined, now: number): boolean {
  return typeof failedAt === "number" && now - failedAt >= 0 && now - failedAt < SERVER_STT_RETRY_AFTER_MS;
}

/** ensureSttModel()/ensureTtsModel() reject with a mix of display-safe copy
 * (storage, "not available on this device") and raw worker/library detail
 * ("TypeError: Failed to fetch", "no response within 120000ms"). Only the
 * former is fit to show a customer; everything else becomes a plain cause. */
export function displaySafeDownloadError(err: unknown): string {
  const message = (err instanceof Error ? err.message : typeof err === "string" ? err : "").trim();
  const lower = message.toLowerCase();
  if (/storage|space|quota/.test(lower)) return "not enough free storage on this device";
  if (lower.includes("not available on this device")) return "this device cannot run it";
  if (/no response within|timeout|timed out/.test(lower)) return "the connection stalled";
  if (/fetch|network|offline|load failed/.test(lower)) return "the connection dropped";
  return "something interrupted it";
}

/**
 * Narrower than isServerSttFailure: is the failure evidence that the
 * PLATFORM'S transcriber is down (as opposed to this one recording being
 * unreadable)? Only an outage switches later turns to the on-device route and
 * parks the utterance for a download-and-retry.
 *
 * VoiceCallController answers "No speech-to-text provider is configured for
 * this agent..." when its whole chain (agent's provider -> sidecar Whisper ->
 * Sarvam) came up empty - for a consumer with no Sarvam key that IS the
 * sidecar being down, whatever the wording says.
 */
export function isServerSttOutage(failure: { message?: string | null; status?: number | null }): boolean {
  const status = failure.status ?? null;
  if (status === 502 || status === 503 || status === 504) return true;
  const message = (failure.message || "").toLowerCase();
  return /no speech-to-text provider|temporarily unavailable|unreachable/.test(message);
}
