import { afterEach, describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});

import { ChannelGrid } from "./ChannelGrid";
import { CHANNEL_META, CHANNEL_ORDER, offeredChannelOrder } from "./ChannelInspectorCards";

const UNOFFERED = ["slack", "discord", "teams", "x"];
const KEPT = ["telegram", "signal", "whatsappqr", "imessage", "email"];

function tiles(): string[] {
  const { container, unmount } = render(
    <ChannelGrid cardsByKey={new Map()} selected="whatsapp" selectChannel={() => {}} statusLoaded />
  );
  const keys = Array.from(container.querySelectorAll("[data-testid='channel-card']")).map(
    (n) => n.getAttribute("data-channel") ?? ""
  );
  unmount();
  return keys;
}

afterEach(() => {
  vi.unstubAllEnvs();
  delete window.__ENV;
});

describe("ChannelGrid on a personal install", () => {
  it("drops slack, discord, teams and x and keeps the personal channels", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    const keys = tiles();
    for (const k of UNOFFERED) expect(keys, k).not.toContain(k);
    for (const k of KEPT) expect(keys, k).toContain(k);
    expect(keys[0]).toBe("whatsapp");
    expect(keys[1]).toBe("sms");
  });

  it("still offers every channel on lite and full", () => {
    for (const edition of ["lite", "full"]) {
      vi.stubEnv("NEXT_PUBLIC_EDITION", edition);
      const keys = tiles();
      for (const k of [...UNOFFERED, ...KEPT]) expect(keys, `${edition}:${k}`).toContain(k);
      expect(keys).toHaveLength(CHANNEL_ORDER.length + 1);
      vi.unstubAllEnvs();
    }
  });

  it("offers every channel when no edition is declared", () => {
    expect(tiles()).toHaveLength(CHANNEL_ORDER.length + 1);
  });

  it("hides at render only: CHANNEL_ORDER and CHANNEL_META keep the unoffered channels", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    expect(offeredChannelOrder()).toEqual(CHANNEL_ORDER.filter((k) => !UNOFFERED.includes(k)));
    for (const k of UNOFFERED) {
      expect(CHANNEL_ORDER).toContain(k);
      expect(CHANNEL_META[k]).toBeDefined();
    }
  });
});
