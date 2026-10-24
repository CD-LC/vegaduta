// The tab's links to this agent's prompts, approvals and turn timeline: where they point, how they are named for
// assistive technology, and that a link to a page the person's scopes would refuse is not offered.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import en from "../../../../messages/en.json";
import { axeViolations } from "../../../lib/axeTestKit";
import { I18nProvider } from "../../../lib/i18n";
import type { EffectiveSettings, SettingCatalogEntry } from "../../../lib/api/settings";
import snapshot from "../../../lib/api/fixtures/settingsCatalog.snapshot.json";
import { AuthContext } from "../../AuthProvider";
import { makeClient } from "../../settings/settingsTestKit";
import BehaviorLimitsTab from "./BehaviorLimitsTab";
import { agentApprovalsHref, agentPromptsHref, agentRelatedLinks, agentTurnsHref, openAgentHref, reachableAgentLinks } from "./agentLinks";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => "/agents",
  useSearchParams: () => new URLSearchParams(),
}));

const ENTRIES = snapshot.entries as unknown as SettingCatalogEntry[];
const MESSAGES = en as Record<string, unknown>;
const effective: EffectiveSettings = {
  values: Object.fromEntries(ENTRIES.map((e) => [e.key, { value: e.default, source: "default" as const, locked: false }])),
};
const ID = "7b0e9d4c-1c2f-4e7a-9a43-0d3b6f1b2a10";
const AGENT = { id: ID, name: "Support bot", providerName: "OPENAI", model: "gpt-x", autonomyTier: "SEMI_AUTONOMOUS", accessLevel: "EDITOR" };

type Can = { isAdmin: boolean; hasPermission: (name: string) => boolean };

function show(can: Can | null) {
  const tree = (
    <I18nProvider locale="en" messages={MESSAGES} enMessages={MESSAGES} complete>
      <BehaviorLimitsTab agent={AGENT} client={makeClient({ catalog: ENTRIES, effective })} />
    </I18nProvider>
  );
  return render(can ? <AuthContext.Provider value={can as never}>{tree}</AuthContext.Provider> : tree);
}

afterEach(() => cleanup());

describe("agent link model", () => {
  it("scopes each link to the agent and encodes the id", () => {
    expect(agentPromptsHref("a b/c")).toBe("/admin/instructions?agent=a%20b%2Fc");
    expect(agentApprovalsHref(ID)).toBe(`/admin/approvals?agent=${ID}`);
    expect(agentTurnsHref(ID)).toBe(`/agents/turns?agentId=${ID}`);
  });

  it("takes each link's scope from the control areas, the one list of who may open what", () => {
    const perms = Object.fromEntries(agentRelatedLinks(ID).map((l) => [l.id, l.perm]));
    expect(perms).toEqual({ prompts: "MANAGE_TENANT_SETTINGS", approvals: "MANAGE_APPROVALS", turns: "VIEW_AUDIT_LOG" });
  });

  it("hides links whose scope is missing, except for a tenant admin, and hides nothing without a permission set", () => {
    const links = agentRelatedLinks(ID);
    const ids = (can: Can | null) => reachableAgentLinks(links, can).map((l) => l.id);
    expect(ids({ isAdmin: false, hasPermission: (n) => n === "MANAGE_APPROVALS" })).toEqual(["approvals"]);
    expect(ids({ isAdmin: true, hasPermission: () => false })).toEqual(["prompts", "approvals", "turns"]);
    expect(ids(null)).toEqual(["prompts", "approvals", "turns"]);
  });

  it("carries ?section and ?focus through the choice of agent on the index", () => {
    expect(openAgentHref(ID)).toBe(`/agents?agentId=${ID}`);
    expect(openAgentHref(ID, { section: "behavior", focus: "agent.limits.maxToolSteps" })).toBe(
      `/agents?agentId=${ID}&section=behavior&focus=agent.limits.maxToolSteps`
    );
  });
});

describe("BehaviorLimitsTab agent links", () => {
  it("offers prompts, approvals and the turn timeline for this agent, each named with the agent", async () => {
    show({ isAdmin: true, hasPermission: () => true });
    const prompts = await screen.findByRole("link", { name: /Prompts for Support bot/ });
    expect(prompts).toHaveAttribute("href", `/admin/instructions?agent=${ID}`);
    expect(screen.getByRole("link", { name: /Approvals for Support bot/ })).toHaveAttribute("href", `/admin/approvals?agent=${ID}`);
    expect(screen.getByRole("link", { name: /Turn timeline for Support bot/ })).toHaveAttribute("href", `/agents/turns?agentId=${ID}`);
    // Links are real anchors in the tab order, not buttons that navigate.
    expect(prompts.tagName).toBe("A");
    expect(prompts).not.toHaveAttribute("tabindex", "-1");
  });

  it("offers only the pages the person's scopes allow", async () => {
    show({ isAdmin: false, hasPermission: (n) => n === "VIEW_AUDIT_LOG" });
    await screen.findByRole("link", { name: /Turn timeline for Support bot/ });
    expect(screen.queryByTestId("agent-link-prompts")).toBeNull();
    expect(screen.queryByTestId("agent-link-approvals")).toBeNull();
  });

  it("has no accessibility violations with the links shown", async () => {
    const { container } = show({ isAdmin: true, hasPermission: () => true });
    await screen.findByRole("link", { name: /Prompts for Support bot/ });
    expect(await axeViolations(container)).toEqual([]);
  });
});
