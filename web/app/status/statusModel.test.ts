import { describe, it, expect } from "vitest";
import {
  COMPONENT_LABELS,
  componentLabel,
  describeBanner,
  isNotReporting,
  statusIconKind,
  statusLabel,
} from "./statusModel";

const up = (name: string) => ({ name, status: "UP" });

describe("status labels", () => {
  it("words the known codes and tidies unfamiliar ones", () => {
    expect(statusLabel("UP")).toBe("Up");
    expect(statusLabel("DOWN")).toBe("Down");
    expect(statusLabel("DEGRADED")).toBe("Degraded");
    expect(statusLabel("UNKNOWN")).toBe("Unknown");
    expect(statusLabel("NOT_CONFIGURED")).toBe("Not deployed");
    expect(statusLabel("OUT_OF_SERVICE")).toBe("Out of service");
  });

  it("picks an icon that is not green for anything unverified", () => {
    expect(statusIconKind("UP")).toBe("up");
    expect(statusIconKind("DOWN")).toBe("down");
    expect(statusIconKind("NOT_CONFIGURED")).toBe("off");
    expect(statusIconKind("UNKNOWN")).toBe("warn");
    expect(statusIconKind("OUT_OF_SERVICE")).toBe("warn");
  });
});

describe("component labels say what is actually probed", () => {
  it("does not call the sidecar the agent runtime", () => {
    expect(componentLabel("agents-runtime")).toBe("Embeddings & document service");
    expect(Object.values(COMPONENT_LABELS).join(" ")).not.toMatch(/agent runtime/i);
  });

  it("calls the disk probe disk space and falls back to the raw name", () => {
    expect(componentLabel("disk")).toBe("Disk space (API host)");
    expect(componentLabel("brand-new-component")).toBe("brand-new-component");
  });
});

describe("describeBanner", () => {
  it("says all systems operational only when every component is up or intentionally absent", () => {
    const b = describeBanner("UP", [up("api"), up("database"), { name: "events", status: "NOT_CONFIGURED" }]);
    expect(b).toEqual({ title: "All systems operational", tone: "ok", notReporting: 0 });
  });

  it("does not hide a component the server could not read under a green banner", () => {
    const b = describeBanner("UP", [up("api"), { name: "redis", status: "UNKNOWN" }]);
    expect(b.tone).toBe("warn");
    expect(b.notReporting).toBe(1);
    expect(b.title).toBe("Operational, but 1 check is not reporting");
  });

  it("pluralises and counts every non-reporting component", () => {
    const b = describeBanner("UP", [
      up("api"),
      { name: "redis", status: "UNKNOWN" },
      { name: "disk", status: "OUT_OF_SERVICE" },
    ]);
    expect(b.title).toBe("Operational, but 2 checks are not reporting");
    expect(b.notReporting).toBe(2);
  });

  it("passes the server's degraded and down roll-ups through", () => {
    expect(describeBanner("DEGRADED", []).title).toBe("Partial degradation");
    expect(describeBanner("DEGRADED", []).tone).toBe("warn");
    expect(describeBanner("DOWN", []).title).toBe("Major outage");
    expect(describeBanner("DOWN", []).tone).toBe("bad");
  });

  it("handles the page's own pseudo-states and any unrecognised roll-up", () => {
    expect(describeBanner("UNREACHABLE", []).tone).toBe("bad");
    expect(describeBanner("CHECKING", []).title).toBe("Checking…");
    expect(describeBanner("SOMETHING_ELSE", []).title).toBe("Status unknown");
    expect(describeBanner("SOMETHING_ELSE", []).tone).toBe("warn");
  });

  it("treats only UP and NOT_CONFIGURED as not needing attention", () => {
    expect(isNotReporting("UP")).toBe(false);
    expect(isNotReporting("NOT_CONFIGURED")).toBe(false);
    expect(isNotReporting("UNKNOWN")).toBe(true);
    expect(isNotReporting("DOWN")).toBe(true);
  });
});
