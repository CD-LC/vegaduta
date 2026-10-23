import { afterEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});

import WorkedForBlock, { QUIET_AFTER_MS } from "./WorkedForBlock";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("WorkedForBlock - live activity", () => {
  it("shows a live line from the first moment, before any event arrives", () => {
    render(<WorkedForBlock steps={[]} startedAt={Date.now()} endedAt={null} />);
    expect(screen.getByTestId("live-status")).toHaveTextContent("Getting started…");
    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  it("names what the open tool call is doing and shows it as a chip that opens its details", () => {
    render(
      <WorkedForBlock
        steps={[
          { type: "tool_call", id: 1, tool: "fetch_web_page", args: '{"url":"https://www.example.com/a"}' },
          { type: "tool_result", id: 1, tool: "fetch_web_page", preview: "Example page text", ms: 120 },
          { type: "tool_call", id: 2, tool: "web_search", args: '{"query":"tide times goa"}' },
        ]}
        startedAt={Date.now()}
        endedAt={null}
      />
    );
    expect(screen.getByTestId("live-status")).toHaveTextContent("Searching the web for “tide times goa”…");
    const chips = screen.getByTestId("tool-chips").querySelectorAll("button");
    expect(Array.from(chips).map((c) => c.getAttribute("data-state"))).toEqual(["done", "running"]);
    expect(chips[0]).toHaveTextContent("Fetch web page · example.com");
    fireEvent.click(chips[0]);
    expect(chips[0]).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByTestId("tool-chip-details")).toHaveTextContent("Example page text");
    expect(screen.getByTestId("tool-chip-details")).toHaveTextContent("120 ms");
  });

  it("says the answer is being written once text streams, and nothing for a settled plain answer", () => {
    const { rerender, container } = render(
      <WorkedForBlock steps={[{ type: "phase", id: 1, stage: "model" }]} startedAt={Date.now()} endedAt={null} />
    );
    expect(screen.getByTestId("live-status")).toHaveTextContent("Model is working…");
    rerender(
      <WorkedForBlock
        steps={[
          { type: "tool_call", id: 1, tool: "web_search" },
          { type: "tool_result", id: 1, tool: "web_search" },
        ]}
        startedAt={Date.now()}
        endedAt={null}
        hasText
      />
    );
    expect(screen.getByTestId("live-status")).toHaveTextContent("Writing the answer…");
    rerender(<WorkedForBlock steps={[]} startedAt={1} endedAt={2} hasText />);
    expect(container.firstChild).toBeNull();
  });

  it("notes a long silence, and a heartbeat resets it", () => {
    vi.useFakeTimers();
    const start = Date.now();
    const { rerender } = render(<WorkedForBlock steps={[]} startedAt={start} endedAt={null} lastEventAt={start} />);
    expect(screen.queryByTestId("live-quiet")).toBeNull();
    act(() => {
      vi.advanceTimersByTime(QUIET_AFTER_MS + 2000);
    });
    expect(screen.getByTestId("live-quiet")).toHaveTextContent("still waiting on the model");
    rerender(<WorkedForBlock steps={[]} startedAt={start} endedAt={null} lastEventAt={Date.now()} />);
    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(screen.queryByTestId("live-quiet")).toBeNull();
  });
});
