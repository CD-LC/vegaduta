import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import RichText from "./RichText";

describe("RichText console page links", () => {
  it("renders a console path as an in-app link", () => {
    render(<RichText text="Open [Tools](/tools) then [Slack](/admin/channels?channel=slack)." />);
    expect(screen.getByRole("link", { name: "Tools" }).getAttribute("href")).toBe("/tools");
    const slack = screen.getByRole("link", { name: "Slack" });
    expect(slack.getAttribute("href")).toBe("/admin/channels?channel=slack");
    expect(slack.getAttribute("target")).toBeNull();
  });

  it("renders the path create_workflow returns as an in-app link", () => {
    const id = "3f1c2a9e-5b7d-4c1e-9a10-0d5e7f2b6c44";
    render(<RichText text={`Draft saved: [Weekly digest](/workflows/${id}).`} />);
    expect(screen.getByRole("link", { name: "Weekly digest" }).getAttribute("href")).toBe(`/workflows/${id}`);
  });

  it.each(["//evil.example/x", "/\\evil.example", "/api/secrets", "javascript:alert(1)"])("refuses %s", (href) => {
    render(<RichText text={`[go](${href})`} />);
    expect(screen.queryByRole("link")).toBeNull();
  });
});
