// ElevenLabs narration for an Academy lesson — expressive voice + real
// per-word timings for the SRT. Called from narrate.sh when
// ELEVENLABS_API_KEY is set; edge-tts remains the no-key fallback.
//
// Usage: node narrate-elevenlabs.mjs <lesson-dir>
// Env: ELEVENLABS_API_KEY (required), ELEVENLABS_VOICE_ID (default:
// Brian — deep, narrational), ELEVENLABS_MODEL (default:
// eleven_multilingual_v2), ELEVENLABS_STYLE (0..1 expressiveness).
import { writeFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";

const lessonDir = process.argv[2];
const key = process.env.ELEVENLABS_API_KEY;
if (!lessonDir || !key) {
  console.error("usage: narrate-elevenlabs.mjs <lesson-dir>  (needs ELEVENLABS_API_KEY)");
  process.exit(1);
}
const script = (await import(join(lessonDir, "script.mjs"))).default;
const voice = process.env.ELEVENLABS_VOICE_ID || "nPczCjzI2devNBz1zQrb"; // Brian
const model = process.env.ELEVENLABS_MODEL || "eleven_multilingual_v2";
const style = Number(process.env.ELEVENLABS_STYLE ?? "0.55"); // >0 = more expressive
const outDir = join(lessonDir, "dist");
mkdirSync(outDir, { recursive: true });

const pad = (n) => String(n).padStart(2, "0");
const srtTime = (sec) => {
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60),
    s = Math.floor(sec % 60), ms = Math.round((sec % 1) * 1000);
  return `${pad(h)}:${pad(m)}:${pad(s)},${String(ms).padStart(3, "0")}`;
};

// Char-level alignment -> grouped word cues (max ~6 words per cue reads
// naturally on screen).
function alignmentToSrt(al) {
  const chars = al.characters, starts = al.character_start_times_seconds,
    ends = al.character_end_times_seconds;
  const words = [];
  let w = "";
  for (let i = 0; i < chars.length; i++) {
    if (/\s/.test(chars[i])) {
      if (w) words.push({ t: w, s: starts[i - w.length], e: ends[i - 1] });
      w = "";
    } else w += chars[i];
  }
  if (w) words.push({ t: w, s: starts[chars.length - w.length], e: ends[chars.length - 1] });
  const cues = [];
  for (let i = 0; i < words.length; i += 6) {
    const g = words.slice(i, i + 6);
    cues.push(`${cues.length + 1}\n${srtTime(g[0].s)} --> ${srtTime(g[g.length - 1].e)}\n${g.map(x => x.t).join(" ")}\n`);
  }
  return cues.join("\n");
}

for (const [i, scene] of script.scenes.entries()) {
  const n = String(i + 1).padStart(2, "0");
  const res = await fetch(
    `https://api.elevenlabs.io/v1/text-to-speech/${voice}/with-timestamps`,
    {
      method: "POST",
      headers: { "xi-api-key": key, "content-type": "application/json" },
      body: JSON.stringify({
        text: scene.say,
        model_id: model,
        voice_settings: {
          stability: 0.42, similarity_boost: 0.85, style,
          use_speaker_boost: true,
        },
      }),
    }
  );
  if (!res.ok) throw new Error(`ElevenLabs ${res.status}: ${(await res.text()).slice(0, 200)}`);
  const { audio_base64, alignment } = await res.json();
  writeFileSync(join(outDir, `scene-${n}.mp3`), Buffer.from(audio_base64, "base64"));
  writeFileSync(join(outDir, `scene-${n}.srt`), alignmentToSrt(alignment));
  console.log(`narrated scene ${n} (elevenlabs)`);
}
