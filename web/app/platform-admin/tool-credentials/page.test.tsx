// The tool-credentials page is one of the three platform-admin pages a personal install keeps. It must not
// link to /platform-admin/mcp-catalog (a real 404 there) or draw the ElevenLabs voice card (CLOUD_SPEECH is
// off), while lite and full keep both.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";

const api = vi.hoisted(() => ({
  listPlatformToolCredentials: vi.fn(async () => []),
  getWebSearchProviders: vi.fn(async () => null),
}));

vi.mock("../lib/api", () => ({ api }));
vi.mock("../components/AdminShell", () => ({
  default: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
}));
vi.mock("../components/admin/ConfirmDialog", () => ({ useConfirm: () => async () => true }));
vi.mock("../lib/toastBus", () => ({ toast: { error: vi.fn(), success: vi.fn() } }));
vi.mock("next/link", () => ({
  default: ({ children, href }: { children: ReactNode; href: string }) => <a href={href}>{children}</a>,
}));

import ToolCredentialsPage from "./page";

beforeEach(() => vi.clearAllMocks());
afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
});

const mcpCatalogLink = () => document.querySelector("a[href='/platform-admin/mcp-catalog']");

describe("tool credentials page by edition", () => {
  it("has no MCP Catalog link and no ElevenLabs card on a personal install, and keeps the search keys", async () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    render(<ToolCredentialsPage />);
    await waitFor(() => expect(api.listPlatformToolCredentials).toHaveBeenCalled());
    expect(mcpCatalogLink()).toBeNull();
    expect(screen.queryByText("ElevenLabs Voice")).toBeNull();
    expect(screen.getByText("Brave Web Search")).toBeInTheDocument();
  });

  it.each(["lite", "full"])("keeps the MCP Catalog link and the ElevenLabs card on %s", async (tier) => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", tier);
    render(<ToolCredentialsPage />);
    await waitFor(() => expect(api.listPlatformToolCredentials).toHaveBeenCalled());
    expect(mcpCatalogLink()).not.toBeNull();
    expect(screen.getByText("ElevenLabs Voice")).toBeInTheDocument();
  });
});
