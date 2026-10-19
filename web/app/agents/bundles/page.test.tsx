// /agents/bundles: preview before import, nothing created until the person confirms.
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { readFileSync } from "node:fs";
import path from "node:path";
import en from "../../../messages/en.json";

const api = vi.hoisted(() => ({ previewAgentBundle: vi.fn(), importAgentBundle: vi.fn() }));
const router = vi.hoisted(() => ({ push: vi.fn() }));
vi.mock("../../lib/api", () => ({ api }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/agents/bundles" }));
vi.mock("../../components/AppShell", () => ({ default: ({ children }: { children?: ReactNode }) => <div>{children}</div> }));
vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const value = key.split(".").reduce<unknown>((o, k) => (o as Record<string, unknown> | undefined)?.[k], (en as unknown as Record<string, unknown>)[namespace]);
    if (typeof value !== "string") return `${namespace}.${key}`;
    return value.replace(/\{(\w+)\}/g, (m, n) => (values && n in values ? String(values[n]) : m));
  },
}));

import AgentBundlesPage from "./page";

const personal = readFileSync(path.resolve(__dirname, "../../../public/agent-bundles/personal-assistant.json"), "utf8");

beforeEach(() => {
  api.previewAgentBundle.mockResolvedValue({ title: "Personal Assistant", tools: ["fetch_web_page", "request_human"], autonomyTier: "SUPERVISED", adjustments: [] });
  api.importAgentBundle.mockResolvedValue({ kind: "AGENT", resourceId: "agent-9" });
  vi.stubGlobal("fetch", vi.fn(async () => ({ ok: true, text: async () => personal })));
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

describe("/agents/bundles", () => {
  it("previews a ready-made agent first and creates nothing yet", async () => {
    render(<AgentBundlesPage />);
    fireEvent.click(screen.getByTestId("ready-personal-assistant").querySelector("button")!);
    await waitFor(() => expect(screen.getByTestId("bundle-preview")).toBeTruthy());
    expect(screen.getByText("fetch_web_page")).toBeTruthy();
    expect(api.previewAgentBundle).toHaveBeenCalledTimes(1);
    expect(api.importAgentBundle).not.toHaveBeenCalled();
  });

  it("imports after confirmation, with the chosen name, then opens the agent", async () => {
    render(<AgentBundlesPage />);
    fireEvent.click(screen.getByTestId("ready-personal-assistant").querySelector("button")!);
    await waitFor(() => screen.getByTestId("bundle-preview"));
    fireEvent.change(screen.getByLabelText("Agent name"), { target: { value: "My helper" } });
    fireEvent.click(screen.getByRole("button", { name: "Import agent" }));
    await waitFor(() => expect(router.push).toHaveBeenCalledWith("/agents?agentId=agent-9"));
    expect(api.importAgentBundle.mock.calls[0][0].title).toBe("My helper");
  });

  it("shows what the server changed for safety", async () => {
    api.previewAgentBundle.mockResolvedValue({ title: "X", tools: [], autonomyTier: "SUPERVISED", adjustments: ["FULLY_AUTONOMOUS lowered to SUPERVISED"] });
    render(<AgentBundlesPage />);
    fireEvent.click(screen.getByTestId("ready-personal-assistant").querySelector("button")!);
    await waitFor(() => screen.getByTestId("bundle-adjustments"));
    expect(screen.getByText(/FULLY_AUTONOMOUS lowered/)).toBeTruthy();
  });

  it("rejects a pasted file that is not an agent file without calling the server", async () => {
    render(<AgentBundlesPage />);
    fireEvent.change(screen.getByPlaceholderText("Paste the agent file here"), { target: { value: "{not json" } });
    fireEvent.click(screen.getAllByRole("button", { name: "Preview" }).at(-1)!);
    await waitFor(() => expect(screen.getByTestId("bundle-error").textContent).toContain("not valid JSON"));
    expect(api.previewAgentBundle).not.toHaveBeenCalled();
  });

  it("shows the server's message when it refuses a bundle", async () => {
    api.previewAgentBundle.mockRejectedValue({ status: 422, message: "The agent has no system prompt." });
    render(<AgentBundlesPage />);
    fireEvent.click(screen.getByTestId("ready-personal-assistant").querySelector("button")!);
    await waitFor(() => expect(screen.getByTestId("bundle-error").textContent).toContain("no system prompt"));
    expect(screen.queryByTestId("bundle-preview")).toBeNull();
  });
});
