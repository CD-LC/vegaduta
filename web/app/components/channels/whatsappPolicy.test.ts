import { describe, expect, it } from "vitest";
import { WHATSAPP_TASKS, qualityTone, whatsAppTaskLabelKey } from "./whatsappPolicy";
import en from "../../../messages/en.json";

describe("whatsappPolicy", () => {
  it("has the six declared tasks, each with an English label", () => {
    expect(WHATSAPP_TASKS).toHaveLength(6);
    const channels = (en as Record<string, any>).channelsPanel as Record<string, string>;
    for (const t of WHATSAPP_TASKS) expect(channels[whatsAppTaskLabelKey(t)], t).toBeTruthy();
  });

  it("reads Meta's documented rating values", () => {
    expect(qualityTone("GREEN")).toBe("good");
    expect(qualityTone("NA")).toBe("unknown");
    expect(qualityTone("UNKNOWN")).toBe("unknown");
    expect(qualityTone(null)).toBe("unknown");
    expect(qualityTone("RED")).toBe("other");
  });
});
