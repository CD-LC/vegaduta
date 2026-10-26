// The LOCAL media-generation tools: local_generate_image and
// local_generate_audio, the out-of-the-box "agent can make things" surface
// for a person who has no provider keys at all.
//
// What the handlers deliberately do NOT do is start a model download: a tool
// call is a model-initiated action and E-0b rule 2 keeps acquisition behind a
// person-initiated consent gesture in the Edge console. A tool that finds the
// model absent refuses with a sentence naming where to turn it on - the same
// honest-degradation contract every local tool follows, and the reason these
// two are network:"none" even though the models behind them are big: once the
// weights are cached, generation itself moves zero bytes.

import type { LocalTool } from "./localTools";
import { refuse } from "./tools/common";
import { edgeImageGenAvailable, generateImageLocally, isImageGenModelCached } from "./imagegen";
import { edgeAudioGenAvailable, generateAudioLocally, isAudioGenModelCached } from "./audiogen";

const MAX_PROMPT_CHARS = 500;

function readPrompt(args: Record<string, unknown>): string {
  const raw = args.prompt;
  if (typeof raw !== "string" || !raw.trim()) {
    refuse('"prompt" must be a non-empty string describing what to generate.');
  }
  const prompt = raw.trim();
  if (prompt.length > MAX_PROMPT_CHARS) {
    refuse(`"prompt" is ${prompt.length} characters; this tool accepts at most ${MAX_PROMPT_CHARS}.`);
  }
  return prompt;
}

function readOptionalNumber(args: Record<string, unknown>, key: string): number | undefined {
  const raw = args[key];
  if (raw === undefined || raw === null) return undefined;
  const n = Number(raw);
  if (!Number.isFinite(n)) refuse(`"${key}" must be a number, got ${typeof raw}.`);
  return n;
}

const IMAGE_GEN_MODEL_HINT =
  "the on-device image model isn't installed on this device yet. The person can turn it on in the Edge console's on-device models list - say so plainly rather than pretending this failed.";

async function runGenerateImage(args: Record<string, unknown>): Promise<string> {
  const prompt = readPrompt(args);
  const seed = readOptionalNumber(args, "seed");
  if (!(await edgeImageGenAvailable())) {
    refuse(
      "on-device image generation needs WebGPU, which this browser doesn't provide. The hosted generate_image tool is the fallback when a provider key exists."
    );
  }
  if (!isImageGenModelCached()) {
    refuse(IMAGE_GEN_MODEL_HINT);
  }
  const result = await generateImageLocally(prompt, seed);
  if (result.handled === false) {
    const detail =
      result.reason === "model-not-ready"
        ? IMAGE_GEN_MODEL_HINT
        : `image generation could not run locally (${result.reason}).`;
    refuse(detail);
  }
  const a = result.artifact;
  return (
    `Generated a ${a.width}x${a.height} image on-device` +
    (a.seed !== undefined ? ` (seed ${a.seed})` : "") +
    ` - artifact id ${a.id}, stored in this session's edge media gallery. ` +
    "The person can view or download it from the Edge console's generated-media list."
  );
}

const AUDIO_GEN_MODEL_HINT =
  "the on-device audio model isn't installed on this device yet. The person can turn it on in the Edge console's on-device models list - say so plainly rather than pretending this failed.";

async function runGenerateAudio(args: Record<string, unknown>): Promise<string> {
  const prompt = readPrompt(args);
  const durationSeconds = readOptionalNumber(args, "duration_seconds");
  if (!(await edgeAudioGenAvailable())) {
    refuse(
      "on-device audio generation needs WebGPU (or an 8GB+ device for the wasm path), which this browser doesn't provide."
    );
  }
  if (!isAudioGenModelCached()) {
    refuse(AUDIO_GEN_MODEL_HINT);
  }
  const result = await generateAudioLocally(prompt, durationSeconds);
  if (result.handled === false) {
    const detail =
      result.reason === "model-not-ready"
        ? AUDIO_GEN_MODEL_HINT
        : `audio generation could not run locally (${result.reason}).`;
    refuse(detail);
  }
  const a = result.artifact;
  return (
    `Generated a ${a.durationSeconds?.toFixed(1) ?? "?"}s audio clip on-device - artifact id ${a.id}, ` +
    "stored in this session's edge media gallery. The person can play or download it from the Edge console's generated-media list."
  );
}

export const MEDIA_LOCAL_TOOLS: LocalTool[] = [
  {
    name: "local_generate_image",
    description:
      "Generate a 512x512 image on this device from a text prompt (open-weights diffusion model, no server, no API key). Requires the on-device image model to be enabled; if the tool refuses saying the model isn't installed, tell the person to enable it in the Edge console.",
    inputSchema: {
      type: "object",
      properties: {
        prompt: { type: "string", description: "What to draw. Under 500 characters." },
        seed: { type: "number", description: "Optional integer seed for reproducible output." },
      },
      required: ["prompt"],
    },
    run: runGenerateImage,
    network: "none",
    consequential: false,
    // A generation legitimately takes minutes on integrated graphics; the
    // shared 2s ceiling would abandon every call.
    timeoutMs: 300_000,
  },
  {
    name: "local_generate_audio",
    description:
      "Generate a short music or sound clip (up to 30 seconds, WAV) on this device from a text prompt. Requires the on-device audio model to be enabled; if the tool refuses saying the model isn't installed, tell the person to enable it in the Edge console.",
    inputSchema: {
      type: "object",
      properties: {
        prompt: { type: "string", description: "What to compose. Under 500 characters." },
        duration_seconds: {
          type: "number",
          description: "Clip length in seconds, 1-30. Defaults to 8.",
        },
      },
      required: ["prompt"],
    },
    run: runGenerateAudio,
    network: "none",
    consequential: false,
    timeoutMs: 300_000,
  },
];
