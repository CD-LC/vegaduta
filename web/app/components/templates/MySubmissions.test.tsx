// Creating and submitting a template needs MANAGE_TEMPLATES: it publishes
// workspace agent configuration to the cross-tenant gallery. /templates/mine
// is a public route without AuthProvider's permissions, so the page reads
// GET /api/me itself and, without the permission, hides the share and submit
// actions behind a note while edit and withdraw stay available.

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(() => ({
  listMyTemplates: vi.fn(),
  getMe: vi.fn(),
  submitTemplate: vi.fn(),
  withdrawTemplate: vi.fn(),
}));

vi.mock("../../lib/api", () => ({ api }));
vi.mock("../../lib/auth", () => ({ auth: { init: () => Promise.resolve({}), login: vi.fn() } }));
vi.mock("../../lib/toastBus", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
vi.mock("./SubmitTemplateFlow", () => ({
  default: ({ open }: { open: boolean }) => (open ? <div data-testid="submit-flow-open" /> : null),
}));
vi.mock("./EditSubmissionModal", () => ({ default: (): null => null }));

import MySubmissions from "./MySubmissions";

const DRAFT = {
  id: "t1",
  title: "Support bot",
  summary: "Answers tickets",
  status: "DRAFT",
  kind: "AGENT",
  category: "support",
};

describe("MySubmissions", () => {
  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it("hides share and submit and explains why without MANAGE_TEMPLATES", async () => {
    api.listMyTemplates.mockResolvedValue([DRAFT]);
    api.getMe.mockResolvedValue({ permissions: ["MANAGE_AGENTS"] });
    render(<MySubmissions autoOpenSubmit />);

    expect(await screen.findByTestId("templates-share-needs-permission")).toBeInTheDocument();
    expect(await screen.findByText("Support bot")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /share a template/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /submit for review/i })).toBeNull();
    expect(screen.getByRole("button", { name: /edit/i })).toBeInTheDocument();
    expect(screen.queryByTestId("submit-flow-open")).toBeNull();
  });

  it("offers share and submit to a holder of MANAGE_TEMPLATES", async () => {
    api.listMyTemplates.mockResolvedValue([DRAFT]);
    api.getMe.mockResolvedValue({ permissions: ["MANAGE_TEMPLATES"] });
    render(<MySubmissions />);

    expect(await screen.findByRole("button", { name: /submit for review/i })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /share a template/i })).toBeInTheDocument();
    expect(screen.queryByTestId("templates-share-needs-permission")).toBeNull();
  });

  it("opens the submit flow on arrival only when the permission is held", async () => {
    api.listMyTemplates.mockResolvedValue([]);
    api.getMe.mockResolvedValue({ permissions: ["MANAGE_TEMPLATES"] });
    render(<MySubmissions autoOpenSubmit />);

    expect(await screen.findByTestId("submit-flow-open")).toBeInTheDocument();
  });
});
