// The ONE place edge-facing display strings live.
//
// Brand-first naming rule (binding, 2026-08-04): the consumer-facing name is
// "VegaDūta Edge" / "On-device AI" — never a raw model name in primary copy.
// Underlying model identities (Gemini Nano today, Llama/Qwen-class models in
// Wave 2) appear ONLY in the expandable details panel and the model-picker
// rows — honest but secondary.
//
// This is a deliberate branding seam: white-label tenants will later swap
// these centrally, so components must import from here rather than scatter
// literals. Strings stay plain English for now — the i18n pass happens before
// the flag defaults on (en.json is fragile and off-limits this wave).

export const EDGE_BRANDING = {
  /** The product name for the edge capability. Leads all primary copy. */
  name: "VegaDūta Edge",
  /** Compact label for tight surfaces (the status chip) - matches the rail
      label, so the chip and the page it links to read the same. */
  shortName: "Browser models",
  /** The brand promise — the one line every opt-in surface leads with. */
  promise: "Runs on your device. Free. Private.",

  // -- Opt-in banner (AgentPlayground) ------------------------------------
  bannerTitle: "Try VegaDūta Edge",
  bannerBody: "Instant replies that run on your device. Free. Private.",
  bannerEnable: "Enable",
  bannerDismissLabel: "Dismiss VegaDūta Edge banner",

  // -- Setup chip / panel --------------------------------------------------
  panelToggleLabel: "On-device replies",
  hostedModeLabel: "Hosted mode",
  warmingUpLabel: "Warming up…",
  readyLabel: "ready",
  availableLabel: "available",
  unavailableLabel: "unavailable",
  /** Reliability blueprint PART1 "Edge AI hardening": shown instead of the
   * routine ready/warming/available labels while the device has no network —
   * on-device is still usable, but the chip should say so honestly rather
   * than imply a hosted fallback exists right now. */
  offlineLabel: "On-device only — you're offline",
  /** Shown when the hosted circuit breaker(s) have tripped — hosted requests
   * are being short-circuited rather than sent and failed one by one. */
  degradedLabel: "Hosted temporarily unavailable",
  /** Brief note surfaced while the tier router is probing a tripped breaker
   * to see if hosted has recovered. */
  retryingLabel: "Retrying…",
  /** Details-panel-only model identity (brand-first rule: secondary, honest). */
  tier0ModelDisplayName: "Chrome built-in model (Gemini Nano)",
  pickerAutoLabel: "Auto (recommended)",
  pickerAutoSubtitle: "Best model this device supports",
  pickerHostedLabel: "Always use hosted",
  pickerHostedSubtitle: "Replies always run on our servers",
  pickerNoModelsNote:
    "Downloadable models arrive in a later update. The browser's built-in model is used when available.",
  panelFooter:
    "VegaDūta Edge replies run in your browser, free, and stay on your device. Anything it can't handle falls back to hosted automatically.",

  // -- E-5 / S-edge on-device vision ---------------------------------------
  /** Chip/details display name for the local VLM asset (brand-first rule:
   * brand leads, the model identity stays secondary — same treatment as
   * tier0ModelDisplayName). */
  visionModelDisplayName: "On-device vision (SmolVLM2 500M)",
  /** E-0b rule 2 one-line consent copy, shown by the opt-in surface BEFORE
   * ensureVisionModel() is called: what, exact-ish size, source, and the
   * privacy why. */
  visionDownloadDetails:
    "On-device vision — about 500 MB, one-time download from the Hugging Face model hub. " +
    "Cached on this device and reused offline; frames you analyze never leave your browser.",

  // -- S-edge companion: on-device face recognition ------------------------
  /** Chip/details display name for the local face-recognition asset
   * (brand-first rule: brand leads, the model identity stays secondary —
   * same treatment as visionModelDisplayName). */
  faceRecognitionModelDisplayName: "On-device face recognition",
  /** E-0b rule 2 one-line consent copy, shown by the opt-in surface BEFORE
   * ensureFaceModel() is called: what, exact-ish size, source, and the
   * privacy why — same tone/length as visionDownloadDetails. */
  faceRecognitionDownloadDetails:
    "On-device face recognition — about 65 MB, one-time download from Google and Hugging Face model hubs. " +
    "Cached on this device and reused offline; faces you enroll or match never leave your browser.",

  // -- E-4 local voice: on-device speech-to-text (Whisper) -----------------
  /** Chip/details display name for the local STT asset (brand-first rule). */
  sttModelDisplayName: "On-device speech-to-text (Whisper tiny)",
  /** E-0b rule 2 one-line consent copy, shown BEFORE ensureSttModel() runs. */
  sttDownloadDetails:
    "On-device speech-to-text — about 45 MB, one-time download from the Hugging Face model hub. " +
    "Cached on this device and reused offline; recordings you transcribe never leave your browser.",

  // -- E-4 local voice: on-device text-to-speech (Kokoro) ------------------
  /** Chip/details display name for the local TTS asset (brand-first rule). */
  ttsModelDisplayName: "On-device voice (Kokoro)",
  /** E-0b rule 2 one-line consent copy, shown BEFORE ensureTtsModel() runs. */
  ttsDownloadDetails:
    "On-device voice — about 90 MB, one-time download from the Hugging Face model hub. " +
    "Cached on this device and reused offline; replies are spoken without a per-message cost.",

  // -- On-device generation: images (SD-Turbo) + audio (MusicGen) ----------
  /** Chip/details display name for the local image-generation asset. */
  imageGenModelDisplayName: "On-device image generation (SD-Turbo)",
  /** E-0b rule 2 one-line consent copy, shown BEFORE ensureImageGenModel() runs. */
  imageGenDownloadDetails:
    "On-device image generation — about 2.4 GB, one-time download from the Hugging Face model hub. " +
    "Needs WebGPU. Cached on this device and reused offline; prompts and images never leave your browser.",

  /** Chip/details display name for the local audio-generation asset. */
  audioGenModelDisplayName: "On-device audio generation (MusicGen)",
  /** E-0b rule 2 one-line consent copy, shown BEFORE ensureAudioGenModel() runs. */
  audioGenDownloadDetails:
    "On-device audio generation — about 1.8 GB, one-time download from the Hugging Face model hub. " +
    "Cached on this device and reused offline; prompts and clips never leave your browser.",

  // -- F-1 update channel (chip + panel) -----------------------------------
  /** Chip-label suffix — deliberately quiet ("On-device AI · update available"). */
  updateBadge: "update available",
  updateTitle: "Update available",
  updateBody:
    "A newer on-device model version is available. Your current one keeps working until the new download finishes and verifies.",
  updateAction: "Update now",

  // -- Download-engine failure copy (shown in the chip's status rows) ------
  /** E-0b rule 5: the polite refusal when storage headroom is short. */
  insufficientStorageError: "Not enough free space on this device",
  /** Integrity-check mismatch — the download is discarded and can be retried. */
  verificationFailedError: "Verification failed — try the download again",
  /** Network drop mid-download — cached progress is kept. */
  interruptedError: "Download interrupted — resume to continue",

  // -- Transcript marker on locally-answered turns -------------------------
  turnBadge: "on-device",

  // -- Live "running on-device" indicator (EdgeActivity consumers) ---------
  /** Shown while an on-device generation is actually in flight. */
  activeLabel: "Running on-device",
  /** Steady-state label when on-device serving is set up and idle. */
  onDeviceModeLabel: "On-device mode",

  // -- Model marketplace / management (Wave 3) -----------------------------
  marketplaceTitle: "On-device models",
  marketplaceSubtitle: "Download once, then replies run in your browser — free, private, and available offline.",
  installedSectionTitle: "On this device",
  notInstalledLabel: "Not downloaded",
  installedLabel: "Downloaded",
  downloadAction: "Download",
  pauseAction: "Pause",
  resumeAction: "Resume",
  cancelAction: "Cancel",
  deleteAction: "Remove from this device",
  deleteConfirmBody: "Removes the downloaded model files from this device. You can download them again any time.",
  switchAction: "Use this model",
  inUseLabel: "In use",

  // -- Routing preference (edge-first default) -----------------------------
  routingPrefTitle: "Where replies run",
  routingPrefEdgeFirstLabel: "On-device first (recommended)",
  routingPrefEdgeFirstSubtitle: "Answers run on your device when it can handle them; hosted covers the rest.",
  routingPrefHostedOnlyLabel: "Hosted only",
  routingPrefHostedOnlySubtitle: "Replies always run on our servers.",
  routingPrefAskLabel: "Let me choose",
  routingPrefAskSubtitle: "Nothing runs on-device until you turn it on yourself.",
  /** Capable device, edge-first, but no model downloaded yet (E-0b: never
   * auto-download) — the one-line nudge the "ready to enable" state renders. */
  readyToEnableTitle: "Your device can run replies locally",
  readyToEnableBody:
    "Download a one-time model to get instant, private, on-device answers. Hosted keeps working either way.",
  readyToEnableAction: "Set up on-device AI",
} as const;
