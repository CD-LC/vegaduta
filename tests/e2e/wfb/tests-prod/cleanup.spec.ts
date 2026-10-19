import { test } from "@playwright/test";
import { signInToConsole } from "../helpers/prod-session";
import { getBearerToken } from "../helpers/prod-api";
test("cleanup", async ({ page }) => {
  await signInToConsole(page);
  const token = await getBearerToken(page);
  const A = `http://api.${process.env.E2E_DOMAIN}`;
  const H = { authorization: `Bearer ${token}`, "content-type": "application/json" };
  const list = await (await page.request.fetch(A + "/api/workflows", { headers: H })).json();
  console.log("ALL NAMES:", list.map((w: any) => w.name.slice(0, 40)).join(" | "));
  for (const w of list) {
    if (/^(e2e-workflow-build-|E2E (NodeCfg|Beh|Adv|Multi))/i.test(w.name) && !/^e2e-(?!workflow-build)/.test(w.name)) {
      const r = await page.request.fetch(`${A}/api/workflows/${w.id}`, { method: "DELETE", headers: H });
      console.log("DEL", w.name.slice(0, 50), r.status());
    }
  }
});
