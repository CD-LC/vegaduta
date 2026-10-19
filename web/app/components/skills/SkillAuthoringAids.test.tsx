import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";

vi.mock("../../lib/api", () => ({ api: { listAgents: vi.fn(() => Promise.resolve([])) } }));

import { SkillFilterBar, SkillLintPanel, SkillPromptPreview, SkillTemplatePicker } from "./SkillAuthoringAids";
import { SKILL_TEMPLATES } from "../../lib/skillAuthoring";

describe("SkillAuthoringAids", () => {
  it("lint panel reports server-refused problems and passes a clean skill", () => {
    const { rerender } = render(<SkillLintPanel draft="# nothing" existingNames={[]} editingName={null} />);
    expect(screen.getByTestId("skill-lint").textContent).toMatch(/1 problem/);
    rerender(
      <SkillLintPanel
        draft={"---\nname: ok-skill\ndescription: Does a thing for you. Use when the user asks for the thing.\n---\n\n# Hi\nbody"}
        existingNames={[]}
        editingName={null}
      />
    );
    expect(screen.getByTestId("skill-lint").textContent).toMatch(/Checks passed/);
  });

  it("prompt preview shows the one line agents get, truncated to 12 words", () => {
    render(
      <SkillPromptPreview
        draft={"---\nname: long-one\ndescription: a b c d e f g h i j k l m n\n---\n\nbody"}
      />
    );
    expect(screen.getByTestId("skill-prompt-preview").textContent).toContain("- long-one: a b c d e f g h i j k l...");
  });

  it("template picker hands back the template text", () => {
    const onPick = vi.fn();
    render(<SkillTemplatePicker draft={SKILL_TEMPLATES[0].skillMd} onPick={onPick} />);
    fireEvent.click(screen.getByText(SKILL_TEMPLATES[1].title));
    expect(onPick).toHaveBeenCalledWith(SKILL_TEMPLATES[1].skillMd);
  });

  it("filter bar reports the query and filter chosen", () => {
    const onQuery = vi.fn();
    const onFilter = vi.fn();
    render(<SkillFilterBar query="" onQuery={onQuery} filter="all" onFilter={onFilter} shown={2} total={9} />);
    fireEvent.change(screen.getByLabelText("Search skills"), { target: { value: "quiz" } });
    fireEvent.click(screen.getByText("Needs attention"));
    expect(onQuery).toHaveBeenCalledWith("quiz");
    expect(onFilter).toHaveBeenCalledWith("attention");
  });
});
