import { chromium } from "@playwright/test";
const b = await chromium.launch(); const c = await b.newContext();
const r = await c.request.post("http://localhost:3000/api/local-auth/login", { data: { identifier: "owner@vegaduta.local", password: "academy-capture-2026" } });
const { accessToken } = await r.json(); const sub = JSON.parse(Buffer.from(accessToken.split(".")[1], "base64url").toString()).sub;
await c.addInitScript(s => { try { localStorage.setItem("vd_tour_status:" + s, "done") } catch {} }, sub);
const p = await c.newPage();
for (const rt of ["/admin/audit","/admin/audit-tail","/home","/admin/contacts","/admin/approvals"]) {
  await p.goto("http://localhost:3000" + rt, { waitUntil: "load" }); await p.waitForTimeout(4000);
  const t = await p.locator("body").innerText();
  const hits = t.split("\n").filter(l => /mroads|\+91|9198|Users\//i.test(l));
  console.log(rt.padEnd(22), hits.length ? "LEAK: " + hits[0].trim().slice(0,80) : "clean");
}
await b.close();
