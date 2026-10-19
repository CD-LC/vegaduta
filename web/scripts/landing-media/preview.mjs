// Fast iteration: jump the storyboard's timers to given seconds (fake clock) and screenshot.
// CSS transitions still run in real time, so we wait ~900 ms after each jump for them to settle.
// Usage: node preview.mjs [t1 t2 ...]   (default 5 16 26 36 47 55)
import { mkdirSync } from "node:fs";
import { join } from "node:path";
import { launch, openPage, OUT } from "./common.mjs";

const times = process.argv.slice(2).map(Number).filter((n) => !Number.isNaN(n));
const T = times.length ? times : [5, 16, 26, 36, 47, 55];
mkdirSync(OUT, { recursive: true });

const browser = await launch();
for (const t of T) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1, colorScheme: "dark", reducedMotion: "no-preference" });
  const page = await openPage(ctx, "storyboard.html");
  await page.clock.install();
  await page.evaluate(() => window.__start());
  await page.clock.runFor(Math.round(t * 1000));
  await page.waitForTimeout(900);
  const file = join(OUT, `preview-${String(t).replace(".", "_")}.png`);
  await page.screenshot({ path: file });
  console.log("wrote", file);
  await ctx.close();
}
await browser.close();
