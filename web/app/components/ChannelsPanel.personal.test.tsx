import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, waitFor } from "@testing-library/react";
import en from "../../messages/en.json";

vi.mock("../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});

let search = "";
const replace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace, push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(search),
}));

// Every api method is a spy that settles with "nothing there"; the tests ask which ones were called.
const calls: Record<string, ReturnType<typeof vi.fn>> = {};
vi.mock("../lib/api", () => ({
  api: new Proxy(
    {},
    {
      get: (_t, name: string) => {
        if (!calls[name]) {
          calls[name] =
            name === "getChannelsStatus"
              ? vi.fn(() => Promise.resolve({ channels: [] }))
              : vi.fn(() => Promise.resolve(null));
        }
        return calls[name];
      },
    }
  ),
}));

import ChannelsPanel from "./ChannelsPanel";

const UNOFFERED_GETS = ["getSlackCredential", "getDiscordCredential", "getTeamsCredential", "getXCredential"];
const ALL_GETS = [...UNOFFERED_GETS, "getTelegramCredential", "getEmailCredential"];

beforeEach(() => {
  search = "";
  for (const k of Object.keys(calls)) delete calls[k];
});

afterEach(() => {
  vi.unstubAllEnvs();
  delete window.__ENV;
});

describe("ChannelsPanel credential reads", () => {
  it("does not call the slack, discord, teams or x credential endpoints on a personal install", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    render(<ChannelsPanel agents={[]} />);
    await waitFor(() => expect(calls.getTelegramCredential).toHaveBeenCalled());
    await waitFor(() => expect(calls.getChannelsStatus).toHaveBeenCalled());
    expect(calls.getEmailCredential).toHaveBeenCalled();
    // Reading the property creates the spy; what matters is that it was never invoked.
    for (const name of UNOFFERED_GETS) expect(calls[name]?.mock.calls.length ?? 0, name).toBe(0);
  });

  it("ignores a ?channel= deep link to a channel this install does not offer", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    search = "channel=slack";
    const { container } = render(<ChannelsPanel agents={[]} />);
    await waitFor(() => expect(calls.getChannelsStatus).toHaveBeenCalled());
    expect(container.querySelector("[data-channel='slack']")).toBeNull();
    expect(container.querySelector("[data-channel='whatsapp'][aria-pressed='true']")).not.toBeNull();
  });

  it("reads every channel credential on lite, full and an undeclared install", async () => {
    for (const edition of ["lite", "full", ""]) {
      for (const k of Object.keys(calls)) delete calls[k];
      vi.unstubAllEnvs();
      if (edition) vi.stubEnv("NEXT_PUBLIC_EDITION", edition);
      const { unmount } = render(<ChannelsPanel agents={[]} />);
      await waitFor(() => {
        for (const name of ALL_GETS) expect(calls[name], `${edition || "undeclared"}:${name}`).toHaveBeenCalled();
      });
      unmount();
    }
  });
});
