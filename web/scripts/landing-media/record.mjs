// Renders storyboard.html (1280x720, 60 s timeline) to demo-60s.mp4 / demo-60s.webm / demo-poster.jpg
// and extracts one review frame per scene (frame-1.png .. frame-6.png) plus loop-seam frames.
//
//   node record.mjs                 # default: deterministic frame-by-frame render (recommended)
//   node record.mjs --screencast    # legacy: Playwright recordVideo screencast + blackdetect trim
//   node record.mjs --encode-only   # reuse out/cut.mkv, just re-run the ffmpeg encodes/frames
//
// Why frame-by-frame: Playwright's screencast is 25 fps, software-rendered (the blurred glows make it
// choppy) and its clock drifted 0.1–0.9 s per minute across runs, which made the loop seam unreliable.
// The default mode instead overrides performance.now/requestAnimationFrame inside the page, steps the
// clock by exactly 1/30 s, syncs every CSS transition/animation to that clock via the Web Animations API
// (document.getAnimations → pause + currentTime) and captures each frame with CDP. Output is bit-for-bit
// reproducible, exactly 1800 frames, no dropped frames.
//
// Env: OUT (output dir, default ./out), FFMPEG (ffmpeg binary), LOGO (logo-mark.png), WEB_ROOT.
import { spawn, spawnSync } from "node:child_process";
import { once } from "node:events";
import { existsSync, mkdirSync, renameSync, statSync } from "node:fs";
import { join } from "node:path";
import { FFMPEG, launch, openPage, OUT } from "./common.mjs";

const DURATION = 60;           // seconds of finished video
const FPS = 30;
const encodeOnly = process.argv.includes("--encode-only");
const screencast = process.argv.includes("--screencast");

mkdirSync(OUT, { recursive: true });
const cut = join(OUT, "cut.mkv");   // lossless intermediate shared by every encode and frame grab

function ff(args, label) {
  const r = spawnSync(FFMPEG, ["-hide_banner", "-loglevel", "error", "-y", ...args], { stdio: "inherit" });
  if (r.status !== 0) throw new Error(`ffmpeg failed (${label}) exit ${r.status}`);
}
function blackRuns(file) {
  const r = spawnSync(FFMPEG, ["-hide_banner", "-i", file, "-vf", "blackdetect=d=0.2:pix_th=0.06:pic_th=0.98", "-an", "-f", "null", "-"], { encoding: "utf8" });
  return [...(r.stderr || "").matchAll(/black_start:([\d.]+) black_end:([\d.]+) black_duration:([\d.]+)/g)]
    .map((m) => ({ start: +m[1], end: +m[2], dur: +m[3] }));
}

// ---------------------------------------------------------------- 1. produce cut.mkv
if (!encodeOnly && !screencast) {
  const browser = await launch();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1, colorScheme: "dark", reducedMotion: "no-preference" });
  const page = await openPage(ctx, "storyboard.html");
  const cdp = await ctx.newCDPSession(page);
  await page.evaluate(() => {
    // Stepped clock: the storyboard's timeline is rAF + performance.now driven, so this makes it deterministic.
    let vnow = 0;
    const queue = [];
    performance.now = () => vnow;
    window.requestAnimationFrame = (cb) => { queue.push(cb); return queue.length; };
    window.cancelAnimationFrame = () => {};
    const seen = new WeakMap();
    window.__frame = (ms) => {
      vnow += ms;
      for (const cb of queue.splice(0)) cb(vnow);
      // getAnimations() flushes style, so transitions created by this frame's class changes are caught here.
      for (const an of document.getAnimations()) {
        let vs = seen.get(an);
        if (vs === undefined) { vs = vnow; seen.set(an, vs); an.pause(); }
        an.currentTime = Math.max(0, vnow - vs);
      }
      return vnow;
    };
  });
  await page.evaluate(() => window.__start());
  await page.evaluate(() => window.__frame(0));

  const enc = spawn(FFMPEG, ["-hide_banner", "-loglevel", "error", "-y", "-f", "image2pipe", "-framerate", String(FPS), "-i", "-",
    "-vf", "format=yuv420p", "-c:v", "libx264", "-qp", "0", "-preset", "veryfast", "-an", cut], { stdio: ["pipe", "inherit", "inherit"] });
  const encDone = once(enc, "close");
  const total = DURATION * FPS;
  const t0 = Date.now();
  for (let i = 0; i < total; i++) {
    if (i > 0) await page.evaluate((ms) => window.__frame(ms), 1000 / FPS);
    const { data } = await cdp.send("Page.captureScreenshot", { format: "png" });
    if (!enc.stdin.write(Buffer.from(data, "base64"))) await once(enc.stdin, "drain");
    if (i % 300 === 0) console.log(`[render] frame ${i}/${total}  t=${(i / FPS).toFixed(1)}s  (${((Date.now() - t0) / 1000).toFixed(0)} s elapsed)`);
  }
  enc.stdin.end();
  const [code] = await encDone;
  if (code !== 0) throw new Error(`ffmpeg (cut) exited ${code}`);
  const log = await page.evaluate(() => window.__log || []);
  const worst = log.reduce((m, [p, a]) => Math.max(m, a - p), 0);
  console.log(`[render] ${total} frames in ${((Date.now() - t0) / 1000).toFixed(0)} s; ${log.length} timeline events, worst lag ${worst.toFixed(3)} s (≤ 1 frame expected)`);
  await browser.close();
}

if (!encodeOnly && screencast) {
  const RAW = join(OUT, "raw.webm");
  const browser = await launch();
  const ctx = await browser.newContext({
    viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1, colorScheme: "dark", reducedMotion: "no-preference",
    recordVideo: { dir: OUT, size: { width: 1280, height: 720 } },
  });
  const page = await openPage(ctx, "storyboard.html");
  await page.evaluate(() => window.__start());
  await page.waitForTimeout((DURATION + 1) * 1000);
  const log = await page.evaluate(() => window.__log || []);
  const worst = log.reduce((m, [p, a]) => Math.max(m, a - p), 0);
  console.log(`[timeline] ${log.length} events fired, worst lag ${worst.toFixed(3)} s`);
  const video = page.video();
  await ctx.close();
  const tmp = await video.path();
  await browser.close();
  if (existsSync(RAW)) { try { renameSync(RAW, RAW + ".old"); } catch {} }
  renameSync(tmp, RAW);
  // Before __start() the page is pure canvas under the #fade overlay, so the first long black run ends
  // where scene 1 begins to fade in; that is a far better t=0 than any Node-side timestamp.
  const lead = blackRuns(RAW).find((b) => b.start < 30 && b.dur >= 0.5);
  const trim = lead ? Math.max(0, lead.end - 0.12) : 0;
  console.log(`[trim] black lead-in ${lead ? `${lead.start}s → ${lead.end}s` : "not found"}; trimming ${trim.toFixed(3)} s`);
  ff(["-ss", trim.toFixed(3), "-i", RAW, "-t", String(DURATION), "-vf", `fps=${FPS},scale=1280:720:flags=lanczos,format=yuv420p`, "-c:v", "libx264", "-qp", "0", "-preset", "veryfast", "-an", cut], "cut");
}

// ---------------------------------------------------------------- 2. encodes + review frames
const mp4 = join(OUT, "demo-60s.mp4");
const webm = join(OUT, "demo-60s.webm");
const poster = join(OUT, "demo-poster.jpg");
ff(["-i", cut, "-vf", `fps=${FPS},format=yuv420p`, "-c:v", "libx264", "-crf", "22", "-preset", "slow", "-movflags", "+faststart", "-an", mp4], "mp4");
ff(["-i", cut, "-c:v", "libvpx-vp9", "-crf", "34", "-b:v", "0", "-row-mt", "1", "-deadline", "good", "-cpu-used", "2", "-an", webm], "webm");
ff(["-ss", "15", "-i", cut, "-frames:v", "1", "-q:v", "3", poster], "poster");   // -q:v 3 ≈ JPEG quality ~80

const FRAME_TIMES = [5, 16, 26, 36, 47, 55];
FRAME_TIMES.forEach((t, i) => ff(["-ss", String(t), "-i", cut, "-frames:v", "1", join(OUT, `frame-${i + 1}.png`)], `frame-${i + 1}`));
ff(["-ss", "0.15", "-i", cut, "-frames:v", "1", join(OUT, "frame-seam-start.png")], "seam-start");
ff(["-ss", "59.85", "-i", cut, "-frames:v", "1", join(OUT, "frame-seam-end.png")], "seam-end");

// Loop-seam check: the cut must start black-ish and end with a black run that begins before ~59.6 s.
const runs = blackRuns(cut);
const tail = runs.filter((b) => b.start > 50).at(-1);
console.log(tail ? `[seam] tail black from ${tail.start} s to ${tail.end} s${tail.start > 59.6 ? "  (WARNING: late fade-out)" : ""}` : "[seam] WARNING: no black tail detected");

for (const f of [mp4, webm, poster]) console.log(`[out] ${f}  ${(statSync(f).size / 1024).toFixed(0)} KB`);
