import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { FLOW_STORAGE_KEY, START_HERE_DISMISS_KEY } from "./flow";
import StartHereCard from "./StartHereCard";

vi.mock("../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: any) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

describe("StartHereCard", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });
  afterEach(() => {
    cleanup();
  });

  it("links to /start and does not redirect anywhere", () => {
    render(<StartHereCard />);
    const link = screen.getByRole("link", { name: "hereCta" });
    expect(link).toHaveAttribute("href", "/start");
  });

  it("offers to continue when a flow was left part-way", () => {
    window.localStorage.setItem(
      FLOW_STORAGE_KEY,
      JSON.stringify({
        step: "create",
        goal: "draft",
        templateKey: "client-call-to-actions",
        agentId: null,
        startedAt: Date.now() - 30_000,
        prefilled: false,
        firstRunAt: null,
        firstRunSeconds: null,
        fired: ["onboarding_start"],
      })
    );
    render(<StartHereCard />);
    expect(screen.getByRole("link", { name: "hereResume" })).toHaveAttribute("href", "/start");
  });

  it("can be dismissed, and stays dismissed", () => {
    const { unmount } = render(<StartHereCard />);
    fireEvent.click(screen.getByRole("button", { name: "hereDismiss" }));
    expect(screen.queryByTestId("start-here-card")).toBeNull();
    expect(window.localStorage.getItem(START_HERE_DISMISS_KEY)).toBe("1");
    unmount();
    render(<StartHereCard />);
    expect(screen.queryByTestId("start-here-card")).toBeNull();
  });

  it("still renders when storage throws", () => {
    const spy = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    try {
      render(<StartHereCard />);
      expect(screen.getByTestId("start-here-card")).toBeInTheDocument();
    } finally {
      spy.mockRestore();
    }
  });
});
