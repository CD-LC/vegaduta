import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }), usePathname: () => "/workflows/templates" }));
vi.mock("../../components/AppShell", () => ({ default: ({ children }: any) => <div>{children}</div> }));

// Real English strings, same {placeholder} interpolation as the app's provider.
vi.mock("../../lib/i18n", () => ({
  useTranslations: (ns: string) => (key: string, values?: Record<string, unknown>) => {
    const raw = ((en as any)[ns] ?? {})[key];
    if (typeof raw !== "string") return `${ns}.${key}`;
    return raw.replace(/\{(\w+)\}/g, (m, k) => (values && k in values ? String(values[k]) : m));
  },
}));

import WorkflowTemplatesPage from "./page";
import { WORKFLOW_TEMPLATES, templateAgentSlots, templateNameKey } from "../../components/workflowTemplates";

describe("/workflows/templates gallery", () => {
  beforeEach(() => push.mockClear());

  it("pages the catalog instead of rendering every template at once", () => {
    render(<WorkflowTemplatesPage />);
    expect(WORKFLOW_TEMPLATES.length).toBeGreaterThan(12);
    expect(screen.getAllByRole("button", { name: "Use this template" })).toHaveLength(12);
    expect(screen.getByRole("button", { name: /next page/i })).toBeTruthy();
  });

  it("searches names, taglines and descriptions, and says so when nothing matches", () => {
    render(<WorkflowTemplatesPage />);
    const box = screen.getByRole("searchbox", { name: "Search templates" });
    fireEvent.change(box, { target: { value: "resume" } });
    expect(screen.getAllByRole("button", { name: "Use this template" })).toHaveLength(1);
    expect(screen.getByText("Resume Screening")).toBeTruthy();
    fireEvent.change(box, { target: { value: "zzz-no-such-template" } });
    expect(screen.queryAllByRole("button", { name: "Use this template" })).toHaveLength(0);
    expect(screen.getByRole("status").textContent).toMatch(/No templates match/);
  });

  it("filters by category", () => {
    render(<WorkflowTemplatesPage />);
    fireEvent.click(screen.getByRole("button", { name: "Customer support" }));
    const expected = WORKFLOW_TEMPLATES.filter((t) => t.category === "Customer support").length;
    expect(expected).toBeGreaterThan(0);
    expect(screen.getAllByRole("button", { name: "Use this template" })).toHaveLength(expected);
  });

  it("flags templates that need no agent and deep-links the chosen one", () => {
    render(<WorkflowTemplatesPage />);
    const noAgentTpl = WORKFLOW_TEMPLATES.find((tpl) => templateAgentSlots(tpl) === 0)!;
    const name = (en as any).workflowTemplates[templateNameKey(noAgentTpl.key)] as string;
    fireEvent.change(screen.getByRole("searchbox", { name: "Search templates" }), { target: { value: name } });
    expect(screen.getAllByText("No agent needed").length).toBeGreaterThanOrEqual(1);
    fireEvent.change(screen.getByRole("searchbox", { name: "Search templates" }), { target: { value: "Lead Capture" } });
    fireEvent.click(screen.getByRole("button", { name: "Use this template" }));
    expect(push).toHaveBeenCalledWith("/workflows?template=lead-capture-crm-workflow");
  });
});
