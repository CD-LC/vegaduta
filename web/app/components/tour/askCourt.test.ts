// Ask-the-court: the palette's static "where do I ...?" table.

import { describe, expect, it } from "vitest";
import { ASK_INTENTS, classifyIntent } from "./askCourt";

describe("classifyIntent", () => {
  it.each([
    ["where do I set the whatsapp token", "whatsapp", "/admin/channels", "channel-whatsapp"],
    ["add a secret", "secret", "/secrets", "secret-add"],
    ["connect a tool", "tool", "/tools", "mcp-connect"],
    ["see my runs", "runs", "/jobs", undefined],
    ["change the model", "model", "/agents", "workbench-model"],
    ["edit the system prompt", "prompt", "/agents", "workbench-system-prompt"],
  ])("%s", (q, id, route, tourId) => {
    const m = classifyIntent(q);
    expect(m?.id).toBe(id);
    expect(m?.route).toBe(route);
    expect(m?.tourId).toBe(tourId);
    expect(m?.href).toBe(tourId ? `${route}?tour=field:${tourId}` : route);
  });

  it("prefers the WhatsApp card over the vault on a tie (table order)", () => {
    expect(classifyIntent("whatsapp api key")?.id).toBe("whatsapp");
  });

  it("is case and whitespace tolerant", () => {
    expect(classifyIntent("  ADD   A   SECRET ")?.id).toBe("secret");
  });

  it("leaves one-word queries to the plain nav search", () => {
    expect(classifyIntent("model")).toBeNull();
    expect(classifyIntent("tools")).toBeNull();
  });

  it("returns null for nothing it knows and for non-strings", () => {
    expect(classifyIntent("what is the weather like")).toBeNull();
    expect(classifyIntent(null)).toBeNull();
    expect(classifyIntent(undefined)).toBeNull();
  });

  it("every deep-linked id is a plain data-tour identifier", () => {
    for (const i of ASK_INTENTS) if (i.tourId) expect(i.tourId).toMatch(/^[a-zA-Z0-9_-]+$/);
  });
});
