import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import TemplateStatusBadge from "./TemplateStatusBadge";

describe("TemplateStatusBadge", () => {
  it.each(["DRAFT", "PENDING", "APPROVED", "REJECTED", "WITHDRAWN"])("renders %s", (s) => {
    render(<TemplateStatusBadge status={s} />);
    expect(screen.getByText(s)).toBeInTheDocument();
  });

  it("uses distinct colour classes for approved vs rejected", () => {
    const { rerender } = render(<TemplateStatusBadge status="APPROVED" />);
    expect(screen.getByText("APPROVED").className).toMatch(/success/);
    rerender(<TemplateStatusBadge status="REJECTED" />);
    expect(screen.getByText("REJECTED").className).toMatch(/destructive/);
  });

  it("falls back to UNKNOWN when status is missing", () => {
    render(<TemplateStatusBadge />);
    expect(screen.getByText("UNKNOWN")).toBeInTheDocument();
  });

  it("shows an unrecognised status verbatim with the neutral style", () => {
    render(<TemplateStatusBadge status="ARCHIVED" />);
    expect(screen.getByText("ARCHIVED").className).toMatch(/text-muted-foreground/);
  });
});
