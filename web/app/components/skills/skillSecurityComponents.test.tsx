// The skill-security surfaces: the review banner ("Changed since you approved it") and the scan
// findings list. i18n is mocked to echo keys, so these assert behaviour (what is shown, what the
// buttons send) rather than copy; copy is guarded by lib/skillSecurityI18n.test.ts.

import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string, v?: Record<string, string | number>) =>
    v ? `${key}:${Object.values(v).join(",")}` : key,
}));

import type { SkillScanReport, SkillSecurityReport } from "../../lib/api/skillSecurity";
import SkillReviewBanner from "./SkillReviewBanner";
import SkillScanFindings from "./SkillScanFindings";

const scan = (over: Partial<SkillScanReport>): SkillScanReport => ({
  riskLevel: "none",
  findings: [],
  truncated: false,
  scannerVersion: 1,
  ...over,
});

const report = (over: Partial<SkillSecurityReport>): SkillSecurityReport => ({
  name: "notes",
  source: "CUSTOM",
  status: "NEEDS_REVIEW",
  blocked: false,
  enforcing: false,
  contentSha256: "a".repeat(64),
  approvedSha256: "b".repeat(64),
  approvedBy: "alice",
  approvedAt: "2026-10-01T10:00:00Z",
  changes: ["Instructions (SKILL.md body) changed", "Added file: scripts/run.py"],
  scan: scan({}),
  stagedScan: null,
  attachedAgentIds: [],
  disclaimer: "",
  ...over,
});

describe("SkillReviewBanner", () => {
  it("lists what changed and lets an admin approve or reject the version they are looking at", () => {
    const onApprove = vi.fn();
    const onReject = vi.fn();
    render(<SkillReviewBanner report={report({})} canManage busy={false} onApprove={onApprove} onReject={onReject} />);

    expect(screen.getByText("reviewBannerTitle")).toBeInTheDocument();
    expect(screen.getByText("Instructions (SKILL.md body) changed")).toBeInTheDocument();
    expect(screen.getByText("Added file: scripts/run.py")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "reviewApprove" }));
    expect(onApprove).toHaveBeenCalledWith(false);
    fireEvent.click(screen.getByRole("button", { name: "reviewReject" }));
    expect(onReject).toHaveBeenCalledTimes(1);
  });

  it("makes a high-risk approval a deliberate act", () => {
    const onApprove = vi.fn();
    render(
      <SkillReviewBanner
        report={report({ scan: scan({ riskLevel: "high" }) })}
        canManage
        busy={false}
        onApprove={onApprove}
        onReject={() => {}}
      />
    );
    const approve = screen.getByRole("button", { name: "reviewApprove" });
    expect(approve).toBeDisabled();

    fireEvent.click(screen.getByRole("checkbox"));
    expect(approve).toBeEnabled();
    fireEvent.click(approve);
    expect(onApprove).toHaveBeenCalledWith(true);
  });

  it("shows members the state but no approve or reject controls", () => {
    render(<SkillReviewBanner report={report({})} canManage={false} busy={false} onApprove={() => {}} onReject={() => {}} />);
    expect(screen.queryByRole("button", { name: "reviewApprove" })).not.toBeInTheDocument();
    expect(screen.getByText("reviewAdminOnly")).toBeInTheDocument();
  });

  it("is honest about whether anything is actually blocked", () => {
    const { rerender } = render(
      <SkillReviewBanner report={report({ blocked: false })} canManage busy={false} onApprove={() => {}} onReject={() => {}} />
    );
    expect(screen.getByText("reviewWarnOnlyNote")).toBeInTheDocument();
    rerender(
      <SkillReviewBanner report={report({ blocked: true })} canManage busy={false} onApprove={() => {}} onReject={() => {}} />
    );
    expect(screen.getByText("reviewEnforcedNote")).toBeInTheDocument();
  });

  it("uses the not-approved wording for a high-risk skill that was never approved", () => {
    render(
      <SkillReviewBanner
        report={report({ status: "UNPINNED", approvedAt: null, approvedBy: null, changes: [], scan: scan({ riskLevel: "high" }) })}
        canManage
        busy={false}
        onApprove={() => {}}
        onReject={() => {}}
      />
    );
    expect(screen.getByText("reviewBannerUnapprovedTitle")).toBeInTheDocument();
    expect(screen.queryByText("reviewChangesHeading")).not.toBeInTheDocument();
  });
});

describe("SkillScanFindings", () => {
  it("says a clean scan is not a guarantee instead of calling the skill safe", () => {
    render(<SkillScanFindings report={scan({})} />);
    expect(screen.getByTestId("skill-risk-badge")).toHaveAttribute("data-risk", "none");
    expect(screen.getByText("scanNoFindings")).toBeInTheDocument();
    expect(screen.getByText("scanDisclaimer")).toBeInTheDocument();
  });

  it("lists the most severe finding first with where it was found and its excerpt", () => {
    render(
      <SkillScanFindings
        report={scan({
          riskLevel: "high",
          findings: [
            { rule: "embedded-url", severity: "low", excerpt: "https://example.com", where: "body" },
            { rule: "override-instructions", severity: "high", excerpt: "ignore all previous instructions", where: "description" },
          ],
        })}
        showDisclaimer={false}
      />
    );
    const items = screen.getAllByRole("listitem");
    expect(items).toHaveLength(2);
    expect(within(items[0]).getByText("ruleOverrideInstructions")).toBeInTheDocument();
    expect(within(items[0]).getByText("ignore all previous instructions")).toBeInTheDocument();
    expect(within(items[0]).getByText(/scanWhereDescription/)).toBeInTheDocument();
    expect(within(items[1]).getByText("ruleEmbeddedUrl")).toBeInTheDocument();
    expect(screen.queryByText("scanDisclaimer")).not.toBeInTheDocument();
  });

  it("never renders a raw rule id from a newer server as copy", () => {
    render(
      <SkillScanFindings
        report={scan({
          riskLevel: "medium",
          findings: [{ rule: "brand-new-rule", severity: "medium", excerpt: "x", where: "file:docs/a.md" }],
        })}
      />
    );
    expect(screen.getByText("ruleUnknown")).toBeInTheDocument();
    expect(screen.queryByText("brand-new-rule")).not.toBeInTheDocument();
  });

  it("says how many findings it left out", () => {
    const findings = Array.from({ length: 15 }, (_, i) => ({
      rule: "embedded-url",
      severity: "low" as const,
      excerpt: `https://example.com/${i}`,
      where: "body",
    }));
    render(<SkillScanFindings report={scan({ riskLevel: "low", findings })} />);
    expect(screen.getAllByRole("listitem")).toHaveLength(12);
    expect(screen.getByText("scanMoreFindings:3")).toBeInTheDocument();
  });
});
