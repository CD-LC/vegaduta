// Lethal-trifecta warning (trust T1): the advisory core has computed for a
// while but no console surface rendered. Locks in the three things that
// matter: it stays silent when the mix is not lethal, it names the tools per
// leg when it is, and it refetches after an allow-list save (refreshKey) -
// without that the warning would describe the tools the agent USED to have.
// Enforcement (core's trifecta-enforcement mode) adds three more things worth
// locking in: the plain-language "what is enforced" lines follow the new
// enforcement/enforced/gated fields, an older core without them still reads as
// the old advisory, and a block-mode save rejection is shown even when the
// report itself is hidden (404).

import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

const getAgentToolTrifecta = vi.fn();

vi.mock("../../lib/api", () => ({
  api: {
    get getAgentToolTrifecta() {
      return getAgentToolTrifecta;
    },
  },
}));

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, v?: Record<string, string | number>) =>
    v ? `${key}:${Object.values(v).join(",")}` : key,
}));

vi.mock("next/link", () => ({
  default: ({ children, href, ...rest }: { children: ReactNode; href: string }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import AgentTrifectaWarning, {
  isLethalTrifecta,
  isTrifectaBlockError,
  normalizeTrifectaEnforcement,
  normalizeTrifectaReport,
} from "./AgentTrifectaWarning";

const SAFE: Record<string, string[]> = { privateDataTools: ["google_gmail"], untrustedContentTools: [], egressTools: ["send_email"], unclassifiedTools: [] };
const LETHAL: Record<string, string[]> = {
  privateDataTools: ["google_gmail"],
  untrustedContentTools: ["fetch_web_page", "web_search"],
  egressTools: ["send_email"],
  unclassifiedTools: [],
};

beforeEach(() => {
  getAgentToolTrifecta.mockReset();
});

describe("normalizeTrifectaReport / isLethalTrifecta", () => {
  it("treats a missing or malformed body as four empty legs", () => {
    expect(normalizeTrifectaReport(null)).toEqual({
      privateDataTools: [],
      untrustedContentTools: [],
      egressTools: [],
      unclassifiedTools: [],
    });
    expect(normalizeTrifectaReport({ privateDataTools: "x", egressTools: ["a", 3, ""] }).egressTools).toEqual(["a"]);
  });

  it("is lethal only when all three legs are non-empty", () => {
    expect(isLethalTrifecta(normalizeTrifectaReport(SAFE))).toBe(false);
    expect(isLethalTrifecta(normalizeTrifectaReport(LETHAL))).toBe(true);
    expect(isLethalTrifecta(null)).toBe(false);
  });
});

describe("normalizeTrifectaEnforcement", () => {
  it("reads an older core's body (no enforcement fields) as the old advisory", () => {
    expect(normalizeTrifectaEnforcement(LETHAL)).toEqual({ mode: "warn", enforced: false, gated: false });
    expect(normalizeTrifectaEnforcement(null)).toEqual({ mode: "warn", enforced: false, gated: false });
  });

  it("reads the four modes and the two flags, and ignores anything else", () => {
    expect(normalizeTrifectaEnforcement({ enforcement: "block", enforced: true, gated: true })).toEqual({
      mode: "block",
      enforced: true,
      gated: true,
    });
    expect(normalizeTrifectaEnforcement({ enforcement: "require-approval" }).mode).toBe("require-approval");
    expect(normalizeTrifectaEnforcement({ enforcement: "off" }).mode).toBe("off");
    expect(normalizeTrifectaEnforcement({ enforcement: "strict", enforced: "yes", gated: 1 })).toEqual({
      mode: "warn",
      enforced: false,
      gated: false,
    });
  });
});

describe("isTrifectaBlockError", () => {
  it("recognises the policy 400 and nothing else", () => {
    const message = "Blocked by the lethal-trifecta policy: this tool mix lets the agent read private data (a).";
    expect(isTrifectaBlockError(Object.assign(new Error(message), { status: 400 }))).toBe(true);
    expect(isTrifectaBlockError(Object.assign(new Error(message), { status: 500 }))).toBe(false);
    expect(isTrifectaBlockError(Object.assign(new Error("Name is required"), { status: 400 }))).toBe(false);
    expect(isTrifectaBlockError(null)).toBe(false);
    expect(isTrifectaBlockError("Blocked by the lethal-trifecta policy")).toBe(false);
  });
});

describe("AgentTrifectaWarning", () => {
  it("renders nothing when the tool mix is not lethal", async () => {
    getAgentToolTrifecta.mockResolvedValue(SAFE);
    const { container } = render(<AgentTrifectaWarning agentId="a1" />);
    await waitFor(() => expect(getAgentToolTrifecta).toHaveBeenCalledWith("a1"));
    await waitFor(() => expect(container.firstChild).toBeNull());
    expect(screen.queryByTestId("trifecta-warning")).toBeNull();
  });

  it("renders nothing when the endpoint is missing (older core answers 404)", async () => {
    getAgentToolTrifecta.mockRejectedValue(Object.assign(new Error("Not found"), { status: 404 }));
    const { container } = render(<AgentTrifectaWarning agentId="a1" />);
    await waitFor(() => expect(getAgentToolTrifecta).toHaveBeenCalled());
    expect(container.firstChild).toBeNull();
  });

  it("names the tools of all three legs and offers both actions when lethal", async () => {
    getAgentToolTrifecta.mockResolvedValue(LETHAL);
    const onRequireApproval = vi.fn();
    render(<AgentTrifectaWarning agentId="a 1" onRequireApproval={onRequireApproval} />);

    expect(await screen.findByTestId("trifecta-warning")).toBeTruthy();
    expect(screen.getByTestId("trifecta-leg-private").textContent).toContain("google_gmail");
    expect(screen.getByTestId("trifecta-leg-untrusted").textContent).toContain("fetch_web_page, web_search");
    expect(screen.getByTestId("trifecta-leg-egress").textContent).toContain("send_email");
    expect(screen.queryByTestId("trifecta-unclassified")).toBeNull();

    expect(screen.getByTestId("trifecta-test-link").getAttribute("href")).toBe(
      "/agents/quality?tab=simulations&agentId=a%201#attack"
    );
    fireEvent.click(screen.getByTestId("trifecta-require-approval"));
    expect(onRequireApproval).toHaveBeenCalledTimes(1);
  });

  it("replaces the approval action with a confirmation when approval is already required", async () => {
    getAgentToolTrifecta.mockResolvedValue(LETHAL);
    render(<AgentTrifectaWarning agentId="a1" approvalRequired onRequireApproval={() => {}} />);
    expect(await screen.findByTestId("trifecta-approval-on")).toBeTruthy();
    expect(screen.queryByTestId("trifecta-require-approval")).toBeNull();
  });

  it("shows the muted could-not-judge line for unclassified tools, with or without the warning", async () => {
    getAgentToolTrifecta.mockResolvedValue({ ...SAFE, unclassifiedTools: ["mcp__x__a", "mcp__x__b"] });
    render(<AgentTrifectaWarning agentId="a1" />);
    const line = await screen.findByTestId("trifecta-unclassified");
    expect(line.textContent).toBe("unclassifiedMany:2");
    expect(screen.queryByTestId("trifecta-warning")).toBeNull();
  });

  it("refetches after an allow-list save (refreshKey) and when the agent changes", async () => {
    getAgentToolTrifecta.mockResolvedValue(SAFE);
    const { rerender } = render(<AgentTrifectaWarning agentId="a1" refreshKey={0} />);
    await waitFor(() => expect(getAgentToolTrifecta).toHaveBeenCalledTimes(1));

    getAgentToolTrifecta.mockResolvedValue(LETHAL);
    rerender(<AgentTrifectaWarning agentId="a1" refreshKey={1} />);
    await waitFor(() => expect(getAgentToolTrifecta).toHaveBeenCalledTimes(2));
    expect(await screen.findByTestId("trifecta-warning")).toBeTruthy();

    getAgentToolTrifecta.mockResolvedValue(SAFE);
    rerender(<AgentTrifectaWarning agentId="a2" refreshKey={1} />);
    await waitFor(() => expect(getAgentToolTrifecta).toHaveBeenLastCalledWith("a2"));
    await waitFor(() => expect(screen.queryByTestId("trifecta-warning")).toBeNull());
  });

  it("says in plain language that sending actions always need approval when the agent is gated", async () => {
    getAgentToolTrifecta.mockResolvedValue({ ...LETHAL, enforcement: "require-approval", enforced: true, gated: true });
    render(<AgentTrifectaWarning agentId="a1" approvalRequired />);

    expect(await screen.findByTestId("trifecta-gated-note")).toBeTruthy();
    expect(screen.getByTestId("trifecta-gated-note").textContent).toBe("gatedNote");
    expect(screen.getByTestId("trifecta-enforced-note").textContent).toBe("enforcedNote");
    expect(screen.queryByTestId("trifecta-block-note")).toBeNull();
  });

  it("does not promise approval for every action once the agent is on a more autonomous tier", async () => {
    getAgentToolTrifecta.mockResolvedValue({ ...LETHAL, enforcement: "require-approval", enforced: true, gated: false });
    render(<AgentTrifectaWarning agentId="a1" approvalRequired={false} />);

    expect(await screen.findByTestId("trifecta-warning")).toBeTruthy();
    expect(screen.queryByTestId("trifecta-enforced-note")).toBeNull();
  });

  it("says the agent cannot be saved with this tool mix in block mode", async () => {
    getAgentToolTrifecta.mockResolvedValue({ ...LETHAL, enforcement: "block", enforced: true, gated: false });
    render(<AgentTrifectaWarning agentId="a1" />);

    expect((await screen.findByTestId("trifecta-block-note")).textContent).toBe("blockedNote");
    expect(screen.queryByTestId("trifecta-gated-note")).toBeNull();
    expect(screen.queryByTestId("trifecta-enforced-note")).toBeNull();
  });

  it("shows no enforcement lines for an older core or plain warn mode", async () => {
    getAgentToolTrifecta.mockResolvedValue(LETHAL);
    render(<AgentTrifectaWarning agentId="a1" />);

    expect(await screen.findByTestId("trifecta-warning")).toBeTruthy();
    expect(screen.queryByTestId("trifecta-gated-note")).toBeNull();
    expect(screen.queryByTestId("trifecta-block-note")).toBeNull();
    expect(screen.queryByTestId("trifecta-enforced-note")).toBeNull();
  });

  it("shows the gated line in warn mode when the exempt-list override applies", async () => {
    getAgentToolTrifecta.mockResolvedValue({ ...LETHAL, enforcement: "warn", enforced: false, gated: true });
    render(<AgentTrifectaWarning agentId="a1" onRequireApproval={() => {}} />);

    expect(await screen.findByTestId("trifecta-gated-note")).toBeTruthy();
    expect(screen.queryByTestId("trifecta-enforced-note")).toBeNull();
    expect(screen.getByTestId("trifecta-require-approval")).toBeTruthy();
  });

  it("shows nothing when enforcement is off (core reports empty legs)", async () => {
    getAgentToolTrifecta.mockResolvedValue({
      privateDataTools: [],
      untrustedContentTools: [],
      egressTools: [],
      unclassifiedTools: [],
      enforcement: "off",
      enforced: false,
      gated: false,
    });
    const { container } = render(<AgentTrifectaWarning agentId="a1" />);
    await waitFor(() => expect(getAgentToolTrifecta).toHaveBeenCalled());
    expect(container.firstChild).toBeNull();
  });

  it("shows the block message of a rejected save clearly, even when the report is hidden (404)", async () => {
    getAgentToolTrifecta.mockRejectedValue(Object.assign(new Error("Not found"), { status: 404 }));
    const message = "Blocked by the lethal-trifecta policy: remove one of the three kinds of tool.";
    render(<AgentTrifectaWarning agentId="a1" blockedMessage={message} />);

    const blocked = await screen.findByTestId("trifecta-save-blocked");
    expect(blocked.textContent).toContain("saveBlockedTitle");
    expect(blocked.textContent).toContain(message);
    expect(screen.queryByTestId("trifecta-warning")).toBeNull();
  });

  it("shows the block message above the warning when the mix is lethal, and drops it when cleared", async () => {
    getAgentToolTrifecta.mockResolvedValue({ ...LETHAL, enforcement: "block", enforced: true, gated: false });
    const { rerender } = render(<AgentTrifectaWarning agentId="a1" blockedMessage="Blocked by the lethal-trifecta policy: x" />);

    expect(await screen.findByTestId("trifecta-warning")).toBeTruthy();
    expect(screen.getByTestId("trifecta-save-blocked")).toBeTruthy();

    rerender(<AgentTrifectaWarning agentId="a1" blockedMessage={null} />);
    await waitFor(() => expect(screen.queryByTestId("trifecta-save-blocked")).toBeNull());
    expect(screen.getByTestId("trifecta-warning")).toBeTruthy();
  });

  it("renders nothing without an agent even if a block message is passed", () => {
    const { container } = render(<AgentTrifectaWarning agentId={null} blockedMessage="Blocked by the lethal-trifecta policy" />);
    expect(container.firstChild).toBeNull();
  });
});
