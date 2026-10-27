import { chromium } from "@playwright/test";
const b = await chromium.launch(); const c = await b.newContext();
const r = await c.request.post("http://localhost:3000/api/local-auth/login", { data: { identifier: "owner@vegaduta.local", password: "academy-capture-2026" } });
const { accessToken } = await r.json(); const sub = JSON.parse(Buffer.from(accessToken.split(".")[1], "base64url").toString()).sub;
await c.addInitScript(s => { try { localStorage.setItem("vd_tour_status:" + s, "done") } catch {} }, sub);
const p = await c.newPage();
for (const rt of ["/admin/audit","/admin/contacts","/home"]) {
  await p.goto("http://localhost:3000" + rt, { waitUntil: "load" }); await p.waitForTimeout(3500);
  const t = await p.locator("body").innerText();
  for (const line of t.split("\n")) {
    if (/mroads|\+91|9198|Users\//i.test(line)) console.log(rt, ">>", line.trim().slice(0, 130));
  }
}
await b.close();
