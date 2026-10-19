// Render + i18n tests for the three workflow explainers (Wait, error strategy,
// Switch). useTranslations() returns the dotted path for a missing key, so the
// translator here is backed by the real en.json and every test asserts that no
// raw "explainersWorkflow." path reached the screen.

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render } from "@testing-library/react";
import fs from "node:fs";
import path from "node:path";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string) =>
    (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key] ?? `${namespace}.${key}`,
}));

import ErrorStrategyExplainer from "./ErrorStrategyExplainer";
import SwitchNodeExplainer from "./SwitchNodeExplainer";
import WaitNodeExplainer from "./WaitNodeExplainer";

const messages = en as unknown as Record<string, Record<string, string>>;
const ns = messages.explainersWorkflow;
const FILES = ["WaitNodeExplainer.tsx", "ErrorStrategyExplainer.tsx", "SwitchNodeExplainer.tsx"];

afterEach(cleanup);

function check(container: HTMLElement) {
  expect(container.textContent).not.toMatch(/explainersWorkflow\.|charts\./);
  const details = container.querySelector("details")!;
  expect(details).not.toBeNull();
  // Collapsed by default, behind the shared "How this works" label.
  expect(details.hasAttribute("open")).toBe(false);
  expect(details.querySelector("summary")?.textContent).toBe("How this works");
  return details;
}

const stepTitles = (container: HTMLElement) =>
  [...container.querySelectorAll("[data-flow-step]")].map((li) => li.querySelector("p")?.textContent);
const outcomeLabels = (container: HTMLElement) =>
  [...container.querySelectorAll("[data-flow-outcome] .font-semibold")].map((el) => el.textContent);

describe("WaitNodeExplainer", () => {
  it("time modes: reach, parked, time arrives, continue - with no webhook outcomes", () => {
    for (const mode of ["duration", "until"] as const) {
      const { container, unmount } = render(<WaitNodeExplainer mode={mode} />);
      check(container);
      expect(stepTitles(container)).toEqual([
        "The run reaches the Wait node",
        "The run is parked",
        "The time arrives",
        "The run continues at the next node",
      ]);
      expect(outcomeLabels(container)).toEqual([]);
      expect(container.textContent).toContain("no step runs and no model is called");
      unmount();
    }
  });

  it("webhook mode: the resume call and both timeout behaviours, named as the form names them", () => {
    const { container } = render(<WaitNodeExplainer mode="webhook" />);
    check(container);
    expect(outcomeLabels(container)).toEqual([
      "Resume call",
      "Nothing arrives in time: Fail the run",
      "Nothing arrives in time: Continue the run",
    ]);
    // The form's own option labels are what the explainer refers to.
    expect(ns.waitOutcomeTimeoutFailLabel).toContain(messages.workflowForms.waitOnTimeoutFail);
    expect(ns.waitOutcomeTimeoutContinueLabel).toContain(messages.workflowForms.waitOnTimeoutContinue);
    expect(container.textContent).toContain("There is no separate timeout branch.");
  });
});

describe("ErrorStrategyExplainer", () => {
  it("lists the three real onError options under their editor labels", () => {
    const { container } = render(<ErrorStrategyExplainer />);
    check(container);
    expect(stepTitles(container)).toHaveLength(3);
    expect(outcomeLabels(container)).toEqual([
      messages.workflowForms.onError_fail,
      messages.workflowForms.onError_continue,
      messages.workflowForms.onError_branch,
    ]);
  });
});

describe("SwitchNodeExplainer", () => {
  it("first match wins, no match goes to Default, data passes through", () => {
    const { container } = render(<SwitchNodeExplainer />);
    check(container);
    expect(outcomeLabels(container)).toEqual(["First match", "No match"]);
    expect(container.textContent).toContain("the run leaves through the Default port.");
    expect(container.textContent).toContain("The next node receives exactly what the Switch received.");
  });
});

describe("explainersWorkflow i18n", () => {
  const KEY_RE = /\bt\(\s*"([A-Za-z0-9_]+)"/g;

  it("defines every key the explainers read, and carries no unused key", () => {
    expect(ns).toBeTypeOf("object");
    const used = new Set<string>();
    for (const file of FILES) {
      const source = fs.readFileSync(path.resolve(__dirname, file), "utf8");
      expect([...source.matchAll(/useTranslations\(\s*"([\w.-]+)"/g)].map((m) => m[1])).toEqual(["explainersWorkflow"]);
      for (const match of source.matchAll(KEY_RE)) {
        used.add(match[1]);
        expect(ns[match[1]], `explainersWorkflow.${match[1]} (${file})`).toBeTypeOf("string");
      }
    }
    expect([...used].sort()).toEqual(Object.keys(ns).sort());
  });

  it("contains no em dash", () => {
    for (const value of Object.values(ns)) expect(value).not.toMatch(/—/);
  });
});
