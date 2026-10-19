package com.agenticai.core.tenancy;

import com.agenticai.core.tenancy.EdgeAgentsConfigController.EdgeModelEntry;
import com.agenticai.core.tenancy.EdgeAgentsConfigController.EdgeModelManifest;
import java.util.List;

/**
 * The in-code Edge model data behind {@link EdgeAgentsConfigController}: the seeded default
 * manifest, the admin "browse official registry" candidates and the fixed audio/vision
 * utilities. Pure data (every size and id verbatim from the installed WebLLM / client constants,
 * see each list's javadoc); the controller re-exposes these lists under its own historical
 * constant names.
 */
final class EdgeModelCatalog {

    private EdgeModelCatalog() {}

    /**
     * Seeded default. Brand-first display names -
     * model identities live in {@code detailName} only. The tier-1 ids and sizes are taken
     * verbatim from the installed WebLLM package's prebuilt model records
     * ({@code @mlc-ai/web-llm} {@code vram_required_MB}: Llama 3B 2263.69, Qwen 1.5B 1629.75,
     * Llama 1B 879.04) so the client engine can resolve them by id against the MLC CDN.
     */
    static final EdgeModelManifest DEFAULT_MANIFEST = new EdgeModelManifest("1", List.of(
        new EdgeModelEntry(
            "chrome-builtin",
            0,
            "Instant on-device model",
            "Provided by your browser (Chrome built-in AI, Gemini Nano) - nothing to download.",
            0L, null, null, null, false,
            "llm",
            // Untagged on purpose: speedTier only differentiates tier-1 WebLLM
            // rows the selector chooses between; tier 0 is its own tier.
            null,
            // No published window: the Prompt API's limit is opaque (D-9).
            null
        ),
        new EdgeModelEntry(
            "Llama-3.2-3B-Instruct-q4f16_1-MLC",
            1,
            "On-device model - larger",
            "Llama 3.2 3B Instruct (q4f16), about 2.3 GB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            2_263_690_000L, null, null, 8.0, true,
            "llm",
            "capable",
            4096
        ),
        new EdgeModelEntry(
            "Qwen2.5-1.5B-Instruct-q4f16_1-MLC",
            1,
            "On-device model - balanced",
            "Qwen2.5 1.5B Instruct (q4f16), about 1.6 GB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            1_629_750_000L, null, null, 4.0, true,
            "llm",
            "fast",
            4096
        ),
        new EdgeModelEntry(
            "Llama-3.2-1B-Instruct-q4f16_1-MLC",
            1,
            "On-device model - lightest",
            "Llama 3.2 1B Instruct (q4f16), about 0.9 GB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            879_040_000L, null, null, 4.0, true,
            "llm",
            "fast",
            4096
        ),
        new EdgeModelEntry(
            "rife-video-interpolation",
            2,
            "Clip Studio motion engine - fast",
            "RIFE frame interpolation (ONNX, fp32), ~21.6 MB, from "
                + "huggingface.co/FuryTMP/RIFE_fp32 (MIT-licensed model card, verified reachable "
                + "2026-08-06, promoted from the registry candidate to the default catalog "
                + "2026-08-07). Faster and smaller than the quality engine below; weakest on "
                + "large/fast motion between keyframes.",
            21_604_631L,
            "https://huggingface.co/FuryTMP/RIFE_fp32/resolve/main/RIFE_fp32.onnx",
            // SE-75: Hugging Face's X-Linked-ETag for this LFS object (the sha256 of the file;
            // X-Linked-Size 21604631 matches sizeBytes), read via a HEAD request 2026-10-14.
            "6a31074c0f588648982b5e828aee6c27e005015a712a46ea63da48c65fa9a26b", null, false,
            "video-interpolation",
            null,
            null
        ),
        new EdgeModelEntry(
            "film-video-interpolation",
            2,
            "Clip Studio motion engine - quality",
            "FILM (Frame Interpolation for Large Motion) frame interpolation (ONNX). Larger "
                + "download than the fast engine, but noticeably less warping/ghosting on bigger "
                + "camera moves between keyframes. Weights not yet published - url stays null "
                + "until they are; clients must treat this entry as not yet downloadable.",
            0L, null, null, null, false,
            "video-interpolation",
            null,
            null
        ),
        // Appended (not interleaved with the original trio above) so existing
        // index-based assertions in EdgeAgentsConfigControllerTest and any
        // stored-override JSON an admin already saved keep matching by
        // position. vram_required_MB verbatim from the installed
        // @mlc-ai/web-llm@0.2.84 package's lib/index.js prebuiltAppConfig
        // (2026-08-06) - sizeBytes follows the same
        // vram_required_MB * 1_000_000 convention as the trio above.
        new EdgeModelEntry(
            "Phi-3.5-mini-instruct-q4f16_1-MLC",
            1,
            "On-device model - most capable",
            "Phi 3.5 Mini Instruct (q4f16), about 3.7 GB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            3_672_070_000L, null, null, 8.0, true,
            "llm",
            "capable",
            4096
        ),
        new EdgeModelEntry(
            "gemma-2-2b-it-q4f16_1-MLC",
            1,
            "On-device model - efficient",
            "Gemma 2 2B Instruct (q4f16), about 1.9 GB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            1_895_300_000L, null, null, 4.0, true,
            "llm",
            "fast",
            4096
        ),
        // Appended 2026-09-09 (cross-scope item recorded in
        // docs/AGENT-COORDINATION.md): the tool-calling model the client's
        // AG-UI activity trace depends on. The client-side
        // BUILTIN_FALLBACK_MANIFEST in web/app/lib/edge/tier1.ts already
        // carried this row, so devices that fell back to the baked-in list
        // got it while devices that trusted GET /api/edge/manifest did not -
        // the two manifests must select identically, so it is mirrored here
        // field-for-field (same id, sizeBytes, minDeviceMemoryGB, speedTier,
        // contextWindowSize). Appended last, like the Phi/Gemma rows above,
        // so index-based test assertions and stored admin overrides keep
        // matching by position. sizeBytes follows the same
        // vram_required_MB * 1_000_000 convention as every other tier-1 row
        // (4033.28 MB verbatim from the installed @mlc-ai/web-llm@0.2.84
        // prebuiltAppConfig record). Known gap carried over from tier1.ts's
        // own comment on this row: the package record also declares
        // required_features ["shader-f16"], which this wire record has no
        // field to gate on (the client's minMaxBufferSize floor has no
        // server-side counterpart either - EdgeModelEntry never grew one);
        // a device lacking it fails the load with a Tier1Error the client
        // router already falls through on, so it degrades visibly, not
        // silently.
        new EdgeModelEntry(
            "Hermes-2-Pro-Mistral-7B-q4f16_1-MLC",
            1,
            "On-device model - tool-savvy",
            "Hermes 2 Pro (Mistral 7B, q4f16), about 4.0 GB. Fine-tuned for reliable "
                + "tool/function calling. Downloaded from the public MLC/WebLLM model CDN.",
            4_033_280_000L, null, null, 8.0, true,
            "llm",
            "capable",
            4096
        ),
        // --- Coding models, promoted from REGISTRY_CATALOG 2026-09-17 ------
        // Why by default rather than admin-addable: the IDE/browser plugins
        // (clients/) are the biggest consumer of this manifest, and for their
        // main jobs - inline completions, Fix, Refactor - a code-trained 1.5B
        // beats a general 7B. Leaving the only coder models behind an admin
        // click meant every fresh install auto-selected a general chat model
        // for code work. Clients tag these "code" (the id-derived use case in
        // clients/shared/src/edge/capabilities.ts) and prefer them for code
        // turns; a general turn still picks a general model.
        //
        // Ids, sizeBytes (vram_required_MB * 1e6) and contextWindowSize are
        // verbatim from the installed @mlc-ai/web-llm@0.2.84 prebuiltAppConfig,
        // same never-guess rule as every row above; minDeviceMemoryGB follows
        // this file's existing vram tiers.
        new EdgeModelEntry(
            "Qwen2.5-Coder-1.5B-Instruct-q4f16_1-MLC",
            1,
            "On-device coder - light",
            "Qwen2.5 Coder 1.5B Instruct (q4f16), about 1.6 GB. Code-trained: inline completions, "
                + "fix and refactor. No VegaDuta-hosted weights yet - downloaded from the public "
                + "MLC/WebLLM model CDN.",
            1_629_750_000L, null, null, 4.0, true,
            "llm",
            "fast",
            4096
        ),
        new EdgeModelEntry(
            "Qwen2.5-Coder-3B-Instruct-q4f16_1-MLC",
            1,
            "On-device coder - balanced",
            "Qwen2.5 Coder 3B Instruct (q4f16), about 2.5 GB. The best code quality per gigabyte "
                + "in this catalog. No VegaDuta-hosted weights yet - downloaded from the public "
                + "MLC/WebLLM model CDN.",
            2_504_760_000L, null, null, 8.0, true,
            "llm",
            "capable",
            4096
        ),
        new EdgeModelEntry(
            "Qwen2.5-Coder-7B-Instruct-q4f16_1-MLC",
            1,
            "On-device coder - high quality",
            "Qwen2.5 Coder 7B Instruct (q4f16), about 5.1 GB. Needs a large GPU. No VegaDuta-hosted "
                + "weights yet - downloaded from the public MLC/WebLLM model CDN.",
            5_106_670_000L, null, null, 16.0, true,
            "llm",
            "capable",
            4096
        ),
        new EdgeModelEntry(
            "Qwen3-4B-q4f16_1-MLC",
            1,
            "On-device model - capable",
            "Qwen3 4B (q4f16), about 3.4 GB. Newer generation than the Llama/Qwen2.5 rows above. "
                + "No VegaDuta-hosted weights yet - downloaded from the public MLC/WebLLM model CDN.",
            3_431_590_000L, null, null, 8.0, true,
            "llm",
            "capable",
            4096
        )
    ));

    /**
     * The web-admin editor's "browse official registry" discovery panel (GET
     * {@code /manifest/registry}, platform-admin only): additional WebLLM chat
     * models the installed engine ({@code @mlc-ai/web-llm}) can already serve
     * - ids verified against the same servable whitelist
     * {@code web/app/lib/edge/tier1.ts}'s {@code KNOWN_WEBLLM_MODEL_IDS}
     * enforces client-side - that aren't in {@link #DEFAULT_MANIFEST} yet.
     * Deliberately curated (one representative instruct/chat variant per
     * model family and size class, not every quantization/context-window
     * permutation the engine can technically run) rather than the full
     * ~130-id set, so an admin is choosing between genuinely distinct
     * size/capability tradeoffs. {@code sizeBytes}/{@code minDeviceMemoryGB}
     * follow the exact same real, never-estimated
     * {@code vram_required_MB * 1_000_000} / vram-tier convention as
     * {@link #DEFAULT_MANIFEST} (extracted verbatim from the installed
     * package 2026-08-06 - re-extract on a web-llm upgrade the same way
     * tier1.ts's whitelist comment instructs). Listing a model here serves it
     * to NO ONE by itself - it only reaches real clients once an admin clicks
     * "Add" in the editor and saves, which writes it into the
     * {@code edge.model-manifest} platform setting exactly like toggling an
     * existing {@link #DEFAULT_MANIFEST} row on.
     *
     * <p>2026-08-07: the RIFE candidate that used to live here
     * ({@code rife-fp32-onnx-furytmp} - verified reachable 2026-08-06 HTTP
     * 200, MIT-licensed, {@code content-length: 21604631}) was promoted
     * straight into {@link #DEFAULT_MANIFEST}'s {@code rife-video-interpolation}
     * row (the "isn't published yet" Motion+ empty state was reported live in
     * this environment - the url was already verified, so publishing it by
     * default rather than waiting on a separate admin click was the fix). No
     * FILM-class (Frame Interpolation for Large Motion) equivalent is listed
     * here - as of the same research pass, no public ONNX export of FILM with
     * clear licensing was found (only TensorFlow SavedModel / safetensors
     * sources), so none was added rather than guessing.
     *
     * <p><b>2026-08-07 request, three candidates deliberately NOT added</b> -
     * checked against the installed {@code @mlc-ai/web-llm@0.2.84} package's
     * own {@code prebuiltAppConfig} (same verbatim-only rule as everything
     * else in this file) rather than assumed:
     * <ul>
     *   <li><b>DeepSeek-Coder V2 Lite</b> - no {@code DeepSeek-Coder}/
     *   {@code DeepSeek-V2} entry of any kind exists in the installed
     *   package (only the unrelated {@code DeepSeek-R1-Distill-*} rows
     *   already represented above by {@code DeepSeek-R1-Distill-Qwen-7B}) -
     *   MLC has not published a WebGPU-compiled build of this model, so the
     *   client engine has nothing to fetch by any id for it.</li>
     *   <li><b>Codestral</b> - zero matches anywhere in the installed
     *   package under any spelling. Same as above: not offered by the
     *   engine, not addable.</li>
     *   <li><b>Llama 3.1 70B Instruct</b> - DOES exist
     *   ({@code Llama-3.1-70B-Instruct-q3f16_1-MLC}, and is already in
     *   {@code tier1.ts}'s client whitelist), but its real, verbatim
     *   {@code vram_required_MB} is 31153.13 (~31 GB) - roughly 5-6x the
     *   largest model already in this catalog ({@code gemma-2-9b-it},
     *   6.4 GB) and well past what any consumer GPU/browser tab combination
     *   can hold. Held back as impractical rather than added with a
     *   technically-real-but-unusable entry; revisit only if a smaller
     *   official quantization (e.g. a future 4-bit single-digit-GB build)
     *   is ever published.</li>
     * </ul>
     */
    static final List<EdgeModelEntry> REGISTRY_CATALOG = List.of(
        new EdgeModelEntry(
            "SmolLM2-360M-Instruct-q4f16_1-MLC", 1,
            "On-device model - ultra-light",
            "SmolLM2 360M Instruct (q4f16), about 376 MB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            376_060_000L, null, null, 4.0, true, "llm", "fast", 4096
        ),
        new EdgeModelEntry(
            "TinyLlama-1.1B-Chat-v1.0-q4f16_1-MLC", 1,
            "On-device model - minimal",
            "TinyLlama 1.1B Chat v1.0 (q4f16), about 697 MB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            697_240_000L, null, null, 4.0, true, "llm", "fast", 2048
        ),
        new EdgeModelEntry(
            "Qwen2.5-0.5B-Instruct-q4f16_1-MLC", 1,
            "On-device model - compact",
            "Qwen2.5 0.5B Instruct (q4f16), about 945 MB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            944_620_000L, null, null, 4.0, true, "llm", "fast", 4096
        ),
        new EdgeModelEntry(
            "Qwen3-0.6B-q4f16_1-MLC", 1,
            "On-device model - quick",
            "Qwen3 0.6B (q4f16), about 1.4 GB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            1_403_340_000L, null, null, 4.0, true, "llm", "fast", 4096
        ),
        // Added 2026-08-07 (user request: "add Qwen 3 ... to marketplace" -
        // 0.6B above already covers the quick/light end of this family; this
        // is the largest Qwen3 size the installed engine can serve, for the
        // capable end). vram_required_MB=5695.78 taken verbatim from the
        // installed @mlc-ai/web-llm@0.2.84 package's lib/index.js
        // prebuiltAppConfig entry for this exact model_id (same
        // never-guessed convention as every other row in this file) - id
        // already present in web/app/lib/edge/tier1.ts's
        // KNOWN_WEBLLM_MODEL_IDS servable whitelist, so no client-side
        // change was needed alongside this one.
        new EdgeModelEntry(
            "Qwen3-8B-q4f16_1-MLC", 1,
            "On-device model - latest-generation, capable",
            "Qwen3 8B (q4f16), about 5.7 GB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            5_695_780_000L, null, null, 16.0, true, "llm", "capable", 4096
        ),
        // Description sharpened 2026-09-11 (no id/size/tier change): this is
        // the SMALLEST tool-trained model the installed engine can serve, and
        // an admin picking a model for agents that call tools had no way to
        // tell that from "conversational". The Hermes family is the one
        // WebLLM's own (still-WIP) native `tools` support is gated to, and
        // the client now prefers a tool-trained model over a merely larger
        // one on tool turns (web/app/lib/edge/capabilities.ts,
        // isToolTunedModelId) - so this row is what that preference resolves
        // to on a mid-range device. The 7B Hermes-2-Pro-Mistral row further
        // down stays the higher-quality option at ~1.8x the download.
        new EdgeModelEntry(
            "Hermes-3-Llama-3.2-3B-q4f16_1-MLC", 1,
            "On-device model - tool-calling, compact",
            "Hermes 3 (Llama 3.2 3B) (q4f16), about 2.3 GB. Fine-tuned for tool/function "
                + "calling - the smallest tool-trained model this engine serves. No "
                + "VegaDuta-hosted weights yet - downloaded from the public MLC/WebLLM model CDN.",
            2_263_690_000L, null, null, 8.0, true, "llm", "capable", 4096
        ),
        // The Qwen2.5-Coder sizes that used to sit here (3B/7B) were
        // promoted into DEFAULT_MANIFEST on 2026-09-17, alongside a new 1.5B
        // - see the note there. Registry rows must stay disjoint from the
        // default catalog (enforced by EdgeAgentsConfigControllerTest), so
        // they are gone from this list rather than duplicated.
        new EdgeModelEntry(
            "Phi-4-mini-instruct-q4f16_1-MLC", 1,
            "On-device model - latest-generation",
            "Phi 4 Mini Instruct (q4f16), about 3.4 GB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            3_437_580_000L, null, null, 8.0, true, "llm", "capable", 4096
        ),
        new EdgeModelEntry(
            "Mistral-7B-Instruct-v0.3-q4f16_1-MLC", 1,
            "On-device model - versatile",
            "Mistral 7B Instruct v0.3 (q4f16), about 4.6 GB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            4_573_390_000L, null, null, 16.0, true, "llm", "capable", 4096
        ),
        new EdgeModelEntry(
            "Llama-3.1-8B-Instruct-q4f16_1-MLC", 1,
            "On-device model - high-quality",
            "Llama 3.1 8B Instruct (q4f16), about 5.0 GB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            5_001_000_000L, null, null, 16.0, true, "llm", "capable", 4096
        ),
        new EdgeModelEntry(
            "Qwen2.5-7B-Instruct-q4f16_1-MLC", 1,
            "On-device model - well-rounded",
            "Qwen2.5 7B Instruct (q4f16), about 5.1 GB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            5_106_670_000L, null, null, 16.0, true, "llm", "capable", 4096
        ),
        new EdgeModelEntry(
            "DeepSeek-R1-Distill-Qwen-7B-q4f16_1-MLC", 1,
            "On-device model - reasoning-focused",
            "DeepSeek R1 Distill Qwen 7B (q4f16), about 5.1 GB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            5_106_670_000L, null, null, 16.0, true, "llm", "capable", 4096
        ),
        new EdgeModelEntry(
            "gemma-2-9b-it-q4f16_1-MLC", 1,
            "On-device model - largest available",
            "Gemma 2 9B Instruct (q4f16), about 6.4 GB. No VegaDuta-hosted weights yet - "
                + "downloaded from the public MLC/WebLLM model CDN.",
            6_422_010_000L, null, null, 16.0, true, "llm", "capable", 4096
        )
    );

    /**
     * The fixed on-device audio/vision utilities (E-4/E-5 in the edge plan):
     * Whisper (STT), Kokoro (TTS), SmolVLM (vision). Unlike every entry above,
     * these are NOT manifest-driven - each is a single hardcoded model the
     * client resolves by id against its own library's default CDN (see
     * {@code web/app/lib/edge/audio/whisper.ts}/{@code kokoro.ts}/
     * {@code vision.ts}'s header comments for the "why no manifest entry"
     * reasoning) and there is no admin choice to make (nothing to enable,
     * nothing to disable, no marketplace of alternatives). This list exists
     * PURELY so the web-admin catalog editor can show admins the full edge
     * surface instead of only the LLM half of it - {@link EdgeAgentsConfigController#getManifestForEditor()}
     * publishes it read-only, with no corresponding write/toggle path.
     * {@code id}/{@code sizeBytes}/{@code displayName}/{@code detailName} are
     * copied verbatim from the client's own constants
     * (STT_ASSET_ID/STT_MODEL_SIZE_BYTES + EDGE_BRANDING.sttModelDisplayName/
     * sttDownloadDetails, and the TTS/vision equivalents) so the two never
     * drift. {@code tier} is 2 (WASM/other) like the RIFE/FILM entries above -
     * transformers.js runs these via WebGPU-when-available, WASM fallback
     * otherwise, so {@code requiresWebGpu} is false (unlike the tier-1 LLM
     * rows, which need it).
     */
    static final List<EdgeModelEntry> FIXED_UTILITIES = List.of(
        new EdgeModelEntry(
            "edge-stt-whisper",
            2,
            "On-device speech-to-text (Whisper tiny)",
            "On-device speech-to-text - about 45 MB, one-time download from the Hugging Face "
                + "model hub. Cached on this device and reused offline; recordings you transcribe "
                + "never leave your browser.",
            45_000_000L, null, null, null, false,
            "stt",
            null,
            null
        ),
        new EdgeModelEntry(
            "edge-tts-kokoro",
            2,
            "On-device voice (Kokoro)",
            "On-device voice - about 90 MB, one-time download from the Hugging Face model hub. "
                + "Cached on this device and reused offline; replies are spoken without a "
                + "per-message cost.",
            90_000_000L, null, null, null, false,
            "tts",
            null,
            null
        ),
        new EdgeModelEntry(
            "edge-vision-smolvlm",
            2,
            "On-device vision (SmolVLM2 500M)",
            "On-device vision - about 500 MB, one-time download from the Hugging Face model hub. "
                + "Cached on this device and reused offline; frames you analyze never leave your "
                + "browser.",
            500_000_000L, null, null, null, false,
            "vision",
            null,
            null
        )
    );
}
