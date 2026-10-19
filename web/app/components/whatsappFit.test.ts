import { describe, expect, it } from "vitest";
import { AGENT_TEMPLATES, templateNameKey, templateTaglineKey } from "./agentTemplates";
import {
  WHATSAPP_READY_TEMPLATE_KEYS,
  WHATSAPP_UNSUITABLE_TEMPLATE_KEYS,
  whatsAppFit,
} from "./whatsappFit";
import en from "../../messages/en.json";

const keys = new Set<string>(AGENT_TEMPLATES.map((t) => t.key));
const strings = (en as Record<string, any>).agentTemplates as Record<string, string>;

describe("WhatsApp template fit", () => {
  it("only names templates that exist", () => {
    for (const k of [...WHATSAPP_READY_TEMPLATE_KEYS, ...WHATSAPP_UNSUITABLE_TEMPLATE_KEYS]) {
      expect(keys.has(k), `unknown template ${k}`).toBe(true);
    }
  });

  it("never lists a template as both ready and unsuitable", () => {
    for (const k of WHATSAPP_READY_TEMPLATE_KEYS) expect(WHATSAPP_UNSUITABLE_TEMPLATE_KEYS).not.toContain(k);
  });

  it("marks deep research and the other open-ended assistants unsuitable", () => {
    expect(whatsAppFit("research-assistant")).toBe("unsuitable");
    expect(whatsAppFit("literature-review-assistant")).toBe("unsuitable");
    expect(whatsAppFit("explain-it-simply-tutor")).toBe("unsuitable");
  });

  it("ships the four task-specific templates, customer-facing, with names", () => {
    for (const k of [
      "whatsapp-front-desk",
      "whatsapp-booking-assistant",
      "whatsapp-payment-followup",
      "whatsapp-lead-followup",
    ]) {
      const t = AGENT_TEMPLATES.find((x) => x.key === k) as Record<string, any> | undefined;
      expect(t, k).toBeTruthy();
      expect(whatsAppFit(k)).toBe("ready");
      expect(t!.customerFacing).toBe(true);
      expect(strings[templateNameKey(k)]).toBeTruthy();
      expect(strings[templateTaglineKey(k)]).toBeTruthy();
    }
  });

  it("task-specific prompts carry an explicit scope limit (the server heuristic looks for one)", () => {
    for (const k of WHATSAPP_READY_TEMPLATE_KEYS.filter((x) => x.startsWith("whatsapp-"))) {
      const prompt = ((AGENT_TEMPLATES.find((x) => x.key === k) as Record<string, any>).systemPrompt as string).toLowerCase();
      expect(prompt).toMatch(/politely decline|only (job|discuss|answer)/);
    }
  });

  it("is neutral for everything else", () => {
    expect(whatsAppFit("code-review-assistant")).toBeNull();
  });
});
