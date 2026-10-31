// Client-side bridge: the server-side local model (Ollama/core) never sees
// the browser's `local_generate_*` tools — those execute in the page. When a
// chat message is unmistakably a media-generation request, we run the tool
// here instead of sending the turn, and append the tool's summary as the
// assistant reply (the artifact itself lands in the edge media gallery).
//
// Deliberately conservative: only fires on explicit image/audio verbs so
// ordinary chat still reaches the model.

import { MEDIA_LOCAL_TOOLS } from "./localToolsMedia";
import { edgeImageGenAvailable, isImageGenModelCached } from "./imagegen";
import { edgeAudioGenAvailable, isAudioGenModelCached } from "./audiogen";

export type LocalMediaResult =
  | { handled: true; tool: string; reply: string }
  | { handled: false };

const IMAGE_RE =
  /\b(generate|create|make|draw|paint|produce|render|show me)\b[^.!?]{0,80}\b(image|picture|photo|drawing|art|illustration|portrait|poster|logo|sketch|wallpaper)\b|\b(image|picture|photo|drawing|illustration|logo|sketch)\s+(of|for|with)\b/i;
const AUDIO_RE =
  /\b(generate|create|make|compose|produce|play)\b[^.!?]{0,80}\b(music|song|sound|audio|clip|jingle|melody|track|tune)\b|\b(music|song|melody|jingle|sound)\s+(of|for|about)\b/i;

const EXPLICIT_TOOL_RE = /local_generate_(image|audio)/i;

/** Runs a local media tool when the message clearly asks for one. Never throws. */
export async function tryLocalMediaTool(message: string): Promise<LocalMediaResult> {
  const text = message.trim();
  if (!text) return { handled: false };

  const explicit = EXPLICIT_TOOL_RE.exec(text);
  const wantsImage = explicit?.[1] === "image" || (!explicit && IMAGE_RE.test(text));
  const wantsAudio = explicit?.[1] === "audio" || (!explicit && AUDIO_RE.test(text));
  if (!wantsImage && !wantsAudio) return { handled: false };

  const toolName = wantsImage ? "local_generate_image" : "local_generate_audio";
  const ready = wantsImage
    ? (await edgeImageGenAvailable()) && isImageGenModelCached()
    : (await edgeAudioGenAvailable()) && isAudioGenModelCached();
  if (!ready) return { handled: false };

  const tool = MEDIA_LOCAL_TOOLS.find((t) => t.name === toolName);
  if (!tool) return { handled: false };
  try {
    const reply = await tool.run({ prompt: text });
    return { handled: true, tool: toolName, reply };
  } catch (err) {
    // A refused/failed tool still answers better than a generic model refusal.
    return { handled: true, tool: toolName, reply: err instanceof Error ? err.message : String(err) };
  }
}
