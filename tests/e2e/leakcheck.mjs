import { chromium } from "@playwright/test";
const b = await chromium.launch(); const c = await b.newContext();
const r = await c.request.post("http://localhost:3000/api/local-auth/login", { data: { identifier: "owner@vegaduta.local", password: "academy-capture-2026" } });
const { accessToken } = await r.json(); const sub = JSON.parse(Buffer.from(accessToken.split(".")[1], "base64url").toString()).sub;
await c.addInitScript(s => { try { localStorage.setItem("vd_tour_status:" + s, "done"); localStorage.setItem("vd_tour_seen_security:" + s, "1"); localStorage.setItem("vd_tour_ended:" + s, "1") } catch {} }, sub);
const p = await c.newPage();
const re = /(mroads|\+91|9198|Users\/)/i;
for (const rt of ["/admin/audit","/admin/contacts","/admin/channels","/admin/approvals","/insights","/home","/agents/turns","/admin/inbox","/jobs","/scheduled","/usage","/agents","/memory","/files","/clips","/data-tables","/admin/audit-tail"]) {
  await p.goto("http://localhost:3000" + rt, { waitUntil: "load" }); await p.waitForTimeout(3200);
  const t = await p.locator("body").innerText();
  const m = t.match(new RegExp(".{30}(mroads|\\\\+91|9198|Users\\/).{70}", "i"));
  console.log(rt.padEnd(22), re.test(t) ? "PERSONAL-DATA: " + (m ? m[0].replace(/\s+/g," ").slice(0,120) : "?") : "clean");
}
await b.close();
