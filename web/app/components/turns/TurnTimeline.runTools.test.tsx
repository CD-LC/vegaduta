import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";

vi.mock("../../lib/i18n", async () => {
  const en = (await import("../../../messages/en.json")).default as unknown as Record<string, Record<string, string>>;
  return {
    useLocale: () => "en",
    useTranslations: (ns: string) => (key: string, values?: Record<string, unknown>) => {
      const raw = en[ns]?.[key] ?? `${ns}.${key}`;
      return raw.replace(/\{(\w+)\}/g, (_m, k) => String(values?.[k] ?? ""));
    },
  };
});

import TurnTimeline from "./TurnTimeline";
import { buildChain } from "./chainTestUtil";

async function intake(summary: string) {
  const events = await buildChain([{ type: "INTAKE", summary }]);
  render(<TurnTimeline events={events} brokenAtSeq={null} />);
  return screen.getByRole("listitem", { name: "Event 0: INTAKE" });
}

describe("TurnTimeline - the tools a run was narrowed to", () => {
  it("lists the run's tools on the intake event", async () => {
    const item = await intake("messageChars=12 attachments=0 tools=2 runTools=web_search,fetch_web_page");
    const row = within(item).getByTestId("tl-run-tools");
    expect(row).toHaveTextContent("Tools for this run: web_search, fetch_web_page");
  });

  it("says when the run had no tools", async () => {
    const item = await intake("messageChars=12 attachments=0 tools=0 runTools=none");
    expect(within(item).getByTestId("tl-run-tools")).toHaveTextContent("none (text only)");
  });

  it("shows nothing for a turn that was not narrowed", async () => {
    const item = await intake("messageChars=12 attachments=0 tools=2");
    expect(within(item).queryByTestId("tl-run-tools")).toBeNull();
  });
});
