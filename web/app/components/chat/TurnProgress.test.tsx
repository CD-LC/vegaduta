import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const dict = en as unknown as Record<string, Record<string, string>>;
  return {
    useTranslations: (ns: string) => (key: string, vars?: Record<string, unknown>) =>
      (dict[ns]?.[key] ?? `${ns}.${key}`).replace(/\{(\w+)\}/g, (_, k: string) => String(vars?.[k] ?? `{${k}}`)),
  };
});

import TurnProgress from "./TurnProgress";

describe("TurnProgress", () => {
  it("renders nothing before any event or text", () => {
    const { container } = render(<TurnProgress steps={[]} hasText={false} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("names the server phase and keeps a clock", () => {
    render(
      <TurnProgress
        steps={[
          { type: "phase", id: 1, stage: "guardrails", ms: 10 },
          { type: "phase", id: 2, stage: "model", ms: 1200 },
        ]}
        startedAt={Date.now() - 42_000}
        hasText={false}
      />
    );
    const row = screen.getByTestId("turn-progress");
    expect(row).toHaveAttribute("data-phase", "model");
    expect(row).toHaveTextContent("Model is working…");
    expect(row).toHaveTextContent(/42 s/);
  });

  it("shows the freshest reasoning line, and hides it when asked", () => {
    const steps = [
      { type: "thinking", id: 1, message: "First the user " },
      { type: "thinking", id: 2, message: "wants a list." },
    ];
    const { rerender } = render(<TurnProgress steps={steps} hasText={false} />);
    expect(screen.getByTestId("turn-thinking")).toHaveTextContent("First the user wants a list.");
    rerender(<TurnProgress steps={steps} hasText={false} showThinking={false} />);
    expect(screen.queryByTestId("turn-thinking")).toBeNull();
    expect(screen.getByTestId("turn-progress")).toHaveTextContent("Thinking…");
  });

  it("names the tool in flight and offers cancel", () => {
    const onCancel = vi.fn();
    render(<TurnProgress steps={[{ type: "tool_call", id: 1, tool: "web_search" }]} hasText onCancel={onCancel} />);
    expect(screen.getByTestId("turn-progress")).toHaveTextContent("Searching the web…");
    screen.getByRole("button", { name: "Cancel" }).click();
    expect(onCancel).toHaveBeenCalled();
  });
});
