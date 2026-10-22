import { describe, it, expect, vi, afterEach } from "vitest";
import { render, screen, waitFor, fireEvent, cleanup } from "@testing-library/react";
import AgentWorkbench from "./AgentWorkbench";

vi.mock("../../lib/i18n", async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...(await import("./testI18n")).i18nMock,
  useLocale: () => "en",
}));
// Every api method resolves to an empty object-ish value; the smoke test only proves that the split
// controller + sections mount and wire state together, not any endpoint.
vi.mock("../../lib/api", () => {
  const handler: ProxyHandler<object> = {
    get: (_t, prop): unknown => (prop === "then" ? undefined : () => Promise.resolve([])),
  };
  return { api: new Proxy({}, handler) };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/agents",
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("../templates/ShareAsTemplateButton", () => ({ default: (): null => null }));
vi.mock("./AgentPlayground", () => ({ default: (): unknown => <div data-testid="playground" /> }));

const provider: any = { id: "p1", providerName: "OPENAI", status: "ACTIVE" };

describe("AgentWorkbench (split shell)", () => {
  it("renders the new-agent draft: toolbar, identity section, playground slot", async () => {
    const onSelect = vi.fn();
    render(
      <AgentWorkbench
        agents={[]}
        providers={[provider]}
        collections={[]}
        selectedAgentId={null}
        onSelect={onSelect}
        onChanged={vi.fn()}
        startInDraft
        agentsLoaded
      />
    );
    await waitFor(() => expect(screen.getByTestId("playground")).toBeTruthy());
    expect(document.querySelector("textarea")).toBeTruthy();
    // a typed name flows through the draft reducer into the controlled input
    const nameInput = document.querySelector("input") as HTMLInputElement;
    expect(nameInput).toBeTruthy();
    fireEvent.change(nameInput, { target: { value: "Support bot" } });
    await waitFor(() => expect((document.querySelector("input") as HTMLInputElement).value).toBe("Support bot"));
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("renders a saved agent and keeps the draft-only controls out of the way", async () => {
    const agent: any = {
      id: "a1",
      name: "Saved bot",
      systemPrompt: "Be brief.",
      providerName: "OPENAI",
      model: "gpt-x",
      maxTokens: 512,
      temperature: 0.4,
      topP: 1,
      editable: true,
      allowedKnowledgeCollectionIds: [],
    };
    render(
      <AgentWorkbench
        agents={[agent]}
        providers={[provider]}
        collections={[]}
        selectedAgentId="a1"
        onSelect={vi.fn()}
        onChanged={vi.fn()}
        agentsLoaded
      />
    );
    await waitFor(() => expect(screen.getByTestId("playground")).toBeTruthy());
    expect(screen.getAllByText("Saved bot").length).toBeGreaterThan(0);
  });

  describe("teammate sharing section", () => {
    afterEach(() => {
      cleanup();
      vi.unstubAllEnvs();
    });
    const saved: any = {
      id: "a1",
      name: "Saved bot",
      systemPrompt: "Be brief.",
      providerName: "OPENAI",
      model: "gpt-x",
      maxTokens: 512,
      temperature: 0.4,
      topP: 1,
      editable: true,
      allowedKnowledgeCollectionIds: [],
    };
    const mount = () =>
      render(
        <AgentWorkbench
          agents={[saved]}
          providers={[provider]}
          collections={[]}
          selectedAgentId="a1"
          onSelect={vi.fn()}
          onChanged={vi.fn()}
          agentsLoaded
        />
      );

    it("is offered on a shared install", async () => {
      vi.stubEnv("NEXT_PUBLIC_EDITION", "full");
      mount();
      await waitFor(() => expect(screen.getByText("SHARING")).toBeTruthy());
    });

    it("is not offered on a personal install (one person, no teammates)", async () => {
      vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
      mount();
      await waitFor(() => expect(screen.getByTestId("playground")).toBeTruthy());
      expect(screen.queryByText("SHARING")).toBeNull();
    });
  });
});
