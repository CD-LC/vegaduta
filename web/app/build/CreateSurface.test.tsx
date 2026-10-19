// The describe-stage contract: the agent-first flight path is on screen and
// the "A custom AI agent" radio is selected before anything else. Services are
// mocked at their module boundary; nothing here builds or fetches.

import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("../lib/api", () => ({
  api: {
    listProviders: () => Promise.resolve([]),
    platformLlm: () => Promise.resolve(null),
  },
}));

vi.mock("../lib/build/edgeDraft", () => ({
  draftWithEdge: vi.fn(),
  edgeStatus: () => Promise.resolve(null),
}));

vi.mock("./lib", () => ({
  appBuilder: { createProject: (): Promise<never> => Promise.reject(new Error("not under test")) },
  APP_BUILDER_URL: "",
  APP_BUILDER_CONFIGURED: false,
  newProjectId: (): string => "app-test",
}));

vi.mock("./components/GithubConnectCard", () => ({ default: (): null => null }));

import CreateSurface from "./CreateSurface";

describe("CreateSurface describe stage", () => {
  it("shows the agent-first flight path and pre-selects the custom agent", () => {
    render(<CreateSurface onOpenApp={(): void => undefined} />);

    expect(screen.getByText(/Build the agent behind what comes next/)).toBeInTheDocument();
    const path = screen.getByTestId("agent-first-flight-path");
    for (const stage of ["Custom agent", "Workflow", "AI-powered app"]) {
      expect(path.textContent).toContain(stage);
    }

    const agent = screen.getByRole("radio", { name: /A custom AI agent/ });
    expect(agent).toHaveAttribute("aria-checked", "true");
    expect(screen.getByRole("radio", { name: /An app or website/ })).toHaveAttribute(
      "aria-checked",
      "false",
    );
  });
});
