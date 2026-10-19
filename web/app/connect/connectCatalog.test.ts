import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import { CONNECT_ITEMS, connectState } from "./connectCatalog";

const byId = (id: string) => CONNECT_ITEMS.find((i) => i.id === id)!;

describe("connectState", () => {
  it("reads a channel from the status list", () => {
    const channels = [{ key: "telegram", state: "CONNECTED" }, { key: "whatsappqr", state: "DISCONNECTED" }];
    expect(connectState(byId("telegram"), { channels, googleConnected: null })).toBe("connected");
    expect(connectState(byId("whatsapp"), { channels, googleConnected: null })).toBe("not-connected");
    expect(connectState(byId("slack"), { channels, googleConnected: null })).toBe("unknown");
  });
  it("is unknown until status loads, and for plain pages", () => {
    expect(connectState(byId("telegram"), { channels: null, googleConnected: null })).toBe("unknown");
    expect(connectState(byId("knowledge"), { channels: [], googleConnected: true })).toBe("unknown");
  });
  it("reads Google separately", () => {
    expect(connectState(byId("google"), { channels: null, googleConnected: true })).toBe("connected");
    expect(connectState(byId("google"), { channels: null, googleConnected: false })).toBe("not-connected");
    expect(connectState(byId("google"), { channels: null, googleConnected: null })).toBe("unknown");
  });
});

describe("catalogue", () => {
  it("only links to pages that exist", () => {
    for (const item of CONNECT_ITEMS) {
      const route = item.href.split("?")[0].replace(/^\//, "");
      expect(() => readFileSync(path.resolve(__dirname, "..", route, "page.tsx"))).not.toThrow();
    }
  });
});
