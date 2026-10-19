import { readFileSync } from "node:fs";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { I18nProvider } from "../../lib/i18n";
import BuildCenter from "./BuildCenter";
import ReadinessRail from "./ReadinessRail";
import NarrativeCard from "./NarrativeCard";
import sample from "./fixtures/brief.sample.json";
import { SAMPLE_READINESS } from "./fixtures/readiness.sample";
import type { Brief } from "./types";

// Smoke tests against the real en.json: catches a missing i18n key (rendered as
// its own path) and proves the flow gets from a brief to a graph. The API is
// mocked with the test fixtures; no app code ships them.
vi.mock("../../lib/api", async () => {
  const { SAMPLE_ENVOYS, SAMPLE_READINESS } = await import("./fixtures/readiness.sample");
  const sampleBrief = (await import("./fixtures/brief.sample.json")).default as any;
  const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
  return {
    api: {
      listBriefs: async () => SAMPLE_ENVOYS,
      getBrief: async () => ({ id: "sample-1", brief: sampleBrief, readiness: SAMPLE_READINESS, rung: "UNDER_WATCH", status: "APPLIED", bindings: [] as any[] }),
      planBriefStream: async (_req: unknown, onEvent: (e: any) => void) => {
        onEvent({ type: "thinking", text: "Reading what you wrote" });
        await wait(20);
        const result = { brief: sampleBrief, readiness: SAMPLE_READINESS, warnings: [] as string[] };
        onEvent({ type: "done", result });
        return result;
      },
      createBrief: async () => ({ id: "sample-1", version: 1, status: "DRAFT" }),
      applyBrief: async () => ({ jobId: "job-1" }),
      getApplyJob: async () => ({ jobId: "job-1", status: "SUCCEEDED", steps: [] as any[] }),
    },
  };
});

const messages = JSON.parse(readFileSync("messages/en.json", "utf8"));
const brief = sample as unknown as Brief;

function wrap(ui: React.ReactNode) {
  return render(
    <I18nProvider locale="en" messages={messages} enMessages={messages} complete>
      {ui}
    </I18nProvider>
  );
}

beforeAll(() => {
  // React Flow measures its container.
  (globalThis as any).ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  (globalThis as any).DOMMatrixReadOnly = class {
    m22 = 1;
  };
});
afterEach(cleanup);

const noMissingKeys = () => expect(document.body.textContent).not.toMatch(/\bbrief\.[a-zA-Z]+\./);

describe("Build Center", () => {
  it("shows the four doors and the envoys list with no missing keys", async () => {
    wrap(<BuildCenter onOpenAppBuilder={() => {}} />);
    expect(await screen.findByText("Give it a brief")).toBeTruthy();
    expect(screen.getByText("Start from an envoy")).toBeTruthy();
    expect(screen.getByText("Walk the court")).toBeTruthy();
    expect(screen.getByText("I want code")).toBeTruthy();
    expect(await screen.findByText("Your envoys")).toBeTruthy();
    expect(await screen.findByText(brief.title)).toBeTruthy();
    noMissingKeys();
  });

  it("the code door hands off to the App Builder", async () => {
    const open = vi.fn();
    wrap(<BuildCenter onOpenAppBuilder={open} />);
    fireEvent.click((await screen.findByText("I want code")).closest("button")!);
    expect(open).toHaveBeenCalled();
  });

  it("plans a brief and reaches the preview graph", async () => {
    wrap(<BuildCenter onOpenAppBuilder={() => {}} />);
    const box = await screen.findByLabelText("What do you want to get done?");
    fireEvent.change(box, { target: { value: "Chase unpaid invoices on WhatsApp every morning" } });
    fireEvent.click(screen.getByText("Plan it").closest("button")!);
    await waitFor(() => expect(screen.getByTestId("envoy-graph")).toBeTruthy(), { timeout: 8000 });
    expect(screen.getByText(/Approval shields: 2/)).toBeTruthy();
    fireEvent.click(screen.getByText("Check what it needs"));
    expect(await screen.findByText("What it needs")).toBeTruthy();
    // gated and waiting rows are never hidden and say why
    expect(screen.getByText(/Available on PREMIUM/)).toBeTruthy();
    expect(screen.getByText("Waiting on admin")).toBeTruthy();
    noMissingKeys();
  });
});

describe("ReadinessRail", () => {
  it("renders every row and disables ask-an-admin until a callback exists", () => {
    wrap(<ReadinessRail rows={SAMPLE_READINESS} />);
    expect(screen.getAllByRole("listitem")).toHaveLength(SAMPLE_READINESS.length);
    expect((screen.getByText("Ask an admin").closest("button") as HTMLButtonElement).disabled).toBe(true);
    noMissingKeys();
  });
});

describe("NarrativeCard", () => {
  it("shows why / tune / proof and disables the editor link before apply", () => {
    wrap(<NarrativeCard brief={brief} refKey="a1" onClose={() => {}} />);
    expect(screen.getByText(brief.narrative.perNode.a1.why)).toBeTruthy();
    expect((screen.getByText("Open in editor").closest("button") as HTMLButtonElement).disabled).toBe(true);
    noMissingKeys();
  });
  it("links secrets to the deep-link even before apply", () => {
    wrap(<NarrativeCard brief={brief} refKey="s1" onClose={() => {}} />);
    expect(screen.getByText("Open in editor").closest("a")?.getAttribute("href")).toBe("/secrets?tour=field:secret-add");
  });
});
