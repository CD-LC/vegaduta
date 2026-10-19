import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import en from "../../../messages/en.json";
vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string): string =>
    (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key] ?? `${namespace}.${key}`,
}));

import SkillSelfCheckBadge from "./SkillSelfCheckBadge";
import { normalizeSkill } from "../../lib/api/skillLibrary";

describe("SkillSelfCheckBadge", () => {
  it("shows passed and weak with the reason as the tooltip", () => {
    const { rerender } = render(<SkillSelfCheckBadge check={{ verdict: "PASS", reason: "Same method." }} />);
    expect(screen.getByTestId("self-check-pass")).toHaveAttribute("title", "Same method.");
    expect(screen.getByTestId("self-check-pass")).toHaveTextContent("Checked: passed");
    rerender(<SkillSelfCheckBadge check={{ verdict: "WEAK", reason: "" }} />);
    expect(screen.getByTestId("self-check-weak")).toBeInTheDocument();
  });

  it("renders nothing for an unchecked skill", () => {
    const { container } = render(<SkillSelfCheckBadge />);
    expect(container).toBeEmptyDOMElement();
  });

  it("keeps only PASS and WEAK from the server row", () => {
    const base = { id: "a", name: "n" };
    expect(normalizeSkill({ ...base, selfCheck: { verdict: "WEAK", reason: "Vague" } })?.selfCheck).toEqual({ verdict: "WEAK", reason: "Vague" });
    expect(normalizeSkill({ ...base, selfCheck: { verdict: "FAIL", reason: "x" } })?.selfCheck).toBeUndefined();
    expect(normalizeSkill({ ...base, selfCheck: null })?.selfCheck).toBeUndefined();
    expect(normalizeSkill(base)?.selfCheck).toBeUndefined();
  });
});
