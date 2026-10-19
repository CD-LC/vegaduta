import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});
vi.mock("../../lib/api", () => ({ api: { setSmsDefaultAgent: vi.fn() } }));

import { SmsInspector } from "./SmsInspector";

describe("SmsInspector", () => {
  it("points a not-yet-connected tenant at the Twilio setup and shows the webhook path", () => {
    const onConnect = vi.fn();
    render(<SmsInspector agents={[]} loaded entry={undefined} refreshStatus={() => {}} onConnectTwilio={onConnect} />);
    expect(screen.getByText(/\/webhook\/twilio\/sms$/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Open Twilio setup" }));
    expect(onConnect).toHaveBeenCalled();
  });

  it("shows the connection detail once Twilio is connected", () => {
    render(
      <SmsInspector
        agents={[]}
        loaded
        entry={{ state: "CONNECTED", detail: "Number +15550100" }}
        refreshStatus={() => {}}
        onConnectTwilio={() => {}}
      />
    );
    expect(screen.getByText("Number +15550100")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Open Twilio setup" })).toBeNull();
  });
});
