import { describe, expect, it } from "vitest";
import { explainBuildError, stepLabel } from "./explainError";

const e = (message: string, status?: number) => ({ message, status });

describe("explainBuildError", () => {
  it("explains an expired session and never offers a blind retry", () => {
    const x = explainBuildError(e("Unauthorized", 401));
    expect(x.kind).toBe("session");
    expect(x.retryable).toBe(false);
    expect(x.action?.href).toBe("/login");
  });

  it("explains a role problem on 403", () => {
    expect(explainBuildError(e("Forbidden", 403)).kind).toBe("permission");
  });

  it("explains a plan limit (billing on: links to plans)", () => {
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    try {
      const x = explainBuildError(e("You have reached the maximum number of agents for your plan", 402));
      expect(x.kind).toBe("limit");
      expect(x.action?.href).toBe("/billing");
    } finally {
      delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
    }
  });

  it("explains a plan limit (billing off: same message, no upgrade link)", () => {
    const x = explainBuildError(e("You have reached the maximum number of agents for your plan", 402));
    expect(x.kind).toBe("limit");
    expect(x.title).toMatch(/plan limit/);
    expect(x.action?.href).toBe("/usage");
    expect(x.fixes.join(" ")).not.toMatch(/upgrade/i);
  });

  it("explains the blueprint's agent-limit refusal and offers an upgrade only when billing is on", () => {
    const msg = "The brief is not valid: limits: This brief adds 1 agent(s) to your existing ones, which is over your plan's limit of 5 agents.";
    for (const status of [400, 403]) {
      const x = explainBuildError(e(msg, status));
      expect(x.kind).toBe("limit");
      // Billing is off by default (self-hosted): same explanation, no upgrade path.
      expect(x.upgrade).toBe(false);
      expect(x.what).toContain("5 agents");
      expect(x.what).toContain("add 1 more");
    }
    process.env.NEXT_PUBLIC_BILLING_ENABLED = "1";
    try {
      for (const status of [400, 403]) {
        expect(explainBuildError(e(msg, status)).upgrade).toBe(true);
      }
    } finally {
      delete process.env.NEXT_PUBLIC_BILLING_ENABLED;
    }
  });

  it("points at providers when no model is connected", () => {
    const x = explainBuildError(e("No provider configured for this tenant"));
    expect(x.kind).toBe("model");
    expect(x.action?.href).toBe("/providers");
  });

  it("treats a busy backend as retryable", () => {
    const x = explainBuildError(e("The service is restarting or briefly overloaded", 503));
    expect(x.kind).toBe("busy");
    expect(x.retryable).toBe(true);
  });

  it("recognises the platform's own unavailable message even without a status", () => {
    const x = explainBuildError(e("We're sorry, our service is temporarily unavailable. Please try again shortly."));
    expect(x.kind).toBe("busy");
    expect(x.retryable).toBe(true);
  });

  it("recognises an unreachable network", () => {
    expect(explainBuildError(e("Failed to fetch")).kind).toBe("offline");
  });

  it("explains validation failures and keeps the raw detail", () => {
    const x = explainBuildError(e("agents[0].name must be at most 60 characters", 422));
    expect(x.kind).toBe("invalid");
    expect(x.details).toContain("must be at most 60");
  });

  it("only blames Git for app builds", () => {
    expect(explainBuildError(e("GitHub token cannot push to repository"), "app").kind).toBe("git");
    expect(explainBuildError(e("GitHub token cannot push to repository"), "agent").kind).not.toBe("git");
  });

  it("falls back to a generic explanation for anything else, still showing the raw text", () => {
    const x = explainBuildError(e("kaboom"));
    expect(x.kind).toBe("unknown");
    expect(x.retryable).toBe(true);
    expect(x.details).toBe("kaboom");
  });

  it("copes with nothing at all", () => {
    expect(explainBuildError(undefined).details).toMatch(/no message/i);
    expect(explainBuildError("plain string").details).toBe("plain string");
  });

  it("every explanation gives the person at least one thing to try", () => {
    for (const [msg, status] of [["", 500], ["x", 401], ["x", 403], ["x", 429], ["x", 409], ["x", 0]] as const) {
      expect(explainBuildError(e(msg, status)).fixes.length).toBeGreaterThan(0);
    }
  });
});

describe("stepLabel", () => {
  it("humanises known apply steps and falls back gracefully", () => {
    expect(stepLabel("CREATE_AGENT")).toBe("Creating your agent");
    expect(stepLabel("eval-set")).toBe("Adding starter test questions");
    expect(stepLabel("SOMETHING_NEW")).toBe("Something new");
    expect(stepLabel("")).toBe("Working");
  });
});
