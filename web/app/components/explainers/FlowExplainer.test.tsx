import { describe, expect, it, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => ({
  useTranslations: (namespace: string) => (key: string) =>
    (en as unknown as Record<string, Record<string, string>>)[namespace]?.[key] ?? `${namespace}.${key}`,
}));

import FlowExplainer from "./FlowExplainer";

const steps = [
  { id: "ask", title: "The agent asks to use a tool", description: "Every tool call goes through the same gate." },
  {
    id: "check",
    title: "The policy is checked",
    outcomes: [
      { label: "Allow", description: "the call runs", state: "success" as const },
      { label: "Block", description: "the call is refused", state: "error" as const },
      { label: "Needs approval", state: "warning" as const },
    ],
  },
  { id: "log", title: "The decision is logged" },
];

describe("FlowExplainer", () => {
  it("sits inside a native disclosure labelled 'How this works', closed by default", () => {
    const { container } = render(<FlowExplainer steps={steps} />);
    const details = container.querySelector("details")!;
    expect(details).not.toBeNull();
    expect(details.hasAttribute("open")).toBe(false);
    expect(details.querySelector("summary")?.textContent).toBe("How this works");
  });

  it("opens by default when asked, and takes a caller title", () => {
    const { container } = render(<FlowExplainer steps={steps} defaultOpen title="How approvals work" />);
    expect(container.querySelector("details")!.hasAttribute("open")).toBe(true);
    expect(container.querySelector("summary")?.textContent).toBe("How approvals work");
  });

  it("renders the steps in order as an ordered list", () => {
    const { container } = render(<FlowExplainer steps={steps} defaultOpen />);
    const list = container.querySelector("ol")!;
    expect(list).not.toBeNull();
    expect(list.getAttribute("aria-label")).toBe("How this works");
    const items = [...list.children];
    expect(items.map((li) => li.tagName)).toEqual(["LI", "LI", "LI"]);
    expect(items.map((li) => li.querySelector("p")?.textContent)).toEqual([
      "The agent asks to use a tool",
      "The policy is checked",
      "The decision is logged",
    ]);
    expect(screen.getByText("Every tool call goes through the same gate.")).toBeInTheDocument();
  });

  it("renders branch outcomes as a nested list under their step, each with a text label", () => {
    const { container } = render(<FlowExplainer steps={steps} defaultOpen />);
    const second = container.querySelectorAll("ol > li")[1] as HTMLElement;
    const outcomes = within(second).getAllByRole("listitem");
    expect(outcomes.map((li) => li.textContent)).toEqual([
      "Allow - the call runs",
      "Block - the call is refused",
      "Needs approval",
    ]);
    expect(container.querySelectorAll("ol > li")[0].querySelector("ul")).toBeNull();
  });

  it("hides the connectors and step numbers from assistive tech, with one connector between each pair", () => {
    const { container } = render(<FlowExplainer steps={steps} defaultOpen />);
    const connectors = container.querySelectorAll("[data-flow-connector]");
    expect(connectors).toHaveLength(steps.length - 1);
    connectors.forEach((connector) => expect(connector.closest("[aria-hidden='true']")).not.toBeNull());
  });

  it("drops its only transition under reduced motion", () => {
    const { container } = render(<FlowExplainer steps={steps} />);
    const animated = [...container.querySelectorAll("[class*='transition']")];
    expect(animated.length).toBeGreaterThan(0);
    animated.forEach((node) => expect(node.getAttribute("class")).toContain("motion-reduce:transition-none"));
    expect(container.innerHTML).not.toMatch(/animate-/);
  });

  it("renders nothing without steps, and copes with a single step", () => {
    expect(render(<FlowExplainer steps={[]} />).container).toBeEmptyDOMElement();
    const { container } = render(<FlowExplainer steps={[{ title: "Only step" }]} defaultOpen />);
    expect(container.querySelectorAll("ol > li")).toHaveLength(1);
    expect(container.querySelector("[data-flow-connector]")).toBeNull();
  });
});
