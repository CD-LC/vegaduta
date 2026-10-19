import { describe, it, expect, vi, afterEach, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import { formatUsd, timeAgo, ComingSoon, InfoNote, PageHint, SectionCard } from "./common";

describe("formatUsd", () => {
  it("uses 4 decimals below one dollar", () => {
    expect(formatUsd(0.5)).toBe("$0.5000");
    expect(formatUsd(0.00123)).toBe("$0.0012");
  });
  it("uses 2 decimals from one dollar up", () => {
    expect(formatUsd(1)).toBe("$1.00");
    expect(formatUsd(1234.567)).toBe("$1234.57");
  });
  it("treats null/undefined as zero", () => {
    expect(formatUsd(null)).toBe("$0.0000");
    expect(formatUsd(undefined)).toBe("$0.0000");
  });
  it("accepts numeric strings", () => {
    expect(formatUsd("2")).toBe("$2.00");
  });
});

describe("timeAgo", () => {
  const NOW = new Date("2026-06-15T12:00:00Z").getTime();
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
  });
  afterEach(() => vi.useRealTimers());

  const ago = (ms: number) => new Date(NOW - ms).toISOString();

  it("returns a dash for empty input", () => {
    expect(timeAgo(null)).toBe("-");
    expect(timeAgo("")).toBe("-");
  });
  it("buckets into just now / minutes / hours / days", () => {
    expect(timeAgo(ago(30_000))).toBe("just now");
    expect(timeAgo(ago(5 * 60_000))).toBe("5m ago");
    expect(timeAgo(ago(59 * 60_000))).toBe("59m ago");
    expect(timeAgo(ago(60 * 60_000))).toBe("1h ago");
    expect(timeAgo(ago(23 * 3600_000))).toBe("23h ago");
    expect(timeAgo(ago(24 * 3600_000))).toBe("1d ago");
    expect(timeAgo(ago(50 * 3600_000))).toBe("2d ago");
  });
});

describe("section primitives", () => {
  it("ComingSoon shows title, description, badge and endpoints", () => {
    render(<ComingSoon title="Budgets" description="Soon." roadmap="Q4" endpoints={["/api/a", "/api/b"]} />);
    expect(screen.getByText("Budgets")).toBeInTheDocument();
    expect(screen.getByText("Coming soon")).toBeInTheDocument();
    expect(screen.getByText("Soon.")).toBeInTheDocument();
    expect(screen.getByText("Q4")).toBeInTheDocument();
    expect(screen.getByText("/api/a")).toBeInTheDocument();
    expect(screen.getByText("/api/b")).toBeInTheDocument();
  });

  it("ComingSoon omits the endpoint row when none are given", () => {
    render(<ComingSoon title="T" description="D" endpoints={[]} />);
    expect(screen.queryByText("Will call:")).toBeNull();
  });

  it("InfoNote and PageHint render their children", () => {
    render(
      <>
        <InfoNote>scope note</InfoNote>
        <PageHint>lead text</PageHint>
      </>
    );
    expect(screen.getByText("scope note")).toBeInTheDocument();
    expect(screen.getByText("lead text")).toBeInTheDocument();
  });

  it("SectionCard renders title, subtitle, actions and body", () => {
    render(
      <SectionCard title="Card" subtitle="sub" actions={<button>act</button>}>
        <p>body</p>
      </SectionCard>
    );
    expect(screen.getByText("Card")).toBeInTheDocument();
    expect(screen.getByText("sub")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "act" })).toBeInTheDocument();
    expect(screen.getByText("body")).toBeInTheDocument();
  });
});
