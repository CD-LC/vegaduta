// Creating a template needs MANAGE_TEMPLATES, so the agent and workflow
// screens only show "Share as template" to members who hold it.

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

let mockPermissions: string[] = [];
vi.mock("../AuthProvider", () => ({
  useAuth: () => ({ hasPermission: (name: string) => mockPermissions.includes(name) }),
}));
vi.mock("./SubmitTemplateFlow", () => ({ default: (): null => null }));

import ShareAsTemplateButton from "./ShareAsTemplateButton";

describe("ShareAsTemplateButton", () => {
  afterEach(() => {
    cleanup();
    mockPermissions = [];
  });

  it("renders nothing without MANAGE_TEMPLATES", () => {
    mockPermissions = ["MANAGE_AGENTS"];
    const { container } = render(<ShareAsTemplateButton sourceId="a1" />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders the button for a holder of MANAGE_TEMPLATES", () => {
    mockPermissions = ["MANAGE_TEMPLATES"];
    render(<ShareAsTemplateButton sourceId="a1" />);
    expect(screen.getByRole("button", { name: /share as template/i })).toBeInTheDocument();
  });
});
