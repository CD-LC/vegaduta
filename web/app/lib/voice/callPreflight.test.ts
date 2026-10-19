// Call preflight rules (2026-09-19): which missing piece gets which line and
// which single action - and, as importantly, when it says NOTHING.

import { describe, expect, it } from "vitest";
import {
  PARKED_UTTERANCE_MAX_AGE_MS,
  SERVER_STT_RETRY_AFTER_MS,
  canRetryTurnOnDevice,
  displaySafeDownloadError,
  evaluateCallPreflight,
  formatApproxMegabytes,
  isServerSttFailure,
  isServerSttOutage,
  likelyEnglishSpeaker,
  micStateFromError,
  parkedUtteranceIsReplayable,
  serverSttFailureIsLive,
  shouldTranscribeOnDeviceFirst,
  type CallPreflightInput,
} from "./callPreflight";
import { progressPercent } from "./callEnvironment";

const healthy: CallPreflightInput = {
  mic: "granted",
  mediaRecorderSupported: true,
  serverSttChecked: true,
  serverSttAvailable: true,
  serverSttFailedThisCall: false,
  agentHasOwnSttProvider: false,
  speechLikelyEnglish: true,
  edgeOn: true,
  localSttSupported: true,
  localSttInstalled: false,
  localTtsSupported: true,
  localTtsInstalled: false,
  serverVoiceMissing: false,
  webSpeechPresent: true,
  sttDownload: { status: "idle" },
  ttsDownload: { status: "idle" },
  sttSizeBytes: 45_000_000,
  ttsSizeBytes: 90_000_000,
};

const ids = (input: CallPreflightInput) => evaluateCallPreflight(input).map((i) => i.id);

describe("evaluateCallPreflight", () => {
  it("says nothing when nothing is missing", () => {
    expect(evaluateCallPreflight(healthy)).toEqual([]);
    expect(evaluateCallPreflight({ ...healthy, mic: "unknown" })).toEqual([]);
    expect(evaluateCallPreflight({ ...healthy, mic: "prompt" })).toEqual([]);
  });

  it("says nothing about the server before the first health probe has completed", () => {
    expect(evaluateCallPreflight({ ...healthy, serverSttChecked: false, serverSttAvailable: false })).toEqual([]);
  });

  it("every issue is one line with at most one action", () => {
    const worst: CallPreflightInput = {
      ...healthy,
      mic: "denied",
      mediaRecorderSupported: false,
      serverSttAvailable: false,
      serverVoiceMissing: true,
    };
    const issues = evaluateCallPreflight(worst);
    expect(issues.length).toBeGreaterThanOrEqual(3);
    for (const issue of issues) {
      expect(issue.line.length).toBeGreaterThan(20);
      expect(issue.line).not.toMatch(/\n/);
    }
  });

  it("maps each microphone state to its own blocking line", () => {
    expect(ids({ ...healthy, mic: "denied" })).toEqual(["mic-denied"]);
    expect(ids({ ...healthy, mic: "no-device" })).toEqual(["mic-no-device"]);
    expect(ids({ ...healthy, mic: "busy" })).toEqual(["mic-busy"]);
    expect(ids({ ...healthy, mic: "insecure" })).toEqual(["mic-insecure"]);
    expect(ids({ ...healthy, mic: "unsupported" })).toEqual(["mic-unsupported"]);
    expect(evaluateCallPreflight({ ...healthy, mic: "denied" })[0].action?.kind).toBe("test-mic");
  });

  it("reports a missing MediaRecorder, but not on top of a missing microphone API", () => {
    expect(ids({ ...healthy, mediaRecorderSupported: false })).toEqual(["recorder-unsupported"]);
    expect(ids({ ...healthy, mic: "unsupported", mediaRecorderSupported: false })).toEqual(["mic-unsupported"]);
  });

  describe("a turn really failed at the server's speech-to-text", () => {
    const failed = { ...healthy, serverSttFailedThisCall: true };

    it("offers the on-device download, with its size and its real language limit", () => {
      const [issue] = evaluateCallPreflight(failed);
      expect(issue.id).toBe("stt-unavailable");
      expect(issue.severity).toBe("blocking");
      expect(issue.action).toEqual({ kind: "download-stt", label: "Download (about 45 MB)" });
      expect(issue.line).toContain("about 45 MB");
      expect(issue.line).toContain("English only");
    });

    it("does not promise that audio stays in the browser from then on", () => {
      const [issue] = evaluateCallPreflight(failed);
      expect(issue.line).not.toMatch(/stays in this browser/i);
      expect(issue.line).toMatch(/tried again with your audio/i);
    });

    it("counts as evidence whatever the probe says, and for an agent with its own provider", () => {
      expect(ids({ ...failed, serverSttAvailable: true })).toEqual(["stt-unavailable"]);
      expect(ids({ ...failed, serverSttAvailable: false, agentHasOwnSttProvider: true })).toEqual(["stt-unavailable"]);
    });

    it("shows progress instead of the button while downloading", () => {
      const [issue] = evaluateCallPreflight({ ...failed, sttDownload: { status: "downloading", percent: 42 } });
      expect(issue.action).toBeUndefined();
      expect(issue.progressPercent).toBe(42);
      expect(issue.line).toContain("42%");
    });

    it("keeps a progress row (no percentage) before totals are known", () => {
      const [issue] = evaluateCallPreflight({ ...failed, sttDownload: { status: "downloading", percent: null } });
      expect(issue.progressPercent).toBeNull();
      expect(issue.line).not.toContain("%");
    });

    it("offers a retry after a failed download", () => {
      const [issue] = evaluateCallPreflight({
        ...failed,
        sttDownload: { status: "failed", error: "the connection dropped" },
      });
      expect(issue.action?.kind).toBe("download-stt");
      expect(issue.line).toContain("the connection dropped");
    });

    it("turns into a quiet info line once Whisper is installed - and admits the audio fallback", () => {
      const [issue] = evaluateCallPreflight({ ...failed, localSttInstalled: true });
      expect(issue.id).toBe("stt-on-device");
      expect(issue.severity).toBe("info");
      expect(issue.action).toBeUndefined();
      // sendTurn uploads the audio when Whisper cannot make out a recording.
      expect(issue.line).toMatch(/audio is sent to the platform/i);
      expect(issue.line).not.toMatch(/and only the text is sent\.$/);
    });

    it("offers no download when the edge surface is off or the device cannot run it", () => {
      for (const input of [
        { ...failed, edgeOn: false },
        { ...failed, localSttSupported: false },
        // An installed marker means nothing in a build with edge compiled out.
        { ...failed, edgeOn: false, localSttInstalled: true },
      ]) {
        const [issue] = evaluateCallPreflight(input);
        expect(issue.id).toBe("stt-unavailable");
        expect(issue.action?.kind).toBe("recheck-server");
        expect(issue.line).not.toMatch(/download/i);
      }
    });

    it("never offers or uses the English-only transcriber for someone who does not look English-speaking", () => {
      for (const input of [
        { ...failed, speechLikelyEnglish: false },
        { ...failed, speechLikelyEnglish: false, localSttInstalled: true },
      ]) {
        const [issue] = evaluateCallPreflight(input);
        expect(issue.id).toBe("stt-unavailable");
        expect(issue.action?.kind).toBe("recheck-server");
        expect(issue.line).not.toMatch(/download|on this device/i);
        expect(issue.line).toMatch(/type your message/i);
      }
    });
  });

  describe("only the sidecar health probe says down (a hint, not evidence)", () => {
    // The probe cannot see a tenant's Sarvam key, which keeps the server's
    // speech-to-text working while the sidecar sleeps.
    const probeDown = { ...healthy, serverSttAvailable: false };

    it("never blocks and never states that speech-to-text is not responding", () => {
      for (const input of [
        probeDown,
        { ...probeDown, localSttInstalled: true },
        { ...probeDown, edgeOn: false },
        { ...probeDown, speechLikelyEnglish: false },
        { ...probeDown, sttDownload: { status: "failed" as const, error: "the connection dropped" } },
        { ...probeDown, sttDownload: { status: "downloading" as const, percent: 10 } },
      ]) {
        const [issue] = evaluateCallPreflight(input);
        expect(issue.id).toBe("stt-maybe-waking");
        expect(issue.severity).not.toBe("blocking");
        expect(issue.line).not.toMatch(/is not responding/i);
      }
    });

    it("offers the download as a backup and says when it would be used", () => {
      const [issue] = evaluateCallPreflight(probeDown);
      expect(issue.severity).toBe("degraded");
      expect(issue.action).toEqual({ kind: "download-stt", label: "Download (about 45 MB)" });
      expect(issue.line).toContain("English only");
      expect(issue.line).toMatch(/only if the platform fails/i);
    });

    it("is a quiet info line once Whisper is installed", () => {
      const [issue] = evaluateCallPreflight({ ...probeDown, localSttInstalled: true });
      expect(issue.severity).toBe("info");
      expect(issue.action).toBeUndefined();
    });

    it("offers only a re-check where on-device speech-to-text cannot run or is the wrong language", () => {
      for (const input of [
        { ...probeDown, edgeOn: false },
        { ...probeDown, localSttSupported: false },
        { ...probeDown, speechLikelyEnglish: false },
      ]) {
        const [issue] = evaluateCallPreflight(input);
        expect(issue.action?.kind).toBe("recheck-server");
        expect(issue.line).not.toMatch(/download/i);
      }
    });

    it("says nothing for an agent with its own speech-to-text provider", () => {
      expect(ids({ ...probeDown, agentHasOwnSttProvider: true })).toEqual([]);
    });
  });

  describe("no server voice", () => {
    const voiceless = { ...healthy, serverVoiceMissing: true };

    it("offers Kokoro with its size, as a degradation rather than a blocker", () => {
      const [issue] = evaluateCallPreflight(voiceless);
      expect(issue.id).toBe("voice-missing");
      expect(issue.severity).toBe("degraded");
      expect(issue.action).toEqual({ kind: "download-tts", label: "Download (about 90 MB)" });
      expect(issue.line).toContain("built-in voice");
    });

    it("says text-only when the browser has no voice of its own", () => {
      const [issue] = evaluateCallPreflight({ ...voiceless, webSpeechPresent: false });
      expect(issue.line).toContain("text-only");
      expect(issue.action?.kind).toBe("download-tts");
    });

    it("is silent once Kokoro is installed", () => {
      expect(evaluateCallPreflight({ ...voiceless, localTtsInstalled: true })).toEqual([]);
    });

    it("shows the automatic download's progress as info", () => {
      const [issue] = evaluateCallPreflight({ ...voiceless, ttsDownload: { status: "downloading", percent: 7 } });
      expect(issue.id).toBe("voice-downloading");
      expect(issue.severity).toBe("info");
      expect(issue.progressPercent).toBe(7);
    });

    it("keeps a running or failed download on screen even when the latest reply was not English", () => {
      const running = evaluateCallPreflight({
        ...healthy,
        serverVoiceMissing: false,
        ttsDownload: { status: "downloading", percent: 30 },
      });
      expect(running.map((i) => i.id)).toEqual(["voice-downloading"]);
      const failedDownload = evaluateCallPreflight({
        ...healthy,
        serverVoiceMissing: false,
        ttsDownload: { status: "failed", error: "the connection dropped" },
      });
      expect(failedDownload[0].action?.kind).toBe("download-tts");
    });

    it("offers nothing when no English-speakable reply came back voiceless", () => {
      // CallPanel passes serverVoiceMissing:false for a Hindi/Kannada/Arabic reply.
      expect(evaluateCallPreflight({ ...healthy, serverVoiceMissing: false })).toEqual([]);
    });

    it("has nothing to offer - so says nothing - when Web Speech works and Kokoro cannot run", () => {
      expect(evaluateCallPreflight({ ...voiceless, localTtsSupported: false })).toEqual([]);
      expect(evaluateCallPreflight({ ...voiceless, edgeOn: false })).toEqual([]);
    });

    it("explains the silence when neither Kokoro nor Web Speech can run", () => {
      const [issue] = evaluateCallPreflight({ ...voiceless, edgeOn: false, webSpeechPresent: false });
      expect(issue.id).toBe("voice-missing");
      expect(issue.action).toBeUndefined();
    });
  });

  it("orders blocking before degraded before info", () => {
    const issues = evaluateCallPreflight({
      ...healthy,
      serverVoiceMissing: true,
      ttsDownload: { status: "downloading", percent: 5 },
      serverSttAvailable: false,
      mic: "denied",
    });
    expect(issues.map((i) => i.severity)).toEqual(["blocking", "degraded", "info"]);
    const afterFailure = evaluateCallPreflight({
      ...healthy,
      serverVoiceMissing: true,
      ttsDownload: { status: "downloading", percent: 5 },
      serverSttFailedThisCall: true,
      mic: "denied",
    });
    expect(afterFailure.map((i) => i.severity)).toEqual(["blocking", "blocking", "info"]);
  });

  it("never claims a language count (do-not-claim list)", () => {
    const everything = [
      { ...healthy, serverSttAvailable: false },
      { ...healthy, serverSttAvailable: false, localSttInstalled: true },
      { ...healthy, serverSttFailedThisCall: true },
      { ...healthy, serverSttFailedThisCall: true, localSttInstalled: true },
      { ...healthy, serverVoiceMissing: true },
    ].flatMap((input) => evaluateCallPreflight(input));
    for (const issue of everything) expect(issue.line).not.toMatch(/\d+\s+languages/i);
  });
});

describe("isServerSttFailure / isServerSttOutage", () => {
  const noProvider =
    "No speech-to-text provider is configured for this agent. Add a Sarvam credential, or select another voice provider for this agent, to enable transcription.";

  it("recognises the production failure (sidecar asleep, no Sarvam key)", () => {
    expect(isServerSttFailure({ message: noProvider, status: 400, transcriptReceived: false })).toBe(true);
    expect(isServerSttOutage({ message: noProvider, status: 400 })).toBe(true);
  });

  it("treats gateway errors as a transcription-step outage", () => {
    for (const status of [502, 503, 504]) {
      expect(isServerSttFailure({ message: "", status, transcriptReceived: false })).toBe(true);
      expect(isServerSttOutage({ message: "", status })).toBe(true);
    }
  });

  it("an unreadable recording is a failure worth one local retry, but not an outage", () => {
    const f = { message: "Could not transcribe that recording. Unsupported codec", status: 400 };
    expect(isServerSttFailure({ ...f, transcriptReceived: false })).toBe(true);
    expect(isServerSttOutage(f)).toBe(false);
  });

  it("never reroutes around sign-in, plan or quota refusals", () => {
    for (const status of [401, 402, 403, 429]) {
      expect(isServerSttFailure({ message: "transcription quota", status, transcriptReceived: false })).toBe(false);
    }
  });

  it("never reroutes once a transcript exists, or for a silent recording", () => {
    expect(isServerSttFailure({ message: "transcribe", status: 500, transcriptReceived: true })).toBe(false);
    expect(
      isServerSttFailure({
        message: "No speech was detected in that recording. Please try speaking more clearly.",
        status: 400,
        transcriptReceived: false,
      })
    ).toBe(false);
  });

  it("ignores unrelated failures", () => {
    expect(isServerSttFailure({ message: "The model provider rejected the request", status: 500, transcriptReceived: false })).toBe(false);
    expect(isServerSttFailure({ message: null, status: null, transcriptReceived: false })).toBe(false);
  });
});

describe("shouldTranscribeOnDeviceFirst", () => {
  const base = {
    edgeOn: true,
    localSttInstalled: true,
    serverSttFailedThisCall: true,
    speechLikelyEnglish: true,
  };
  it("goes on-device after a turn really failed at the server, when Whisper is installed", () => {
    expect(shouldTranscribeOnDeviceFirst(base)).toBe(true);
  });
  it("never on the health probe alone - a tenant's Sarvam key keeps the server working while the sidecar sleeps", () => {
    // The function does not even take the probe; without a failed turn it is false.
    expect(shouldTranscribeOnDeviceFirst({ ...base, serverSttFailedThisCall: false })).toBe(false);
  });
  it("never when Whisper is not installed, or edge is off", () => {
    expect(shouldTranscribeOnDeviceFirst({ ...base, localSttInstalled: false })).toBe(false);
    expect(shouldTranscribeOnDeviceFirst({ ...base, edgeOn: false })).toBe(false);
  });
  it("never feeds non-English speech to the English-only transcriber", () => {
    expect(shouldTranscribeOnDeviceFirst({ ...base, speechLikelyEnglish: false })).toBe(false);
    expect(canRetryTurnOnDevice({ edgeOn: true, localSttInstalled: true, speechLikelyEnglish: false })).toBe(false);
    expect(canRetryTurnOnDevice({ edgeOn: true, localSttInstalled: true, speechLikelyEnglish: true })).toBe(true);
    expect(canRetryTurnOnDevice({ edgeOn: true, localSttInstalled: false, speechLikelyEnglish: true })).toBe(false);
    expect(canRetryTurnOnDevice({ edgeOn: false, localSttInstalled: true, speechLikelyEnglish: true })).toBe(false);
  });
});

describe("likelyEnglishSpeaker", () => {
  it("needs every known signal to be English", () => {
    expect(likelyEnglishSpeaker(["en-US", "en"])).toBe(true);
    expect(likelyEnglishSpeaker(["en-IN"])).toBe(true);
    expect(likelyEnglishSpeaker(["EN_gb", null, ""])).toBe(true);
    expect(likelyEnglishSpeaker(["en-IN", "hi"])).toBe(false);
    expect(likelyEnglishSpeaker(["kn-IN", "en"])).toBe(false);
  });
  it("treats no signal at all as not English, and is not fooled by a prefix", () => {
    expect(likelyEnglishSpeaker([])).toBe(false);
    expect(likelyEnglishSpeaker([null, undefined, " "])).toBe(false);
    expect(likelyEnglishSpeaker(["eng-x"])).toBe(false);
  });
});

describe("parkedUtteranceIsReplayable", () => {
  const now = 1_000_000;
  const idle = { now, recording: false, pipelineBusy: false };
  it("replays a fresh recording on an idle panel", () => {
    expect(parkedUtteranceIsReplayable({ ...idle, parkedAt: now - 30_000 })).toBe(true);
  });
  it("never when nothing is parked (a new hold, a new turn or a typed message cleared it)", () => {
    expect(parkedUtteranceIsReplayable({ ...idle, parkedAt: null })).toBe(false);
    expect(parkedUtteranceIsReplayable({ ...idle, parkedAt: undefined })).toBe(false);
  });
  it("never on top of a recording or a turn in flight", () => {
    expect(parkedUtteranceIsReplayable({ ...idle, parkedAt: now - 1_000, recording: true })).toBe(false);
    expect(parkedUtteranceIsReplayable({ ...idle, parkedAt: now - 1_000, pipelineBusy: true })).toBe(false);
  });
  it("drops a recording that is too old to still be the current message", () => {
    expect(parkedUtteranceIsReplayable({ ...idle, parkedAt: now - PARKED_UTTERANCE_MAX_AGE_MS })).toBe(false);
    expect(parkedUtteranceIsReplayable({ ...idle, parkedAt: now - PARKED_UTTERANCE_MAX_AGE_MS + 1 })).toBe(true);
    expect(parkedUtteranceIsReplayable({ ...idle, parkedAt: now + 5_000 })).toBe(false);
  });
});

describe("small helpers", () => {
  it("formatApproxMegabytes rounds honestly", () => {
    expect(formatApproxMegabytes(45_000_000)).toBe("about 45 MB");
    expect(formatApproxMegabytes(90_000_000)).toBe("about 90 MB");
    expect(formatApproxMegabytes(3_400_000)).toBe("about 3 MB");
    expect(formatApproxMegabytes(0)).toBe("about 1 MB");
  });

  it("micStateFromError never guesses", () => {
    expect(micStateFromError("NotAllowedError")).toBe("denied");
    expect(micStateFromError("NotFoundError")).toBe("no-device");
    expect(micStateFromError("NotReadableError")).toBe("busy");
    expect(micStateFromError("SecurityError")).toBe("insecure");
    expect(micStateFromError("TypeError")).toBe("unknown");
    expect(micStateFromError(undefined)).toBe("unknown");
  });

  it("serverSttFailureIsLive expires so the server gets tried again", () => {
    const t0 = 1_000_000;
    expect(serverSttFailureIsLive(null, t0)).toBe(false);
    expect(serverSttFailureIsLive(t0, t0 + 1000)).toBe(true);
    expect(serverSttFailureIsLive(t0, t0 + SERVER_STT_RETRY_AFTER_MS)).toBe(false);
    expect(serverSttFailureIsLive(t0 + 5000, t0)).toBe(false); // clock went backwards
  });

  it("displaySafeDownloadError never leaks library detail", () => {
    expect(displaySafeDownloadError(new Error("TypeError: Failed to fetch"))).toBe("the connection dropped");
    expect(displaySafeDownloadError(new Error("no response within 120000ms"))).toBe("the connection stalled");
    expect(displaySafeDownloadError(new Error("Not enough free storage for this download"))).toBe(
      "not enough free storage on this device"
    );
    expect(displaySafeDownloadError(new Error("On-device voice is not available on this device."))).toBe(
      "this device cannot run it"
    );
    expect(displaySafeDownloadError(new Error("RuntimeError: abort(OOM) at wasm://..."))).toBe(
      "something interrupted it"
    );
    expect(displaySafeDownloadError(undefined)).toBe("something interrupted it");
  });

  it("progressPercent ignores tiny config files and garbage", () => {
    expect(progressPercent({ loaded: 45_000_000, total: 90_000_000 })).toBe(50);
    expect(progressPercent({ loaded: 200, total: 200 })).toBeNull();
    expect(progressPercent({ loaded: 5, total: 0 })).toBeNull();
    expect(progressPercent(null)).toBeNull();
    expect(progressPercent({ loaded: 99_000_000, total: 90_000_000 })).toBe(100);
  });
});
