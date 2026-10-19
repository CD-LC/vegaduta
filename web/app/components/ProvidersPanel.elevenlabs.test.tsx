// The ElevenLabs key and cloned voices are server-gated on MANAGE_CHANNELS
// (voice is a channel), not MANAGE_INTEGRATIONS like the rest of this panel,
// so the ElevenLabs card's write actions follow MANAGE_CHANNELS.

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

let mockPermissions: string[] = [];
vi.mock("../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));
vi.mock("next/link", () => ({ default: ({ children, href }: { children: React.ReactNode; href: string }) => <a href={href}>{children}</a> }));
vi.mock("./AuthProvider", () => ({ useAuth: () => ({ hasPermission: (name: string) => mockPermissions.includes(name) }) }));
vi.mock("../lib/api", () => {
  const empty = vi.fn(async () => null);
  return {
    api: new Proxy(
      {
        getTenantSettings: vi.fn(async () => ({ plan: "PREMIUM", experienceMode: "ADVANCED", usePlatformLlm: false })),
        platformLlm: vi.fn(async () => null),
        getElevenLabsCredential: vi.fn(async () => ({ status: "ACTIVE", createdAt: "2026-09-01T00:00:00Z" })),
        listElevenLabsVoices: vi.fn(async () => []),
      } as Record<string, unknown>,
      { get: (target, key: string) => target[key] ?? empty },
    ),
  };
});

import ProvidersPanel from "./ProvidersPanel";

afterEach(() => {
  cleanup();
  mockPermissions = [];
});

describe("ProvidersPanel - ElevenLabs", () => {
  it("hides voice cloning from a member with only MANAGE_INTEGRATIONS", async () => {
    mockPermissions = ["MANAGE_INTEGRATIONS"];
    render(<ProvidersPanel providers={[]} onChanged={() => {}} />);
    await screen.findAllByText("ACTIVE");
    expect(screen.queryByText("elevenLabsVoicesNeededNote")).toBeNull();
  });

  it("offers voice cloning to a holder of MANAGE_CHANNELS", async () => {
    mockPermissions = ["MANAGE_INTEGRATIONS", "MANAGE_CHANNELS"];
    render(<ProvidersPanel providers={[]} onChanged={() => {}} />);
    expect(await screen.findByText("elevenLabsVoicesNeededNote")).toBeInTheDocument();
  });
});
