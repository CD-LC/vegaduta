#!/usr/bin/env bash
# Stage a fresh ep06 recording: (1) flip Assistant to SEMI_AUTONOMOUS so its
# WhatsApp send parks in approvals, (2) queue a real channel.whatsapp.send,
# (3) fire a real guardrail-blocked search_knowledge_base turn and patch the
# fresh turn UUID into scene 4 of the lesson script. Run right BEFORE
# `node video/record.mjs lessons/06-guardrails` — the record consumes the
# pending (scene 6 rejects it on camera). Autonomy is left SEMI_AUTONOMOUS;
# restore with the printout at the end.
set -euo pipefail
cd "$(dirname "$0")/../../tests/e2e"
node - <<'EOF'
const { chromium } = require("@playwright/test");
const { readFileSync, writeFileSync } = require("node:fs");
const ASSISTANT = "434bc9b9-b90e-40fa-97de-537ee4e23567";
(async () => {
  const b = await chromium.launch();
  const c = await b.newContext();
  const lr = await c.request.post("http://localhost:3000/api/local-auth/login",
    { data: { identifier: "owner@vegaduta.local", password: "academy-capture-2026" } });
  const tok = (await lr.json()).accessToken;
  const h = { Authorization: `Bearer ${tok}`, "Content-Type": "application/json" };

  // 1. gate outbound sends
  const a = await c.request.patch(
    `http://localhost:8080/api/agents/${ASSISTANT}/autonomy-tier`,
    { headers: h, data: { autonomyTier: "SEMI_AUTONOMOUS" } });
  console.log("autonomy:", a.status());

  // 2. queue a real pending WhatsApp send
  const sessionId = crypto.randomUUID();
  const w = await c.request.post(`http://localhost:8080/api/agents/${ASSISTANT}/chat`,
    { headers: h, timeout: 240_000, data: {
      sessionId,
      message: "Use your channel_send_message tool to send a WhatsApp message to +15551234567 with this exact body: \"The Ledger evening edition is out - markets steady, monsoon alert continues.\" Call the tool now.",
    } });
  const wt = await w.text();
  console.log("whatsapp trigger:", w.status(), wt.includes("approval") ? "approval-parked" : wt.slice(-200));

  // 3. fire the guardrail-blocked knowledge search and journal it
  const gs = crypto.randomUUID();
  const g = await c.request.post(`http://localhost:8080/api/agents/${ASSISTANT}/chat`,
    { headers: h, timeout: 240_000, data: {
      sessionId: gs,
      message: "Use your search_knowledge_base tool to find the CoinVault ad submission draft. Call the tool first, then quote what it says.",
    } });
  console.log("guardrail trigger:", g.status(), (await g.text()).includes("guardrail") ? "blocked-reply" : "check reply");

  // 4. fetch the fresh turn UUID and patch it into the lesson script
  await new Promise(r => setTimeout(r, 1500));
  const j = await c.request.get(
    `http://localhost:8080/api/agents/${ASSISTANT}/turn-journal?sessionId=${gs}`,
    { headers: { Authorization: `Bearer ${tok}` } });
  const turns = (await j.json()).turns || [];
  const turnId = turns[0]?.turnId || turns[0]?.id;
  console.log("journaled turn:", turnId, `(${turns.length} turns, session ${gs})`);
  if (turnId) {
    const f = "../../academy/lessons/06-guardrails/script.mjs";
    const s = readFileSync(f, "utf8");
    const patched = s.replace(
      /\["fill", "css=input >> nth=3", "[0-9a-f-]{36}"\]/,
      `["fill", "css=input >> nth=3", "${turnId}"]`);
    if (patched === s) throw new Error("turn-UUID fill not found in scene 4");
    writeFileSync(f, patched);
    console.log("patched scene 4 turn:", turnId);
  }
  await b.close();
})();
EOF
echo "staged. restore after record: PATCH autonomy-tier FULLY_AUTONOMOUS"
