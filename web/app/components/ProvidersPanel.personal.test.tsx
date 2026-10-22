// A personal install runs core without CLOUD_SPEECH: no Sarvam / ElevenLabs controllers exist, so the
// providers page must not draw their cards, read their credentials, or fetch cloned voices (each would be a
// 404). lite, full and an undeclared build keep all of it (see ProvidersPanel.elevenlabs.test.tsx).

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import { refreshPlatformStatus, resetPlatformStatusForTests } from "../lib/platformStatus";

vi.mock("../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("next/link", () => ({
  default: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a>,
}));
vi.mock("./AuthProvider", () => ({ useAuth: () => ({ hasPermission: () => true }) }));

const speechCalls = vi.hoisted(() => ({
  getSarvamCredential: vi.fn(async () => ({ status: "ACTIVE", createdAt: "2026-09-01T00:00:00Z" })),
  getElevenLabsCredential: vi.fn(async () => ({ status: "ACTIVE", createdAt: "2026-09-01T00:00:00Z" })),
  listElevenLabsVoices: vi.fn(async () => []),
}));
vi.mock("../lib/api", () => {
  const empty = vi.fn(async () => null);
  return {
    api: new Proxy(
      {
        getTenantSettings: vi.fn(async () => ({ plan: "PREMIUM", experienceMode: "ADVANCED", usePlatformLlm: false })),
        platformLlm: vi.fn(async () => null),
        getE2bCredential: vi.fn(async () => null),
        ...speechCalls,
      } as Record<string, unknown>,
      { get: (target, key: string) => target[key] ?? empty }
    ),
  };
});

import ProvidersPanel from "./ProvidersPanel";

beforeEach(() => {
  for (const fn of Object.values(speechCalls)) fn.mockClear();
});

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  delete window.__ENV;
});

describe("ProvidersPanel - cloud speech by edition", () => {
  it("offers no Sarvam or ElevenLabs card and calls none of their endpoints on a personal install", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    render(<ProvidersPanel providers={[]} onChanged={() => {}} />);
    // A chat provider card is still there, so the page rendered.
    expect(await screen.findByText("anthropicLabel")).toBeInTheDocument();
    expect(screen.queryByText("sarvamLabel")).toBeNull();
    expect(screen.queryByText("elevenLabsLabel")).toBeNull();
    expect(screen.queryByText("elevenLabsVoicesNeededNote")).toBeNull();
    // The sandbox card is not a speech vendor and stays.
    expect(screen.getByText("e2bLabel")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByText("ACTIVE")).toBeNull());
    expect(speechCalls.getSarvamCredential).not.toHaveBeenCalled();
    expect(speechCalls.getElevenLabsCredential).not.toHaveBeenCalled();
    expect(speechCalls.listElevenLabsVoices).not.toHaveBeenCalled();
  });

  it("offers both cards on a personal install once core reports cloud-speech on (the escape hatch)", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    const reported = (async () => ({
      ok: true,
      json: async () => ({
        edition: { tier: "personal", billingEnabled: false, authMode: "local", features: { "cloud-speech": true } },
      }),
    })) as unknown as typeof fetch;
    try {
      render(<ProvidersPanel providers={[]} onChanged={() => {}} />);
      expect(await screen.findByText("anthropicLabel")).toBeInTheDocument();
      expect(screen.queryByText("sarvamLabel")).toBeNull();
      await act(async () => {
        await refreshPlatformStatus(reported, { force: true });
      });
      expect(await screen.findByText("sarvamLabel")).toBeInTheDocument();
      expect(screen.getByText("elevenLabsLabel")).toBeInTheDocument();
    } finally {
      resetPlatformStatusForTests();
    }
  });

  it.each(["lite", "full"])("keeps both cards and reads their credentials on the %s edition", async (tier) => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", tier);
    render(<ProvidersPanel providers={[]} onChanged={() => {}} />);
    expect(await screen.findByText("sarvamLabel")).toBeInTheDocument();
    expect(screen.getByText("elevenLabsLabel")).toBeInTheDocument();
    expect(speechCalls.getSarvamCredential).toHaveBeenCalled();
    expect(speechCalls.getElevenLabsCredential).toHaveBeenCalled();
    expect(speechCalls.listElevenLabsVoices).toHaveBeenCalled();
  });
});
