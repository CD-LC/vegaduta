import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";

vi.mock("../../lib/i18n", async () => {
  const en = (await import("../../../messages/en.json")).default as unknown as Record<string, Record<string, string>>;
  return {
    useTranslations: (ns: string) => (key: string, values?: Record<string, unknown>) => {
      const raw = en[ns]?.[key] ?? `${ns}.${key}`;
      return raw.replace(/\{(\w+)\}/g, (_m, k) => String(values?.[k] ?? ""));
    },
  };
});

import JudgeSamplingNote from "./JudgeSamplingNote";
import { JUDGE_SAMPLE_RATE_KEY, judgeSamplingFrom } from "./judgeSampling";
import type { EffectiveSetting, SettingsClient } from "../../lib/api/settings";

function client(values: Record<string, EffectiveSetting> | Error): SettingsClient {
  return {
    getCatalog: vi.fn(),
    getEffective: vi.fn(() => (values instanceof Error ? Promise.reject(values) : Promise.resolve({ values }))),
    put: vi.fn(),
    reset: vi.fn(),
    getHistory: vi.fn(),
  };
}

describe("judgeSamplingFrom", () => {
  it("reads the server's fraction as a percentage", () => {
    expect(judgeSamplingFrom({ values: { [JUDGE_SAMPLE_RATE_KEY]: { value: 0.05, source: "platform", locked: true } } })).toEqual({
      percent: 5,
      source: "platform",
      locked: true,
    });
    expect(judgeSamplingFrom({ values: { [JUDGE_SAMPLE_RATE_KEY]: { value: "0.125", source: "default", locked: false } } })?.percent).toBe(12.5);
  });

  it("treats a value above 1 as a percentage already and caps it", () => {
    expect(judgeSamplingFrom({ values: { [JUDGE_SAMPLE_RATE_KEY]: { value: 20, source: "tenant", locked: false } } })?.percent).toBe(20);
    expect(judgeSamplingFrom({ values: { [JUDGE_SAMPLE_RATE_KEY]: { value: 400, source: "tenant", locked: false } } })?.percent).toBe(100);
  });

  it("is null when the rate is absent or unreadable", () => {
    expect(judgeSamplingFrom({ values: {} })).toBeNull();
    expect(judgeSamplingFrom(null)).toBeNull();
    expect(judgeSamplingFrom({ values: { [JUDGE_SAMPLE_RATE_KEY]: { value: "lots", source: "default", locked: false } } })).toBeNull();
    expect(judgeSamplingFrom({ values: { [JUDGE_SAMPLE_RATE_KEY]: { value: -1, source: "default", locked: false } } })).toBeNull();
  });
});

describe("JudgeSamplingNote", () => {
  it("shows the rate and where it comes from, read-only", async () => {
    const c = client({ [JUDGE_SAMPLE_RATE_KEY]: { value: 0.05, source: "platform", locked: true } });
    render(<JudgeSamplingNote agentId="a1" client={c} />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading the scoring rate...");
    await screen.findByText("About 5% of live turns are scored by the background quality judge.");
    expect(screen.getByTestId("judge-sampling-source")).toHaveTextContent("Set by the platform");
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("slider")).not.toBeInTheDocument();
    expect(c.getEffective).toHaveBeenCalledWith("a1");
  });

  it("says it is not shown when the settings API does not publish the rate", async () => {
    render(<JudgeSamplingNote agentId="a1" client={client({})} />);
    await screen.findByText(/set by your platform administrator and is not shown here/);
  });

  it("says the same when the settings API cannot be read", async () => {
    render(<JudgeSamplingNote agentId="a1" client={client(new Error("404"))} />);
    await screen.findByText(/set by your platform administrator and is not shown here/);
  });
});
