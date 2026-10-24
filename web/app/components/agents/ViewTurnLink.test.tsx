import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

vi.mock("../../lib/i18n", async () => {
  const en = (await import("../../../messages/en.json")).default as unknown as Record<string, Record<string, string>>;
  return { useTranslations: (ns: string) => (key: string) => en[ns]?.[key] ?? `${ns}.${key}` };
});

import ViewTurnLink, { turnTimelineHref } from "./ViewTurnLink";

describe("ViewTurnLink", () => {
  it("links to the session's turn timeline", () => {
    render(<ViewTurnLink agentId="a-1" sessionId="s 1/x" />);
    const link = screen.getByRole("link", { name: "View turn" });
    expect(link).toHaveAttribute("href", "/agents/turns?agentId=a-1&sessionId=s+1%2Fx");
  });

  it("links straight to a turn when its id is known", () => {
    expect(turnTimelineHref("a-1", "s-1", "t-1")).toBe("/agents/turns?agentId=a-1&sessionId=s-1&turnId=t-1");
    expect(turnTimelineHref("a-1", null, "t-1")).toBe("/agents/turns?agentId=a-1&turnId=t-1");
  });

  it("renders nothing without an agent and a session", () => {
    const { container, rerender } = render(<ViewTurnLink agentId="a-1" sessionId={null} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<ViewTurnLink agentId={null} sessionId="s-1" />);
    expect(container).toBeEmptyDOMElement();
  });
});
