// i18n guard for the agent glance card, the guardrail activity line and the two agent explainers.
// useTranslations() returns the dotted path for a missing key (it never falls back), so this checks every
// t("...") literal those files use against its namespace in messages/en.json, that no key is left unused, and
// that the explainers render real copy with the steps the core hook engine / argument-policy guard has.

import { describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import { render, screen, within } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string) =>
    (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key] ?? `${namespace}.${key}`,
}));

import HookInterceptExplainer from "./HookInterceptExplainer";
import ToolPolicyExplainer from "./ToolPolicyExplainer";

const messages = en as unknown as Record<string, Record<string, string>>;

const FILES: Record<string, string[]> = {
  agentGlance: ["./AgentGlanceCard.tsx"],
  guardrailActivity: ["./GuardrailActivity.tsx"],
  explainersAgents: ["./HookInterceptExplainer.tsx", "./ToolPolicyExplainer.tsx"],
};

// Keys chosen at runtime (a ternary inside t(...)), which the literal scan below cannot see.
const DYNAMIC: Record<string, string[]> = {
  agentGlance: [],
  guardrailActivity: ["hooksSummary", "hooksSummaryWithErrors"],
  explainersAgents: [],
};

function literalKeys(source: string): string[] {
  const keys = new Set<string>();
  for (const m of source.matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
  return [...keys];
}

describe("agent insights i18n", () => {
  for (const [namespace, files] of Object.entries(FILES)) {
    it(`${namespace}: every key used exists, every key is used, and the namespace is the one the files read`, () => {
      const ns = messages[namespace];
      expect(ns).toBeTypeOf("object");
      const used = new Set<string>(DYNAMIC[namespace]);
      for (const file of files) {
        const source = readFileSync(path.resolve(__dirname, file), "utf8");
        expect(source).toContain(`useTranslations("${namespace}")`);
        const keys = literalKeys(source);
        expect(keys.length).toBeGreaterThan(0);
        for (const key of keys) used.add(key);
      }
      expect([...used].filter((k) => !(k in ns))).toEqual([]);
      expect(Object.keys(ns).filter((k) => !used.has(k))).toEqual([]);
    });

    it(`${namespace}: no em dash and no empty string`, () => {
      for (const value of Object.values(messages[namespace])) {
        expect(value).not.toMatch(/—/);
        expect(value.trim()).not.toBe("");
      }
    });
  }

  it("keeps the placeholders the code passes values for", () => {
    const g = messages.agentGlance;
    for (const name of ["ok", "total", "percent", "range"]) expect(g.summary).toContain(`{${name}}`);
    for (const name of ["window", "succeeded", "failed", "cancelled"]) expect(g.jobsSummary).toContain(`{${name}}`);
    for (const name of ["queued", "running", "awaiting"]) expect(g.jobsLive).toContain(`{${name}}`);
    for (const name of ["ok", "total", "percent"]) expect(g.toolsSummary).toContain(`{${name}}`);
    expect(g.footer).toContain("{range}");
    expect(g.tableCaption).toContain("{range}");
    expect(g.range).toContain("{from}");
    expect(g.range).toContain("{to}");
    expect(g.windowDays).toContain("{count}");
    expect(g.windowHours).toContain("{count}");
    for (const key of ["rowJobsSucceeded", "rowJobsFailed", "rowJobsCancelled", "jobsNone"]) expect(g[key]).toContain("{window}");
    const a = messages.guardrailActivity;
    for (const key of ["hooksSummary", "hooksSummaryWithErrors"]) {
      for (const name of ["days", "blocked", "approval", "redacted"]) expect(a[key]).toContain(`{${name}}`);
    }
    expect(a.hooksSummaryWithErrors).toContain("{errors}");
    expect(a.policySummary).toContain("{refused}");
    for (const key of ["hooksNone", "policySummary", "policyNone", "tableCaption"]) expect(a[key]).toContain("{days}");
  });
});

describe("agent explainers", () => {
  it("HookInterceptExplainer: closed by default, five ordered steps, the real before-tool and after-tool outcomes", () => {
    const { container } = render(<HookInterceptExplainer />);
    expect(container.querySelector("details")!.open).toBe(false);
    const steps = container.querySelectorAll("[data-flow-step]");
    expect(steps).toHaveLength(5);
    expect(steps[0].textContent).toContain("The model asks to use a tool");
    expect(steps[2].textContent).toContain("The tool runs");
    const before = [...steps[1].querySelectorAll("[data-flow-outcome]")].map((o) => o.textContent!.split(" - ")[0]);
    expect(before).toEqual(["Allow", "Redact", "Needs approval", "Block or limit"]);
    const after = [...steps[3].querySelectorAll("[data-flow-outcome]")].map((o) => o.textContent!.split(" - ")[0]);
    expect(after).toEqual(["Pass on", "Redact", "Withhold"]);
    expect(steps[4].textContent).toContain("agent.hook.decision");
    expect(container.innerHTML).not.toMatch(/explainersAgents\.|charts\./);
    expect(within(container).getByText("How this works")).toBeInTheDocument();
  });

  it("ToolPolicyExplainer: allowed or refused, and the refusal is recorded under the real action type", () => {
    const { container } = render(<ToolPolicyExplainer />);
    const steps = container.querySelectorAll("[data-flow-step]");
    expect(steps).toHaveLength(3);
    const outcomes = [...steps[1].querySelectorAll("[data-flow-outcome]")].map((o) => o.textContent!.split(" - ")[0]);
    expect(outcomes).toEqual(["Allowed", "Refused"]);
    expect(steps[2].textContent).toContain("tool.arg_policy.denied");
    expect(container.innerHTML).not.toMatch(/explainersAgents\.|charts\./);
    expect(screen.getAllByRole("list").length).toBeGreaterThan(0);
  });
});
