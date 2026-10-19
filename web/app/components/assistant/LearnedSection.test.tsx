import { describe, expect, it, vi } from "vitest";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import LearnedSection from "./LearnedSection";
import type { Learned } from "./useLearned";
import type { LearningProposal } from "../../lib/api/assistant";

const proposal = (over: Partial<LearningProposal>): LearningProposal => ({
  id: "p1",
  agentId: null,
  kind: "PREFERENCE",
  title: "Short answers",
  body: "Keep answers short.",
  status: "PENDING",
  createdAt: "",
  applicable: true,
  ...over,
});

vi.mock("../../lib/i18n", () => ({ useTranslations: () => (k: string) => k }));

const base = (over: Partial<Learned>): Learned => ({
  loaded: true,
  pending: [],
  active: [],
  facts: [],
  proposals: [],
  approveProposal: vi.fn().mockResolvedValue(undefined),
  rejectProposal: vi.fn().mockResolvedValue(undefined),
  approve: vi.fn().mockResolvedValue(undefined),
  reject: vi.fn().mockResolvedValue(undefined),
  forgetFact: vi.fn().mockResolvedValue(undefined),
  editFact: vi.fn().mockResolvedValue(undefined),
  ...over,
});

describe("LearnedSection", () => {
  it("renders nothing before the first read", () => {
    const { container } = render(<LearnedSection learned={base({ loaded: false })} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("approves and rejects a drafted skill", async () => {
    const learned = base({
      pending: [{ id: "s1", name: "book-flight", description: "d", status: "PENDING", origin: "LEARNED", version: 1, uses: 0, agentIds: [], updatedAt: "" }],
    });
    render(<LearnedSection learned={learned} />);
    fireEvent.click(screen.getByText("learnedApprove"));
    expect(learned.approve).toHaveBeenCalledWith("s1");
    await waitFor(() => expect(screen.getByText("learnedReject").closest("button")).not.toBeDisabled());
    fireEvent.click(screen.getByText("learnedReject"));
    expect(learned.reject).toHaveBeenCalledWith("s1");
  });

  it("edits and forgets a remembered fact", async () => {
    const fact = { id: "f1", scope: "shared" as const, content: "likes tea" };
    const learned = base({ facts: [fact] });
    render(<LearnedSection learned={learned} />);
    fireEvent.click(screen.getByText("learnedForget"));
    expect(learned.forgetFact).toHaveBeenCalledWith(fact);
    await waitFor(() => expect(screen.getByText("learnedForget").closest("button")).not.toBeDisabled());
    fireEvent.click(screen.getByText("learnedEdit"));
    fireEvent.change(screen.getByLabelText("learnedEditFact"), { target: { value: "likes coffee" } });
    fireEvent.click(screen.getByText("learnedSave"));
    expect(learned.editFact).toHaveBeenCalledWith(fact, "likes coffee");
  });

  it("groups proposals by kind and approves one with the edited text", async () => {
    const learned = base({
      proposals: [proposal({}), proposal({ id: "p2", kind: "LESSON", title: "t", body: "Check the date twice." })],
    });
    render(<LearnedSection learned={learned} />);
    const kinds = screen.getAllByTestId(/^learned-proposals-/).map((el) => el.getAttribute("data-testid"));
    expect(kinds).toEqual(["learned-proposals-LESSON", "learned-proposals-PREFERENCE"]);
    const row = screen.getByTestId("learned-proposal-p1");
    fireEvent.click(row.querySelector("button:nth-of-type(3)") as HTMLElement);
    fireEvent.change(screen.getByLabelText("learnedEditProposal"), { target: { value: "Keep answers very short." } });
    fireEvent.click(screen.getAllByText("learnedApprove")[1]);
    expect(learned.approveProposal).toHaveBeenCalledWith("p1", "Keep answers very short.");
  });

  it("shows why an approval was refused and rejects without an edit", async () => {
    const learned = base({
      proposals: [proposal({})],
      approveProposal: vi.fn().mockRejectedValue(new Error("Your profile is full")),
    });
    render(<LearnedSection learned={learned} />);
    fireEvent.click(screen.getByText("learnedApprove"));
    expect(await screen.findByRole("alert")).toHaveTextContent("Your profile is full");
    expect(learned.approveProposal).toHaveBeenCalledWith("p1", undefined);
    await waitFor(() => expect(screen.getByText("learnedReject").closest("button")).not.toBeDisabled());
    fireEvent.click(screen.getByText("learnedReject"));
    expect(learned.rejectProposal).toHaveBeenCalledWith("p1");
  });

  it("cannot approve a kind nothing applies yet", () => {
    render(<LearnedSection learned={base({ proposals: [proposal({ kind: "ROUTINE", applicable: false })] })} />);
    expect(screen.getByText("learnedApprove").closest("button")).toBeDisabled();
    expect(screen.getByText("learnedProposalNotYet")).toBeInTheDocument();
  });
});
