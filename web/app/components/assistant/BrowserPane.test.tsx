// The Assistant's browser pane: the unavailable state (instead of hiding), the sign-in offer on a live
// conversation, and the saved-login line. i18n echoes keys; the takeover panel is stubbed to its props.

import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, v?: Record<string, string | number>) => (v ? `${key}:${Object.values(v).join(",")}` : key),
}));
vi.mock("../agents/BrowserTakeoverPanel", () => ({
  default: (p: { agentId?: string; sessionId?: string; pauseLabel?: string; usedBrowser?: boolean }) => (
    <div data-testid="takeover-stub" data-agent={p.agentId} data-session={p.sessionId} data-label={p.pauseLabel} data-used={String(p.usedBrowser)} />
  ),
}));

import BrowserPane from "./BrowserPane";
import type { BrowserProfile } from "../../lib/api/browserAutomation";

const profile = {
  id: "p1",
  name: "Work logins",
  allowedDomains: ["shop.example", "mail.example"],
  hasState: true,
  expired: false,
  attachedAgentIds: ["a1"],
} as BrowserProfile;

afterEach(cleanup);

describe("BrowserPane", () => {
  it("explains the missing add-on instead of hiding", () => {
    render(<BrowserPane availability="unavailable" agentId="a1" sessionId="s1" profile={null} profilesOn={false} onClose={() => {}} />);
    expect(screen.getByTestId("browser-unavailable")).toBeTruthy();
    expect(screen.getByTestId("browser-unavailable").textContent).toContain("unavailableReason");
    expect(screen.queryByTestId("takeover-stub")).toBeNull();
  });

  it("offers sign-in on the conversation's session and shows the saved login", () => {
    render(<BrowserPane availability="ready" agentId="a1" sessionId="s1" profile={profile} profilesOn onClose={() => {}} />);
    const stub = screen.getByTestId("takeover-stub");
    expect(stub.dataset.session).toBe("s1");
    expect(stub.dataset.agent).toBe("a1");
    expect(stub.dataset.label).toBe("signInButton");
    expect(stub.dataset.used).toBe("true");
    expect(screen.getByText("Work logins")).toBeTruthy();
    expect(screen.getByText("shop.example, mail.example")).toBeTruthy();
  });

  it("asks for a first message when there is no session yet, and omits the profile line when logins are off", () => {
    render(<BrowserPane availability="ready" agentId="a1" sessionId={null} profile={null} profilesOn={false} onClose={() => {}} />);
    expect(screen.getByTestId("browser-no-session")).toBeTruthy();
    expect(screen.queryByTestId("takeover-stub")).toBeNull();
    expect(screen.queryByTestId("browser-profile")).toBeNull();
  });
});
