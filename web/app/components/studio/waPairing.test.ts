import { describe, expect, it } from "vitest";
import { safeWaLink, whatsappView } from "./waPairing";
import type { WhatsAppStatus } from "../try/demoClient";

const base: WhatsAppStatus = {
  mode: "LIVE",
  used: false,
  messageCount: 0,
  available: true,
  state: "NONE",
  pairingCode: null,
  deepLink: null,
  businessNumberMasked: "+91 ••••• ••210",
};

describe("whatsappView", () => {
  it("hides for the PREVIEW mode, unavailable, or missing status", () => {
    expect(whatsappView(null, false)).toBe("hidden");
    expect(whatsappView(undefined, false)).toBe("hidden");
    expect(whatsappView({ ...base, mode: "PREVIEW" }, false)).toBe("hidden");
    expect(whatsappView({ ...base, available: false }, false)).toBe("hidden");
  });

  it("is idle until a code exists, then waiting", () => {
    expect(whatsappView(base, false)).toBe("idle");
    const pending = { ...base, state: "PENDING", pairingCode: "VD-7F3K", deepLink: "https://wa.me/919876543210?text=VD-7F3K" };
    expect(whatsappView(pending, false)).toBe("waiting");
    // a code with no usable link must not render a broken QR
    expect(whatsappView({ ...pending, deepLink: "javascript:alert(1)" }, false)).toBe("idle");
  });

  it("shows linked, and hides an idle card once the sandbox has ended", () => {
    expect(whatsappView({ ...base, state: "LINKED", used: true }, false)).toBe("linked");
    expect(whatsappView({ ...base, state: "LINKED", used: true }, true)).toBe("linked");
    expect(whatsappView(base, true)).toBe("hidden");
  });
});

describe("safeWaLink", () => {
  it("accepts only https wa.me links", () => {
    expect(safeWaLink("https://wa.me/919876543210?text=VD-7F3K")).not.toBeNull();
    expect(safeWaLink("https://wa.me/919876543210")).not.toBeNull();
    expect(safeWaLink("http://wa.me/919876543210")).toBeNull();
    expect(safeWaLink("https://evil.example/919876543210")).toBeNull();
    expect(safeWaLink("https://wa.me/919876543210?text=<script>")).toBeNull();
    expect(safeWaLink(null)).toBeNull();
  });
});
