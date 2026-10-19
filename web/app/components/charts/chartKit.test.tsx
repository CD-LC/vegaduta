// Render tests for the chart kit. The translator is backed by the real
// messages/en.json `charts` namespace, so a key the kit uses but en.json lacks
// shows up here as its raw path and fails the text assertions.

import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string, values?: Record<string, unknown>) => {
    const template = (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key];
    if (typeof template !== "string") return `${namespace}.${key}`;
    return template.replace(/\{(\w+)\}/g, (match, name) => (values && name in values ? String(values[name]) : match));
  },
}));

import { ChartFrame, MiniBars, RankedBars, RunStrip, StackedStatusBar, SuccessRing } from "./index";

const SUMMARY = "42 of 50 runs succeeded this week.";

function noNaN(container: HTMLElement) {
  expect(container.innerHTML).not.toMatch(/NaN|Infinity/);
}

describe("ChartFrame", () => {
  const table = {
    columns: [
      { key: "day", header: "Day" },
      { key: "runs", header: "Runs", align: "right" as const },
    ],
    rows: [
      { id: "mon", day: "Mon", runs: 3 },
      { id: "tue", day: "Tue", runs: 5 },
    ],
  };
  const bars = [
    { label: "Mon", value: 3 },
    { label: "Tue", value: 5 },
  ];

  it("shows the title and summary, and gives the chart the summary as its aria-label", () => {
    render(
      <ChartFrame title="Runs" summary={SUMMARY}>
        <MiniBars data={bars} />
      </ChartFrame>
    );
    expect(screen.getByRole("heading", { name: "Runs" })).toBeInTheDocument();
    expect(screen.getByText(SUMMARY)).toBeInTheDocument();
    expect(screen.getByRole("img")).toHaveAttribute("aria-label", SUMMARY);
  });

  it("has no table toggle without table data", () => {
    render(
      <ChartFrame title="Runs" summary={SUMMARY}>
        <MiniBars data={bars} />
      </ChartFrame>
    );
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("toggles between the chart and the same data as a table", () => {
    render(
      <ChartFrame title="Runs" summary={SUMMARY} table={table}>
        <MiniBars data={bars} />
      </ChartFrame>
    );
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "View as table" }));

    const grid = screen.getByRole("table");
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
    expect(within(grid).getAllByRole("columnheader").map((th) => th.textContent)).toEqual(["Day", "Runs"]);
    expect(within(grid).getAllByRole("row")).toHaveLength(3);
    expect(within(grid).getByText("Tue")).toBeInTheDocument();
    expect(within(grid).getByText("5")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "View as chart" }));
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.getByRole("img")).toHaveAttribute("aria-label", SUMMARY);
  });

  it("can start in table view", () => {
    render(
      <ChartFrame title="Runs" summary={SUMMARY} table={table} defaultView="table">
        <MiniBars data={bars} />
      </ChartFrame>
    );
    expect(screen.getByRole("table")).toBeInTheDocument();
  });

  it("renders the period switch slot", () => {
    render(
      <ChartFrame title="Runs" summary={SUMMARY} periodSwitch={<span>7d / 30d</span>}>
        <MiniBars data={bars} />
      </ChartFrame>
    );
    expect(screen.getByText("7d / 30d")).toBeInTheDocument();
  });

  it("empty: shows the built-in empty state with the CTA and never the chart", () => {
    const { container } = render(
      <ChartFrame title="Runs" summary={SUMMARY} empty emptyAction={<a href="/agents">Run an agent</a>} table={table}>
        <div data-testid="chart-body" />
      </ChartFrame>
    );
    expect(screen.getByText("No data yet")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Run an agent" })).toBeInTheDocument();
    expect(screen.queryByTestId("chart-body")).not.toBeInTheDocument();
    expect(screen.queryByText(SUMMARY)).not.toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
    expect(container.querySelector("svg[role='img']")).toBeNull();
  });

  it("empty: is inferred from a table with zero rows, and takes a custom title", () => {
    render(
      <ChartFrame title="Runs" summary={SUMMARY} table={{ columns: table.columns, rows: [] }} emptyTitle="No runs this week" emptyDescription="Runs appear here.">
        <div data-testid="chart-body" />
      </ChartFrame>
    );
    expect(screen.getByText("No runs this week")).toBeInTheDocument();
    expect(screen.getByText("Runs appear here.")).toBeInTheDocument();
    expect(screen.queryByTestId("chart-body")).not.toBeInTheDocument();
  });

  it("loading: shows a busy placeholder instead of the chart", () => {
    const { container } = render(
      <ChartFrame title="Runs" summary={SUMMARY} loading table={table}>
        <div data-testid="chart-body" />
      </ChartFrame>
    );
    expect(container.querySelector("[aria-busy='true']")).not.toBeNull();
    expect(screen.queryByTestId("chart-body")).not.toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("error: shows the error as an alert instead of the chart", () => {
    render(
      <ChartFrame title="Runs" summary={SUMMARY} error="Could not load runs.">
        <div data-testid="chart-body" />
      </ChartFrame>
    );
    expect(screen.getByRole("alert")).toHaveTextContent("Could not load runs.");
    expect(screen.queryByTestId("chart-body")).not.toBeInTheDocument();
  });
});

describe("MiniBars", () => {
  const data = [
    { label: "Mon", value: 2 },
    { label: "Tue", value: 4 },
    { label: "Wed", value: 1 },
  ];

  it("renders one focusable, titled bar per datum", () => {
    const { container } = render(<MiniBars data={data} ariaLabel="Runs per day" />);
    const svg = screen.getByRole("img");
    expect(svg).toHaveAttribute("aria-label", "Runs per day");
    expect(svg.getAttribute("viewBox")).toBe("0 0 30 100");
    const rects = container.querySelectorAll("rect[data-bar]");
    expect(rects).toHaveLength(3);
    rects.forEach((rect) => expect(rect.getAttribute("tabindex")).toBe("0"));
    expect([...rects].map((rect) => rect.querySelector("title")?.textContent)).toEqual(["Mon: 2", "Tue: 4", "Wed: 1"]);
    noNaN(container);
  });

  it("marks the last bar as partial: hatched, dashed, and labelled by the caller", () => {
    const { container } = render(<MiniBars data={data} partialLast partialLabel="so far" ariaLabel="Runs" />);
    const rects = container.querySelectorAll("rect[data-bar]");
    const last = rects[2];
    expect(last.getAttribute("data-partial")).toBe("true");
    expect(last.getAttribute("fill")).toMatch(/^url\(#mini-bars-hatch-/);
    expect(last.getAttribute("stroke-dasharray")).toBeTruthy();
    expect(last.querySelector("title")?.textContent).toBe("Wed: 1 (so far)");
    expect(rects[0].getAttribute("data-partial")).toBeNull();
    expect(rects[0].getAttribute("fill")).toBe("var(--primary)");
    expect(screen.getByText("Wed (so far)")).toBeInTheDocument();
  });

  it("shows the value readout on focus and hover", () => {
    const { container } = render(<MiniBars data={data} ariaLabel="Runs" />);
    const readout = container.querySelector("[data-bar-readout]")!;
    const rects = container.querySelectorAll("rect[data-bar]");
    expect(readout.textContent).toBe("");
    fireEvent.focus(rects[1]);
    expect(readout.textContent).toBe("Tue: 4");
    fireEvent.blur(rects[1]);
    expect(readout.textContent).toBe("");
    fireEvent.mouseEnter(rects[0]);
    expect(readout.textContent).toBe("Mon: 2");
  });

  it("uses the caller's value text when given", () => {
    const { container } = render(<MiniBars data={[{ label: "Mon", value: 1234, valueText: "1,234 runs" }]} ariaLabel="Runs" />);
    expect(container.querySelector("title")?.textContent).toBe("Mon: 1,234 runs");
  });

  it("renders nothing for no data - no empty axis", () => {
    const { container } = render(<MiniBars data={[]} ariaLabel="Runs" />);
    expect(container).toBeEmptyDOMElement();
  });

  it("survives a single point, all zeros and dirty values without NaN", () => {
    for (const series of [[5], [0, 0, 0], [NaN, -4, Infinity, 3]]) {
      const { container, unmount } = render(
        <MiniBars data={series.map((value, i) => ({ label: `d${i}`, value }))} ariaLabel="Runs" />
      );
      expect(container.querySelectorAll("rect[data-bar]")).toHaveLength(series.length);
      noNaN(container);
      unmount();
    }
  });
});

describe("SuccessRing", () => {
  it("renders the rate in the centre with its text label and status", () => {
    const { container } = render(
      <SuccessRing rate={0.84} label="Success rate" statusLabel="Healthy" icon={<svg data-testid="ring-icon" aria-hidden="true" />} ariaLabel={SUMMARY} />
    );
    expect(screen.getByRole("img")).toHaveAttribute("aria-label", SUMMARY);
    expect(container.querySelector("[data-ring-value]")?.textContent).toBe("84%");
    expect(screen.getByText("Success rate")).toBeInTheDocument();
    expect(screen.getByText("Healthy")).toBeInTheDocument();
    expect(screen.getByTestId("ring-icon")).toBeInTheDocument();
    expect(container.querySelector("[data-ring-arc]")?.getAttribute("stroke")).toBe("var(--state-success)");
    noNaN(container);
  });

  it("null rate renders the dash state - not 0% and not 100%", () => {
    for (const missing of [null, undefined, NaN]) {
      const { container, unmount } = render(<SuccessRing rate={missing} label="Success rate" ariaLabel="No runs yet" />);
      expect(container.querySelector("[data-ring-state]")?.getAttribute("data-ring-state")).toBe("none");
      expect(container.querySelector("[data-ring-value]")?.textContent).toBe("-");
      expect(container.querySelector("[data-ring-arc]")).toBeNull();
      expect(container.textContent).not.toMatch(/%/);
      noNaN(container);
      unmount();
    }
  });

  it("uses the caller's null placeholder", () => {
    const { container } = render(<SuccessRing rate={null} label="Success rate" nullText="n/a" ariaLabel="x" />);
    expect(container.querySelector("[data-ring-value]")?.textContent).toBe("n/a");
  });

  it("a real 0% is drawn as 0%, distinct from no data, and out-of-range rates are clamped", () => {
    const zero = render(<SuccessRing rate={0} label="Success rate" ariaLabel="x" />);
    expect(zero.container.querySelector("[data-ring-value]")?.textContent).toBe("0%");
    expect(zero.container.querySelector("[data-ring-state]")?.getAttribute("data-ring-state")).toBe("value");
    zero.unmount();
    const over = render(<SuccessRing rate={3} label="Success rate" state="warning" ariaLabel="x" />);
    expect(over.container.querySelector("[data-ring-value]")?.textContent).toBe("100%");
    expect(over.container.querySelector("[data-ring-arc]")?.getAttribute("stroke")).toBe("var(--state-warning)");
  });

  it("takes its aria-label from the enclosing ChartFrame summary", () => {
    render(
      <ChartFrame title="Reliability" summary={SUMMARY}>
        <SuccessRing rate={0.84} label="Success rate" />
      </ChartFrame>
    );
    expect(screen.getByRole("img")).toHaveAttribute("aria-label", SUMMARY);
  });
});

describe("StackedStatusBar", () => {
  const segments = [
    { label: "Succeeded", count: 42, state: "success" as const },
    { label: "Failed", count: 6, state: "error" as const },
    { label: "Waiting", count: 0, state: "queued" as const },
    { label: "Custom", count: 2, color: "var(--ax-violet)" },
  ];

  it("draws a segment per non-zero count and lists every segment as text", () => {
    const { container } = render(<StackedStatusBar segments={segments} ariaLabel={SUMMARY} showPercent />);
    expect(screen.getByRole("img")).toHaveAttribute("aria-label", SUMMARY);
    const rects = container.querySelectorAll("rect[data-segment]");
    expect([...rects].map((rect) => rect.getAttribute("fill"))).toEqual([
      "var(--state-success)",
      "var(--state-error)",
      "var(--ax-violet)",
    ]);
    const items = within(screen.getByRole("list")).getAllByRole("listitem");
    expect(items.map((li) => li.textContent)).toEqual(["Succeeded4284%", "Failed612%", "Waiting00%", "Custom24%"]);
    noNaN(container);
  });

  it("renders nothing when every count is zero or invalid", () => {
    const { container } = render(
      <StackedStatusBar segments={[{ label: "A", count: 0 }, { label: "B", count: NaN }, { label: "C", count: -2 }]} ariaLabel="x" />
    );
    expect(container).toBeEmptyDOMElement();
    expect(render(<StackedStatusBar segments={[]} ariaLabel="x" />).container).toBeEmptyDOMElement();
  });

  it("handles a single segment", () => {
    const { container } = render(<StackedStatusBar segments={[{ label: "Succeeded", count: 3, state: "success" }]} ariaLabel="x" />);
    expect(container.querySelector("rect[data-segment]")?.getAttribute("width")).toBe("400");
    noNaN(container);
  });
});

describe("RunStrip", () => {
  const runs = [
    { id: "1", status: "success" as const, title: "Run 1: succeeded" },
    { id: "2", status: "error" as const, title: "Run 2: failed" },
    { id: "3", status: "running" as const, title: "Run 3: running" },
  ];

  it("is a named list with one titled mark per run, in order", () => {
    render(<RunStrip runs={runs} label="last 20 runs" />);
    const list = screen.getByRole("list", { name: "last 20 runs" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(3);
    const marks = within(list).getAllByRole("img");
    expect(marks.map((mark) => mark.getAttribute("aria-label"))).toEqual(["Run 1: succeeded", "Run 2: failed", "Run 3: running"]);
    expect(marks.map((mark) => mark.getAttribute("title"))).toEqual(["Run 1: succeeded", "Run 2: failed", "Run 3: running"]);
    expect(screen.getByText("last 20 runs")).toBeInTheDocument();
  });

  it("gives each status its own shape, not only its own colour", () => {
    render(<RunStrip runs={runs} label="runs" />);
    const classes = screen.getAllByRole("img").map((mark) => mark.className);
    expect(new Set(classes).size).toBe(3);
  });

  it("keeps only the newest `max` runs", () => {
    render(<RunStrip runs={runs} label="last 2 runs" max={2} />);
    expect(screen.getAllByRole("img").map((mark) => mark.getAttribute("aria-label"))).toEqual(["Run 2: failed", "Run 3: running"]);
  });

  it("renders nothing for no runs", () => {
    expect(render(<RunStrip runs={[]} label="last 20 runs" />).container).toBeEmptyDOMElement();
  });
});

describe("RankedBars", () => {
  const items = [
    { label: "web_search", value: 10 },
    { label: "send_email", value: 40 },
    { label: "read_file", value: 20 },
    { label: "calendar", value: 5 },
  ];

  it("ranks largest first as an ordered list with value text", () => {
    const { container } = render(<RankedBars items={items} ariaLabel={SUMMARY} />);
    const list = screen.getByRole("list", { name: SUMMARY });
    expect(list.tagName).toBe("OL");
    expect(within(list).getAllByRole("listitem").map((li) => li.textContent)).toEqual([
      "send_email40",
      "read_file20",
      "web_search10",
      "calendar5",
    ]);
    const widths = [...container.querySelectorAll<HTMLElement>("[data-ranked-bar]")].map((bar) => bar.style.width);
    expect(widths).toEqual(["100%", "50%", "25%", "12.5%"]);
    expect(container.querySelector("[data-ranked-more]")).toBeNull();
  });

  it("scales against a fixed full-scale value when given one (rates)", () => {
    const { container } = render(
      <RankedBars items={[{ label: "a", value: 0.5 }, { label: "b", value: 0.25 }]} scaleMax={1} ariaLabel="x" />,
    );
    const widths = [...container.querySelectorAll<HTMLElement>("[data-ranked-bar]")].map((bar) => bar.style.width);
    expect(widths).toEqual(["50%", "25%"]);
  });

  it("caps the rows and says how many more there are", () => {
    const { container } = render(<RankedBars items={items} cap={2} ariaLabel="x" />);
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    expect(container.querySelector("[data-ranked-more]")?.textContent).toBe("and 2 more");
  });

  it("accepts a caller-supplied more line and pre-ranked order", () => {
    const { container } = render(
      <RankedBars items={items} cap={1} sort={false} moreLabel={(count) => `+${count} others`} ariaLabel="x" />
    );
    expect(screen.getByRole("listitem")).toHaveTextContent("web_search");
    expect(container.querySelector("[data-ranked-more]")?.textContent).toBe("+3 others");
  });

  it("renders nothing for no items, and no NaN widths for all-zero or dirty values", () => {
    expect(render(<RankedBars items={[]} ariaLabel="x" />).container).toBeEmptyDOMElement();
    const { container } = render(
      <RankedBars items={[{ label: "a", value: 0 }, { label: "b", value: NaN }, { label: "c", value: -1 }]} ariaLabel="x" />
    );
    const widths = [...container.querySelectorAll<HTMLElement>("[data-ranked-bar]")].map((bar) => bar.style.width);
    expect(widths).toEqual(["0%", "0%", "0%"]);
    noNaN(container);
  });
});
