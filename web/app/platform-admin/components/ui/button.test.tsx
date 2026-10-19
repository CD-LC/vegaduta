import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { Button } from "./button";

describe("Button", () => {
  it("defaults to type=button so it never submits a form by accident", () => {
    render(<Button>Go</Button>);
    expect(screen.getByRole("button", { name: "Go" })).toHaveAttribute("type", "button");
  });

  it("allows an explicit type", () => {
    render(<Button type="submit">Save</Button>);
    expect(screen.getByRole("button")).toHaveAttribute("type", "submit");
  });

  it("applies variant and size classes and merges custom className last", () => {
    render(<Button variant="destructive" size="sm" className="h-20">X</Button>);
    const cls = screen.getByRole("button").className;
    expect(cls).toMatch(/bg-destructive/);
    expect(cls).toMatch(/text-xs/);
    expect(cls).toMatch(/h-20/);
    expect(cls).not.toMatch(/\bh-8\b/);
  });

  it("fires onClick unless disabled", () => {
    const fn = vi.fn();
    const { rerender } = render(<Button onClick={fn}>Hit</Button>);
    fireEvent.click(screen.getByRole("button"));
    expect(fn).toHaveBeenCalledTimes(1);
    rerender(<Button onClick={fn} disabled>Hit</Button>);
    fireEvent.click(screen.getByRole("button"));
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it("asChild styles the child element without nesting a button or setting type", () => {
    render(<Button asChild><a href="/x">Link</a></Button>);
    const a = screen.getByRole("link", { name: "Link" });
    expect(a).toHaveAttribute("data-slot", "button");
    expect(a).not.toHaveAttribute("type");
    expect(screen.queryByRole("button")).toBeNull();
  });
});
