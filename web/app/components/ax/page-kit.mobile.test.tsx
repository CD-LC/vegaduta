// Prod signed-in pass 2026-10-05, bug 5: at 375px on /agents/quality?tab=datasets
// "New dataset", "Seed from golden set", "Configure gate" and two of the five
// tabs sat past the right edge with nothing to scroll. jsdom has no layout, so
// these tests pin the CLASS CONTRACT that makes the row responsive: below `sm`
// the Section header stacks and its actions wrap; the tab strip scrolls.

import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { Section, SECTION_ACTION_GROUP_CLASS } from "./page-kit";
import { Tabs, TabsList, TabsTrigger } from "../ui/tabs";

const classes = (el: Element | null) => new Set((el?.getAttribute("class") || "").split(/\s+/).filter(Boolean));

describe("Section header on a phone", () => {
  afterEach(cleanup);

  it("stacks below sm and only becomes a single row from sm up", () => {
    const { container } = render(
      <Section title="Eval datasets & regression runs" actions={<button>New dataset</button>}>
        body
      </Section>
    );
    const header = classes(container.querySelector('[data-slot="section-header"]'));
    expect(header.has("flex-col")).toBe(true);
    expect(header.has("sm:flex-row")).toBe(true);
    // The old header was an unconditional no-wrap row.
    expect(header.has("justify-between")).toBe(false);
  });

  it("lets the actions wrap and never forces them wider than the screen", () => {
    const { container } = render(
      <Section title="Promotion gate" actions={<button>Configure gate</button>}>
        body
      </Section>
    );
    const actions = classes(container.querySelector('[data-slot="section-actions"]'));
    expect(actions.has("flex-wrap")).toBe(true);
    expect(actions.has("max-w-full")).toBe(true);
    // `shrink-0` is what pushed the buttons off-screen; it may only apply from sm up.
    expect(actions.has("shrink-0")).toBe(false);
    expect(actions.has("sm:shrink-0")).toBe(true);
    expect(screen.getByRole("button", { name: "Configure gate" })).toBeTruthy();
  });

  it("the shared action-group class wraps too (sections that pass their own wrapper)", () => {
    const group = new Set(SECTION_ACTION_GROUP_CLASS.split(" "));
    expect(group.has("flex-wrap")).toBe(true);
    expect(group.has("max-w-full")).toBe(true);
  });
});

describe("TabsList on a phone", () => {
  afterEach(cleanup);

  it("scrolls sideways so every tab stays reachable", () => {
    render(
      <Tabs value="live" onValueChange={() => {}}>
        <TabsList>
          <TabsTrigger value="live">Live quality</TabsTrigger>
          <TabsTrigger value="datasets">Datasets & gate</TabsTrigger>
          <TabsTrigger value="simulations">Simulations</TabsTrigger>
          <TabsTrigger value="reliability">Reliability</TabsTrigger>
          <TabsTrigger value="solutions">Solutions</TabsTrigger>
        </TabsList>
      </Tabs>
    );
    const list = classes(screen.getByRole("tablist"));
    expect(list.has("overflow-x-auto")).toBe(true);
    expect(list.has("max-w-full")).toBe(true);
    expect(screen.getAllByRole("tab")).toHaveLength(5);
  });
});
