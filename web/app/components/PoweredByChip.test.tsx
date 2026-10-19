// "customers should know what they are doing, what is powering" - the shared
// engine-disclosure chip used by quick task, playground, build and custom
// apps. These tests pin the two behaviors the whole feature rests on: the
// internal PLATFORM_HOSTED accounting label never reaches the screen raw,
// and a missing model/provider is rendered as "not disclosed" rather than
// fabricated.

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import PoweredByChip, { inferSource, providerDisplayName } from "./PoweredByChip";

afterEach(cleanup);

describe("providerDisplayName", () => {
  it("masks the internal PLATFORM_HOSTED accounting label", () => {
    expect(providerDisplayName("PLATFORM_HOSTED")).toBe("VegaDūta (included)");
    expect(providerDisplayName("PLATFORM_HOSTED")).not.toContain("PLATFORM_HOSTED");
  });

  it("maps known provider enum names to brand labels", () => {
    expect(providerDisplayName("ANTHROPIC")).toBe("Anthropic");
    expect(providerDisplayName("AWS_BEDROCK")).toBe("AWS Bedrock");
  });

  it("humanizes unknown enum-style names and returns null for null", () => {
    expect(providerDisplayName("SOME_NEW_PROVIDER")).toBe("Some new provider");
    expect(providerDisplayName(null)).toBeNull();
    expect(providerDisplayName(undefined)).toBeNull();
  });
});

describe("inferSource", () => {
  it("treats PLATFORM_HOSTED as the platform pool", () => {
    expect(inferSource("PLATFORM_HOSTED")).toBe("platform");
  });
  it("treats a named provider as bring-your-own-key", () => {
    expect(inferSource("ANTHROPIC")).toBe("byok");
  });
});

describe("PoweredByChip", () => {
  it("renders provider and model together", () => {
    render(<PoweredByChip source="byok" provider="ANTHROPIC" model="claude-sonnet-4" />);
    expect(screen.getByText(/Powered by Anthropic/)).toBeTruthy();
    expect(screen.getByText(/claude-sonnet-4/)).toBeTruthy();
  });

  it("never leaks the raw PLATFORM_HOSTED label", () => {
    render(<PoweredByChip source="platform" provider="PLATFORM_HOSTED" />);
    expect(screen.getByText(/VegaDūta \(included\)/)).toBeTruthy();
    expect(screen.queryByText(/PLATFORM_HOSTED/)).toBeNull();
  });

  it("renders on-device with a model name when known", () => {
    render(<PoweredByChip source="device" model="On-device model — compact" prefix="Drafted by" />);
    expect(screen.getByText(/Drafted by on-device/)).toBeTruthy();
    expect(screen.getByText(/On-device model — compact/)).toBeTruthy();
  });

  it("labels deterministic output honestly (no model implied)", () => {
    render(<PoweredByChip source="recipes" prefix="Drafted by" />);
    expect(screen.getByText(/Drafted by recipes/)).toBeTruthy();
  });

  it("carries the source-specific explanation in the tooltip", () => {
    render(<PoweredByChip source="platform" provider="PLATFORM_HOSTED" />);
    expect(screen.getByTitle(/hosted model pool/)).toBeTruthy();
  });
});
