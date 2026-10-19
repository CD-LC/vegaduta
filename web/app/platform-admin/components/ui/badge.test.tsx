import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { Badge, badgeVariants } from "./badge";

describe("Badge", () => {
  it("renders a span with the default variant", () => {
    render(<Badge>New</Badge>);
    const el = screen.getByText("New");
    expect(el.tagName).toBe("SPAN");
    expect(el).toHaveAttribute("data-slot", "badge");
    expect(el.className).toMatch(/bg-primary/);
  });

  it("applies the requested variant", () => {
    render(<Badge variant="destructive">Bad</Badge>);
    expect(screen.getByText("Bad").className).toMatch(/bg-destructive/);
  });

  it("badgeVariants resolves classes without a component", () => {
    expect(badgeVariants({ variant: "outline" })).toMatch(/border-border/);
  });

  it("asChild renders the child element", () => {
    render(<Badge asChild><a href="/x">Tag</a></Badge>);
    expect(screen.getByRole("link", { name: "Tag" })).toHaveAttribute("data-slot", "badge");
  });
});
