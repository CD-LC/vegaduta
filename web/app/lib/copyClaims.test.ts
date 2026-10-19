import { describe, it, expect } from "vitest";
import { existsSync, readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { PLATFORM_FACTS, HOSTING_FACTS } from "./platform-facts";
import { COMPETITORS, VEGA_BEHIND } from "../compare/compare-data";

// Public-copy audit (2026-10-03). Each rule below is a claim the code or infra
// contradicted and that read fine on the page, so nothing else would have
// caught it. Numbers come from lib/platform-facts.ts; the Java and terraform
// sources are read only when the checkout has them (the web image build does
// not), and the test says nothing rather than fail when they are absent.

const WEB_ROOT = join(__dirname, "..", "..");
const REPO_ROOT = join(WEB_ROOT, "..");

const read = (rel: string) => readFileSync(join(WEB_ROOT, rel), "utf8");
const readRepo = (rel: string): string | null => {
  const p = join(REPO_ROOT, rel);
  return existsSync(p) ? readFileSync(p, "utf8") : null;
};

/** Enum constant names of a Java enum source (comments stripped). */
function javaEnumValues(src: string): string[] {
  const noComments = src.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
  const open = noComments.indexOf("{");
  let body = noComments.slice(open + 1);
  const semi = body.indexOf(";");
  body = semi >= 0 ? body.slice(0, semi) : body.slice(0, body.lastIndexOf("}"));
  return body
    .split(",")
    .map((s) => /^\s*([A-Z][A-Z0-9_]*)/.exec(s)?.[1])
    .filter((s): s is string => Boolean(s));
}

function strings(node: unknown, path = ""): Array<[string, string]> {
  if (typeof node === "string") return [[path, node]];
  if (node && typeof node === "object") {
    return Object.entries(node as Record<string, unknown>).flatMap(([k, v]) => strings(v, path ? `${path}.${k}` : k));
  }
  return [];
}

const en = JSON.parse(read("messages/en.json"));
const enStrings = strings(en);

describe("channel count", () => {
  it("publishes the ChannelType count (Teams, SMS and Signal, Matrix, iMessage and WhatsApp QR excluded until exercised), not a larger one", () => {
    const src = readRepo("core/omnichannel/src/main/java/com/agenticai/core/omnichannel/session/ChannelType.java");
    if (src) {
      const values = javaEnumValues(src);
      const unexercised = new Set(["TEAMS", "SMS", "SIGNAL", "MATRIX", "IMESSAGE", "WHATSAPP_QR"]);
      expect(values.filter((v) => !unexercised.has(v))).toHaveLength(PLATFORM_FACTS.channels);
    }
    expect(PLATFORM_FACTS.channels).toBe(9);
  });

  it("never says WhatsApp, voice and ten other channels", () => {
    const aiPage = read("app/ai/page.tsx");
    expect(aiPage).not.toMatch(/ten other channels/i);
    expect(aiPage).toContain("PLATFORM_FACTS.channels");
  });
});

describe("workflow node types", () => {
  it("matches WorkflowNodeType", () => {
    const src = readRepo("core/workflow/src/main/java/com/agenticai/core/workflow/WorkflowNodeType.java");
    if (src) expect(javaEnumValues(src)).toHaveLength(PLATFORM_FACTS.workflowNodeTypes);
  });

  it("is the only node-type count in marketing copy", () => {
    const sources: Array<[string, string]> = [
      ["messages/en.json", read("messages/en.json")],
      ["compare-data.ts", read("app/compare/compare-data.ts")],
      ["showtime/script.ts", read("app/components/studio/showtime/script.ts")],
    ];
    for (const [name, text] of sources) {
      for (const m of text.matchAll(/\b(\d+) node types\b/g)) {
        expect(Number(m[1]), `${name}: "${m[0]}"`).toBe(PLATFORM_FACTS.workflowNodeTypes);
      }
      expect(text, name).not.toMatch(/\b(thirteen|fourteen) node types\b/i);
    }
  });
});

describe("speech is not a language count", () => {
  // A string that sets a voice/speech/call word next to 22 or 30 must say it is
  // the console, or that coverage depends on the provider. Sarvam's live-call
  // voice is hard-coded to en-IN (SarvamCallSpeechSynthesizer, the live-call Sarvam tier).
  const speechWord = /\b(voice|speech|text-to-speech|spoken|phone calls?|calls?|speaks?)\b/i;
  const count = /\b(22|30)\+?\s+(scheduled\s+)?(indian\s+)?languages?\b/i;
  const qualified = /console|interface|depend|differs|provider you connect|provider supports/i;

  it("keeps every voice/call string in en.json from implying per-language speech", () => {
    for (const [key, value] of enStrings) {
      const pairs = speechWord.test(value) && count.test(value);
      if (!pairs) continue;
      // Only flag when the number sits within a clause of the speech word.
      const near =
        /\b(voice|speech|text-to-speech|spoken|phone calls?|speaks?)\b[^.]{0,60}\b(22|30)\b|\b(22|30)\+?\s+(scheduled\s+)?(indian\s+)?languages?\b[^.]{0,60}\b(voice|speech|text-to-speech|spoken|phone calls?|speaks?)\b/i;
      if (!near.test(value)) continue;
      expect(qualified.test(value), `${key}: ${value.slice(0, 120)}`).toBe(true);
    }
  });

  it("never says 'any language' or 'own language' about voice, calls or speech", () => {
    const loose =
      /\b(voice|speech|spoken|phone calls?|calls?|speaks?)\b[^.]{0,80}\b(any language|own language|every language)\b|\b(any language|own language|every language)\b[^.]{0,80}\b(voice|speech|spoken|phone calls?|calls?|speaks?)\b/i;
    for (const [key, value] of enStrings) {
      if (!loose.test(value)) continue;
      expect(qualified.test(value), `${key}: ${value.slice(0, 120)}`).toBe(true);
    }
  });

  it("does not name a language count beside 'Voice / Call' in workflow recipes", () => {
    const dir = join(WEB_ROOT, "app", "help", "workflow-recipes");
    for (const name of readdirSync(dir).filter((n) => /^recipes-.*\.tsx$/.test(n))) {
      const text = readFileSync(join(dir, name), "utf8");
      for (const m of text.matchAll(/connectors:\s*\[([^\]]*)\]/g)) {
        if (!/Voice \/ Call/.test(m[1])) continue;
        expect(m[1], `${name}: ${m[1].slice(0, 100)}`).not.toMatch(/\b\d+\+?\s+(Indian\s+)?languages\b/i);
      }
    }
  });

  it("does not describe inbound phone calls as Indian-language or provider-selectable", () => {
    // Twilio ConversationRelay TwiML carries no language / ttsProvider /
    // transcriptionProvider (TwilioVoiceWebhookController), so Sarvam and
    // ElevenLabs speech is not on the inbound phone path.
    // Tutorial copy is split across tutorials-data*.ts modules; check every one.
    const tutorialFiles = readdirSync(join(WEB_ROOT, "app", "tutorials"))
      .filter((n) => /^tutorials-data.*\.ts$/.test(n))
      .map((n) => `app/tutorials/${n}`);
    expect(tutorialFiles.length).toBeGreaterThan(1);
    for (const rel of [...tutorialFiles, "app/ai/ai-data.ts"]) {
      expect(read(rel), rel).not.toMatch(/Indian-language callers|callers will speak Indian languages/i);
    }
  });

  it("keeps the compare pages from claiming Indian-language TTS on calls", () => {
    const text = JSON.stringify([COMPETITORS, VEGA_BEHIND]);
    expect(text).not.toMatch(/Indian-language text-to-speech/i);
    expect(text).toMatch(/currently Indian English/);
  });

  it("records the live-call voice locale in one place", () => {
    expect(PLATFORM_FACTS.liveCallSarvamVoiceLocale).toBe("en-IN");
    // The live-call Sarvam tier (VoiceCallController's second TTS tier) lives with the Sarvam adapters.
    const src = readRepo("core/sarvam/src/main/java/com/agenticai/core/sarvam/SarvamCallSpeechSynthesizer.java");
    if (src) expect(src).toContain(`synthesize(text, "${PLATFORM_FACTS.liveCallSarvamVoiceLocale}"`);
  });
});

describe("how production is run", () => {
  it("does not describe production observability as Prometheus/Grafana/Loki/Alertmanager", () => {
    for (const [key, value] of enStrings) {
      expect(value, key).not.toMatch(/\b(Prometheus|Loki|Alertmanager)\b/);
    }
    expect(read("app/why-vegaduta/page.tsx")).not.toMatch(/\b(Prometheus|Loki|Alertmanager)\b/);
  });

  it("agrees with the terraform it was read from", () => {
    // The module's reliability options (2026-10-04) are variables, so the claim is pinned to their DEFAULTS:
    // a plain apply must still mean 7-day backups, no geo-redundant copy and no HA standby, which is what the
    // public copy says. HA is a dynamic block that only renders when the mode is not "Disabled".
    const vars = readRepo("infra/terraform/modules/azure-database/variables.tf");
    const db = readRepo("infra/terraform/modules/azure-database/main.tf");
    if (vars && db) {
      const defaultOf = (name: string): string | null => {
        const m = vars.match(new RegExp('variable "' + name + '"[\\s\\S]*?default\\s*=\\s*([^\\n]+)'));
        return m ? m[1].trim() : null;
      };
      expect(defaultOf("backup_retention_days")).toBe(String(HOSTING_FACTS.backupRetentionDays));
      expect(defaultOf("geo_redundant_backup_enabled")).toBe("false");
      expect(defaultOf("high_availability_mode")).toBe('"Disabled"');
      expect(db).toMatch(/dynamic\s+"high_availability"/);
    }
    const main = readRepo("infra/terraform/environments/azure-phase1/variables.tf");
    if (main) expect(main).toMatch(/variable "location"[\s\S]{0,60}default\s*=\s*"centralindia"/);
  });

  it("says one region, one database server and a seven-day backup window on /why-vegaduta", () => {
    const page = read("app/why-vegaduta/page.tsx");
    expect(HOSTING_FACTS.regions).toBe(1);
    expect(HOSTING_FACTS.databaseStandby).toBe(false);
    expect(page).toMatch(/single Azure\s+region/);
    expect(page).toMatch(/no standby/);
    expect(page).toContain(HOSTING_FACTS.backupRetentionDays === 7 ? "seven days" : "<update the copy>");
  });
});

describe("reinforcement learning is described as experimental", () => {
  it("names no algorithm and says it is off by default", () => {
    const body: string = en.landing.atlas_learning_body;
    expect(body).not.toMatch(/Thompson|bandit|veto|reward hacking/i);
    expect(body).toMatch(/experimental/i);
    expect(body).toMatch(/off by default/i);
  });

  it("holds for every learning / self-tuning string, rendered or not", () => {
    const learning = enStrings.filter(([key]) =>
      /(^|\.)(atlas_learning_body|underneath_learning_body|platform_p3_[a-z0-9]+)$/.test(key)
    );
    expect(learning.length).toBeGreaterThanOrEqual(6);
    for (const [key, value] of learning) {
      expect(value, key).not.toMatch(/Thompson|bandit|veto|reward hacking|best-performing/i);
    }
    for (const key of ["atlas_learning_body", "underneath_learning_body", "platform_p3_body"]) {
      const value = learning.find(([k]) => k.endsWith(key))?.[1] ?? "";
      expect(value, key).toMatch(/experimental/i);
      expect(value, key).toMatch(/off by default/i);
    }
  });
});

describe("built-in tool and content counts", () => {
  it("quotes the tool floor from platform-facts, not the stale 48", () => {
    for (const [key, value] of enStrings) {
      expect(value, key).not.toMatch(/\b48 built-in tools\b/);
    }
    expect(en.landing.heroUniversal_plateHandsBody).toContain(`${PLATFORM_FACTS.builtInToolsFloor}+ built-in tools`);
    expect(en.hire.cv_skill2).toContain(`${PLATFORM_FACTS.builtInToolsFloor}+ built-in tools`);
  });

  it("quotes the Academy lesson and MCP registry floors from platform-facts", () => {
    expect(en.hire.cv_edu3).toContain(`${PLATFORM_FACTS.academyLessonsFloor}+ lessons`);
    expect(en.hire.cv_skill2).toContain(
      `${PLATFORM_FACTS.mcpRegistryEntriesFloor.toLocaleString("en-US")}+ MCP registry entries`
    );
  });
});

describe("plugins are not claimed as store listings", () => {
  it("states where the VS Code, JetBrains and Chrome plugins are installed from", () => {
    const page = read("app/why-vegaduta/page.tsx");
    expect(page).toMatch(/our own plugins page/);
    // No negative about the stores: the Chrome Web Store outcome (submitted
    // 2026-08-19) is unrecorded, so the page must not assert one.
    expect(page).not.toMatch(/not yet on the public marketplaces/);
    expect(page).not.toMatch(/put the same agents inside/);
  });
});
