// AI image, video and music generation for the sandbox. Never rejects: every failure is a
// `simulated: true` result the page labels as a sample; see ../demoClient.ts.

import { API_BASE, absoluteImageUrl, readJson } from "./demoHttp";

// --- AI image generation ---------------------------------------------------

export type DemoImageResult = {
  // Absolute URL, ready for an <img src>. Null whenever `simulated` is true —
  // there is genuinely nothing to render, and handing the page a URL that
  // 404s would produce exactly the broken-image state this surface must never
  // show.
  imageUrl: string | null;
  imagesUsed: number;
  imageLimit: number;
  // True for EVERY non-image outcome: a refused prompt, an exhausted
  // allowance, a busy model. The UI labels it as a sample and renders its own
  // placeholder. Presenting a placeholder as generated output would be the
  // fabricated demo this whole feature exists to replace.
  simulated: boolean;
  // Always a sentence when `simulated`; null on success.
  message: string | null;
};


/**
 * Generates one image. Like `sendMessage`, this NEVER rejects and never
 * returns an error shape — a network failure resolves to the same
 * `simulated: true` result the server produces for its own failures, so the
 * page has one code path and no error branch at all.
 */
export async function generateImage(token: string, prompt: string): Promise<DemoImageResult> {
  const offline: DemoImageResult = {
    imageUrl: null,
    imagesUsed: 0,
    imageLimit: 0,
    simulated: true,
    message: "We couldn't reach the image model just now. Your sandbox is fine — try again in a moment.",
  };
  try {
    const res = await fetch(`${API_BASE}/api/demo/image`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Demo-Token": token },
      body: JSON.stringify({ prompt }),
    });
    const body = await readJson(res);
    // A validation rejection (or any other non-demo-shaped body) falls through
    // to the offline sample rather than surfacing a status code.
    if (!body || typeof body.simulated !== "boolean") return offline;
    return {
      imageUrl: absoluteImageUrl(body.imageUrl),
      imagesUsed: typeof body.imagesUsed === "number" ? body.imagesUsed : 0,
      imageLimit: typeof body.imageLimit === "number" ? body.imageLimit : 0,
      simulated: body.simulated,
      message: typeof body.message === "string" ? body.message : null,
    };
  } catch {
    return offline;
  }
}

// --- AI video generation -----------------------------------------------------
//
// Same shape and error philosophy as generateImage above, pointed at
// /api/demo/video (DemoVideoController, core/imagegen). Short and heavily
// capped on purpose - see that controller's javadoc: video is billed per
// second of output, so the anonymous sandbox gets a single ~15s clip, not an
// open allowance. The request can take up to ~100s server-side (the
// controller long-polls the provider itself) - callers should show a patient
// "generating your video" state, not a snappy spinner.

export type DemoVideoResult = {
  videoUrl: string | null;
  videosUsed: number;
  videoLimit: number;
  durationSeconds: number;
  simulated: boolean;
  message: string | null;
};

export async function generateVideo(token: string, prompt: string): Promise<DemoVideoResult> {
  const offline: DemoVideoResult = {
    videoUrl: null,
    videosUsed: 0,
    videoLimit: 0,
    durationSeconds: 0,
    simulated: true,
    message: "We couldn't reach the video model just now. Your sandbox is fine — try again in a moment.",
  };
  try {
    const res = await fetch(`${API_BASE}/api/demo/video`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Demo-Token": token },
      body: JSON.stringify({ prompt }),
    });
    const body = await readJson(res);
    if (!body || typeof body.simulated !== "boolean") return offline;
    return {
      videoUrl: absoluteImageUrl(body.videoUrl),
      videosUsed: typeof body.videosUsed === "number" ? body.videosUsed : 0,
      videoLimit: typeof body.videoLimit === "number" ? body.videoLimit : 0,
      durationSeconds: typeof body.durationSeconds === "number" ? body.durationSeconds : 0,
      simulated: body.simulated,
      message: typeof body.message === "string" ? body.message : null,
    };
  } catch {
    return offline;
  }
}

// --- AI music generation -----------------------------------------------------
//
// Same shape and error philosophy as generateImage, pointed at /api/demo/music
// (DemoMusicController, core/agents). One short clip per sandbox: music is
// billed per generated minute on a platform-paid key. `model` names what
// actually composed the clip and `lowCostModel` is the server saying it picked
// the cheapest listed model - the page repeats that to the visitor instead of
// implying the demo shows the best the platform can do.

export type DemoMusicResult = {
  // Absolute URL, ready for an <audio src>. Null whenever `simulated` is true.
  musicUrl: string | null;
  musicUsed: number;
  musicLimit: number;
  durationSeconds: number;
  simulated: boolean;
  message: string | null;
  model: string | null;
  lowCostModel: boolean;
};

export type DemoMusicStatus = {
  // False when the sandbox cannot make music at all right now (switched off,
  // no platform key, zero allowance) - the page then offers no music mode
  // rather than one that can only decline.
  enabled: boolean;
  musicUsed: number;
  musicLimit: number;
  clipSeconds: number;
};

const MUSIC_UNAVAILABLE: DemoMusicStatus = { enabled: false, musicUsed: 0, musicLimit: 0, clipSeconds: 0 };

/**
 * Free to call (spends nothing server-side). Never rejects: an older core that
 * has no /api/demo/music/status, or a network failure, is simply "no music
 * mode" - the rest of the sandbox is unaffected.
 */
export async function fetchMusicStatus(token: string): Promise<DemoMusicStatus> {
  try {
    const res = await fetch(`${API_BASE}/api/demo/music/status`, {
      headers: { "X-Demo-Token": token },
    });
    if (!res.ok) return MUSIC_UNAVAILABLE;
    const body = await readJson(res);
    if (!body || typeof body.enabled !== "boolean") return MUSIC_UNAVAILABLE;
    return {
      enabled: body.enabled,
      musicUsed: typeof body.musicUsed === "number" ? body.musicUsed : 0,
      musicLimit: typeof body.musicLimit === "number" ? body.musicLimit : 0,
      clipSeconds: typeof body.clipSeconds === "number" ? body.clipSeconds : 0,
    };
  } catch {
    return MUSIC_UNAVAILABLE;
  }
}

export async function generateMusic(token: string, prompt: string): Promise<DemoMusicResult> {
  const offline: DemoMusicResult = {
    musicUrl: null,
    musicUsed: 0,
    musicLimit: 0,
    durationSeconds: 0,
    simulated: true,
    message: "We couldn't reach the music model just now. Your sandbox is fine — try again in a moment.",
    model: null,
    lowCostModel: false,
  };
  try {
    const res = await fetch(`${API_BASE}/api/demo/music`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Demo-Token": token },
      body: JSON.stringify({ prompt }),
    });
    const body = await readJson(res);
    if (!body || typeof body.simulated !== "boolean") return offline;
    return {
      musicUrl: absoluteImageUrl(body.musicUrl),
      musicUsed: typeof body.musicUsed === "number" ? body.musicUsed : 0,
      musicLimit: typeof body.musicLimit === "number" ? body.musicLimit : 0,
      durationSeconds: typeof body.durationSeconds === "number" ? body.durationSeconds : 0,
      simulated: body.simulated,
      message: typeof body.message === "string" ? body.message : null,
      model: typeof body.model === "string" && body.model ? body.model : null,
      lowCostModel: body.lowCostModel === true,
    };
  } catch {
    return offline;
  }
}
