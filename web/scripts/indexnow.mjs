#!/usr/bin/env node
// IndexNow submitter (GEO pass, 2026-09-19).
//
// Why: ChatGPT Search and Microsoft Copilot answer from Bing's index. Bing
// does not discover a small site quickly on its own, and IndexNow is the
// supported way to tell it "these URLs changed, fetch them" — one POST, picked
// up by Bing, Yandex, Seznam and Naver. Google does not take part; it relies
// on the sitemap.
//
// It reads the LIVE sitemap, so it can only ever announce URLs that are really
// deployed — run it after a production deploy, never before. Announcing URLs
// that 404 teaches the index the site is unreliable.
//
//   node scripts/indexnow.mjs                 dry run: prints what it would send
//   node scripts/indexnow.mjs --submit        sends every sitemap URL
//   node scripts/indexnow.mjs --submit --only /glossary,/ai/mcp
//
// Configuration (environment):
//   INDEXNOW_KEY   your IndexNow key (required; the script exits 0 if unset)
//   INDEXNOW_HOST  public hostname to announce, e.g. "example.com"
//                  (defaults to the host of SITE_URL / NEXT_PUBLIC_SITE_URL)
//
// The key is not a secret: the protocol REQUIRES it to be publicly readable at
// https://<host>/<key>.txt so the index can verify the sender controls the
// host. Serve that file from your deployment (e.g. web/public/<key>.txt) and
// rotate by replacing the file and INDEXNOW_KEY together.

const KEY = (process.env.INDEXNOW_KEY || "").trim();
const SITE = (process.env.SITE_URL || process.env.NEXT_PUBLIC_SITE_URL || "").trim();
let HOST = (process.env.INDEXNOW_HOST || "").trim();
if (!HOST && SITE) {
  try {
    HOST = new URL(SITE).hostname;
  } catch {
    HOST = "";
  }
}
if (!KEY || !HOST) {
  console.log(
    "IndexNow not configured: set INDEXNOW_KEY and INDEXNOW_HOST (or SITE_URL) to submit. Skipping."
  );
  process.exit(0);
}
const KEY_LOCATION = `https://${HOST}/${KEY}.txt`;
const SITEMAP = `https://${HOST}/sitemap.xml`;
const ENDPOINT = "https://api.indexnow.org/indexnow";
const MAX_URLS = 10000; // protocol limit per request

const args = process.argv.slice(2);
const submit = args.includes("--submit");
const onlyAt = args.indexOf("--only");
const only = onlyAt >= 0 ? (args[onlyAt + 1] || "").split(",").filter(Boolean) : null;

async function main() {
  const keyCheck = await fetch(KEY_LOCATION);
  const served = keyCheck.ok ? (await keyCheck.text()).trim() : null;
  if (served !== KEY) {
    console.error(
      `Key file not live: GET ${KEY_LOCATION} -> ${keyCheck.status}. ` +
        "IndexNow rejects submissions it cannot verify; deploy web first."
    );
    process.exit(1);
  }

  const xml = await (await fetch(SITEMAP)).text();
  let urls = [...xml.matchAll(/<loc>([^<]+)<\/loc>/g)].map((m) => m[1].trim());
  urls = [...new Set(urls)].filter((u) => new URL(u).hostname === HOST);
  if (only) {
    const wanted = new Set(only.map((p) => `https://${HOST}${p === "/" ? "/" : p.replace(/\/$/, "")}`));
    urls = urls.filter((u) => wanted.has(u) || wanted.has(u.replace(/\/$/, "")));
  }
  if (urls.length === 0) throw new Error("no URLs to submit");
  if (urls.length > MAX_URLS) throw new Error(`${urls.length} URLs exceeds the ${MAX_URLS} limit; batch it`);

  console.log(`${urls.length} URL(s) from ${SITEMAP}`);
  for (const u of urls.slice(0, 8)) console.log("  " + u);
  if (urls.length > 8) console.log(`  … and ${urls.length - 8} more`);

  if (!submit) {
    console.log("\nDry run. Re-run with --submit to send.");
    return;
  }

  const res = await fetch(ENDPOINT, {
    method: "POST",
    headers: { "content-type": "application/json; charset=utf-8" },
    body: JSON.stringify({ host: HOST, key: KEY, keyLocation: KEY_LOCATION, urlList: urls }),
  });
  // 200 = accepted, 202 = accepted, key validation pending. 403 = key file not
  // found/mismatch, 422 = URLs don't belong to host, 429 = slow down.
  console.log(`IndexNow responded ${res.status} ${res.statusText}`);
  if (![200, 202].includes(res.status)) {
    console.error(await res.text());
    process.exit(1);
  }
}

main().catch((e) => {
  console.error(e.message || e);
  process.exit(1);
});
