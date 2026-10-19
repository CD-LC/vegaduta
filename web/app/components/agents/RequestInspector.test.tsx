// Request X-ray: what the model was sent. Locks in the composition bar (one
// segment per message + tool schema), the multi-call picker, and that the
// panel renders nothing for an empty list - it is a bonus surface and must
// never break a chat turn.

import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import RequestInspector, { type LlmRequestSnapshot } from "./RequestInspector";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, v?: Record<string, string | number>) =>
    v ? `${key}:${Object.values(v).join(",")}` : key,
}));

const snap = (id: string, over: Partial<LlmRequestSnapshot> = {}): LlmRequestSnapshot => ({
  id,
  at: "2026-10-01T10:00:00Z",
  provider: "ANTHROPIC",
  model: "claude-sonnet-5-5",
  streaming: true,
  options: { temperature: 0.2, maxTokens: 1024 },
  messages: [
    { role: "system", text: "You are a helpful agent.", chars: 24 },
    { role: "system", text: "Remember: user likes tea.", chars: 25 },
    { role: "user", text: "hello", chars: 5 },
  ],
  tools: [{ name: "web_search", description: "Search the web", inputSchema: '{"type":"object"}' }],
  ...over,
});

describe("RequestInspector", () => {
  it("renders nothing without snapshots", () => {
    const { container } = render(<RequestInspector snapshots={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it("shows one bar segment per message plus the tool definitions, and reveals message text on open", () => {
    render(<RequestInspector snapshots={[snap("a")]} defaultOpen />);
    // 3 messages + tool definitions
    expect(screen.getAllByRole("img")[0].querySelectorAll("button")).toHaveLength(4);
    expect(screen.getByText("systemPrompt")).toBeTruthy();
    fireEvent.click(screen.getByText("userMessage"));
    expect(screen.getByText("hello")).toBeTruthy();
  });

  it("offers a call picker when a turn made several model calls", () => {
    render(<RequestInspector snapshots={[snap("a"), snap("b", { tools: [] })]} defaultOpen />);
    expect(screen.getAllByRole("tab", { name: /callN/ })).toHaveLength(2);
  });
});
