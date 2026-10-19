import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { I18nProvider } from "../../lib/i18n";
import en from "../../../messages/en.json";
import type { UpstreamSchemaNode } from "../../lib/api/workflowExpressions";

let schema: UpstreamSchemaNode[] = [];
vi.mock("./useExpressionData", () => ({
  useUpstreamSchema: (wf: string | undefined) => ({ nodes: wf ? schema : [], loading: false, refresh: () => {} }),
  useExpressionFunctions: (): never[] => [],
}));

import DataPicker from "./DataPicker";
import { ExpressionFocusProvider, useExpressionFocus } from "./ExpressionFocus";

function Registrar({ onInsert }: { onInsert: (e: string) => void }) {
  const f = useExpressionFocus()!;
  return (
    <button
      type="button"
      onClick={() => {
        f.register({ nodeId: "n2", field: "url", insert: onInsert });
        f.markActive("n2", "url");
      }}
    >
      focus-field
    </button>
  );
}

function setup(onInsert = vi.fn()) {
  render(
    <I18nProvider locale="en" messages={en} enMessages={en}>
      <ExpressionFocusProvider>
        <Registrar onInsert={onInsert} />
        <DataPicker workflowId="wf1" nodeId="n2" />
      </ExpressionFocusProvider>
    </I18nProvider>,
  );
  fireEvent.click(screen.getByRole("button", { name: "Data from earlier steps" }));
  return onInsert;
}

afterEach(cleanup);

describe("DataPicker", () => {
  it("renders nothing without a focus provider", () => {
    schema = [];
    const { container } = render(
      <I18nProvider locale="en" messages={en} enMessages={en}>
        <DataPicker workflowId="wf1" nodeId="n2" />
      </I18nProvider>,
    );
    expect(container.textContent).toBe("");
  });

  it("says so when no upstream node has data", () => {
    schema = [];
    setup();
    expect(screen.getByText(/No earlier steps with data yet/)).toBeTruthy();
  });

  it("inserts nothing until a field has been focused, then inserts the path expression", () => {
    schema = [
      {
        nodeId: "n1",
        label: "Fetch",
        source: "pinned",
        paths: [
          { path: "items[0].price", type: "number", sample: "9" },
          { path: "items[0].name", type: "string", sample: "Pen" },
        ],
      },
    ];
    const onInsert = setup();
    fireEvent.click(screen.getByRole("button", { name: "Expand Fetch" }));
    const nodePick = screen.getByRole("button", { name: /^Fetch/ });
    expect((nodePick as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "focus-field" }));
    expect((screen.getByRole("button", { name: /^Fetch/ }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: /^Fetch/ }));
    expect(onInsert).toHaveBeenLastCalledWith('$node["Fetch"]');

    fireEvent.click(screen.getByRole("button", { name: "Expand items" }));
    fireEvent.click(screen.getByRole("button", { name: "Expand [0]" }));
    fireEvent.click(screen.getByRole("button", { name: /^price/ }));
    expect(onInsert).toHaveBeenLastCalledWith('$node["Fetch"].items[0].price');
  });

  it("uses the id form for unlabeled and duplicate-labeled nodes", () => {
    schema = [
      { nodeId: "n1", label: "", source: "lastRun", paths: [{ path: "a", type: "string", sample: "x" }] },
      { nodeId: "n3", label: "Dup", source: "pinned", paths: [{ path: "b", type: "string", sample: "y" }] },
      { nodeId: "n4", label: "Dup", source: "pinned", paths: [] },
    ];
    const onInsert = setup();
    fireEvent.click(screen.getByRole("button", { name: "focus-field" }));
    fireEvent.click(screen.getByRole("button", { name: /^n1/ }));
    expect(onInsert).toHaveBeenLastCalledWith('$node.id("n1")');
    fireEvent.click(screen.getAllByRole("button", { name: /^Dup/ })[0]);
    expect(onInsert).toHaveBeenLastCalledWith('$node.id("n3")');
  });

  it("explains an upstream node without sample data", () => {
    schema = [{ nodeId: "n1", label: "Fetch", source: "none", paths: [] }];
    setup();
    fireEvent.click(screen.getByRole("button", { name: "Expand Fetch" }));
    expect(screen.getByText(/No sample data yet/)).toBeTruthy();
  });
});
