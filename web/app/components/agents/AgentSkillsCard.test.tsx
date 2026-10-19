// The attach confirmation on an agent's Skills card: a custom skill that is flagged, changed or
// not yet approved shows its scan result before it is attached (the first attach is what pins its
// content); an already-approved clean skill attaches straight away. i18n is mocked to echo keys, so
// this asserts behaviour, not copy (copy is guarded by lib/skillSecurityI18n.test.ts).

import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, v?: Record<string, string | number>) =>
    v ? `${key}:${Object.values(v).join(",")}` : key,
}));

vi.mock("next/link", () => ({
  default: ({ href, children }: { href: string; children?: ReactNode }) => <a href={href}>{children}</a>,
}));

vi.mock("../AuthProvider", () => ({
  useAuth: () => ({ hasPermission: () => false }),
}));

const mockApi = vi.hoisted(() => ({
  getAgentSkills: vi.fn(),
  listSkills: vi.fn(),
  getSkillSecurityOverview: vi.fn(),
  getSkillSecurityReport: vi.fn(),
  updateAgentSkills: vi.fn(),
}));

vi.mock("../../lib/api", () => ({ api: mockApi }));

import AgentSkillsCard from "./AgentSkillsCard";

function skill(name: string) {
  return { id: name, name, description: `${name} description`, source: "CUSTOM", enabled: true, fileCount: 0 };
}

function summary(name: string, status: string, riskLevel: string) {
  return { name, source: "CUSTOM", status, riskLevel, findingCount: 0, blocked: false };
}

describe("AgentSkillsCard attach confirmation", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    const settings = { skillsEnabled: true, allowedSkillNames: ["alpha-skill"], activeSkills: [] as unknown[] };
    mockApi.getAgentSkills.mockResolvedValue(settings);
    mockApi.listSkills.mockResolvedValue([skill("alpha-skill"), skill("beta-skill"), skill("gamma-skill")]);
    mockApi.getSkillSecurityOverview.mockResolvedValue({
      enabled: true,
      enforcing: false,
      skills: [
        summary("alpha-skill", "APPROVED", "none"),
        summary("beta-skill", "UNPINNED", "none"),
        summary("gamma-skill", "APPROVED", "none"),
      ],
    });
    mockApi.getSkillSecurityReport.mockResolvedValue(null);
    mockApi.updateAgentSkills.mockImplementation(
      async (_id: string, body: { skillsEnabled: boolean; allowedSkillNames: string[] }) => ({
        ...body,
        activeSkills: [],
      })
    );
  });

  const checkbox = async (name: string) => {
    const boxes = await screen.findAllByRole("checkbox");
    const match = boxes.find((b) => (b.closest("label")?.textContent ?? "").includes(name));
    if (!match) throw new Error(`no checkbox for ${name}`);
    return match;
  };

  it("shows the scan result before attaching a skill nobody has approved yet", async () => {
    render(<AgentSkillsCard agentId="agent-1" locked={false} />);

    fireEvent.click(await checkbox("beta-skill"));

    expect(await screen.findByText("attachTitle:beta-skill")).toBeInTheDocument();
    expect(screen.getByText("attachBodyFirst")).toBeInTheDocument();
    expect(mockApi.updateAgentSkills).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "attachConfirm" }));
    await waitFor(() =>
      expect(mockApi.updateAgentSkills).toHaveBeenCalledWith("agent-1", {
        skillsEnabled: true,
        allowedSkillNames: ["alpha-skill", "beta-skill"],
      })
    );
  });

  it("attaches an already-approved clean skill without a prompt", async () => {
    render(<AgentSkillsCard agentId="agent-1" locked={false} />);

    fireEvent.click(await checkbox("gamma-skill"));

    await waitFor(() => expect(mockApi.updateAgentSkills).toHaveBeenCalledTimes(1));
    expect(screen.queryByText("attachTitle:gamma-skill")).not.toBeInTheDocument();
  });
});
