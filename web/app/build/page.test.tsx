// The /build surface contract (2026-10): the Agent builder ALWAYS opens first.
// VegaDūta is agentic AI - the custom agent is the starting point and apps are
// the high-ceiling outcome it powers, so the last tab used is deliberately NOT
// remembered: a stale vd.build.surface value and a remount must both land on
// "create". Heavy children and the optional app-builder service are mocked;
// the Tabs primitives stay real because tab order and selection are what's
// under test.

import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";

vi.mock("../components/AppShell", () => ({
  default: ({ children, title }: { children?: ReactNode; title?: string }) => (
    <div>
      <h1>{title}</h1>
      {children}
    </div>
  ),
}));

vi.mock("../components/RouteTransition", () => ({
  usePageReady: (): void => undefined,
}));

vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

// Optional service: unconfigured here, so no network can fire.
vi.mock("./lib", () => ({
  appBuilder: {
    health: () => Promise.resolve({}),
    listTemplates: () => Promise.resolve([]),
    listProjects: () => Promise.resolve([]),
    getGithubStatus: () => Promise.resolve({ connected: false }),
  },
  APP_BUILDER_URL: "",
  APP_BUILDER_CONFIGURED: false,
  onWarming: (): (() => void) => () => undefined,
  newProjectId: (): string => "app-test",
}));

vi.mock("./CreateSurface", () => ({
  default: () => <div data-testid="create-surface" />,
}));

vi.mock("./studio/StudioSurface", () => ({
  default: () => <div data-testid="studio-surface" />,
}));

vi.mock("./SandboxPreview", () => ({ default: (): null => null }));
vi.mock("./SketchPad", () => ({ default: (): null => null }));
vi.mock("./designInputs", () => ({
  composeContext: (): string => "",
  designInputFromFile: (): Promise<null> => Promise.resolve(null),
  serializeSketch: (): string => "",
}));
vi.mock("./edgeFiles", () => ({ generateFilesOnDevice: vi.fn() }));

import AppBuilderPage from "./page";

describe("AppBuilderPage surfaces", () => {
  beforeEach(() => {
    // A stale saved tab must have no effect: /build opens on the agent.
    window.localStorage.setItem("vd.build.surface", "studio");
  });

  afterEach(() => {
    cleanup();
    window.localStorage.clear();
  });

  it("always opens on the Agent builder, leads with it, and returns to it on remount", () => {
    const view = render(<AppBuilderPage />);

    // Initial surface is the agent builder even though storage says "studio".
    expect(screen.getByTestId("create-surface")).toBeInTheDocument();
    expect(screen.queryByTestId("studio-surface")).toBeNull();

    // Tab order is the story: agent first, studio second. No app-builder service in this mock,
    // so the "App projects" tab is left out rather than opening on a not-available notice.
    const tabs = screen.getAllByRole("tab").map((t) => t.textContent);
    expect(tabs[0]).toContain("Agent builder");
    expect(tabs[0]).toContain("Start here");
    expect(tabs[1]).toContain("AI app studio");
    expect(tabs).toHaveLength(2);
    expect(screen.getByRole("tab", { name: /Agent builder/ })).toHaveAttribute(
      "aria-selected",
      "true",
    );

    // In-mount navigation still works.
    fireEvent.click(screen.getByRole("tab", { name: /AI app studio/ }));
    expect(screen.getByTestId("studio-surface")).toBeInTheDocument();
    expect(screen.queryByTestId("create-surface")).toBeNull();

    // Remount resets to the agent - the last tab is never persisted.
    view.unmount();
    render(<AppBuilderPage />);
    expect(screen.getByTestId("create-surface")).toBeInTheDocument();
    expect(screen.queryByTestId("studio-surface")).toBeNull();
  });
});
