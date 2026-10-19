import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useState, type ReactNode } from "react";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { I18nProvider } from "../../lib/i18n";
import en from "../../../messages/en.json";
import type { UpstreamSchemaNode } from "../../lib/api/workflowExpressions";

const evaluate = vi.fn();
vi.mock("../../lib/api/workflowExpressions", async (orig) => {
  const real = await orig<typeof import("../../lib/api/workflowExpressions")>();
  return { ...real, workflowExpressionsApi: { ...real.workflowExpressionsApi, evaluateExpression: (...a: unknown[]) => evaluate(...a) } };
});

const schema: UpstreamSchemaNode[] = [
  {
    nodeId: "n1",
    label: "Fetch",
    source: "pinned",
    paths: [
      { path: "items", type: "array", sample: null },
      { path: "items[0].price", type: "number", sample: "9" },
      { path: "id", type: "string", sample: "a1" },
    ],
  },
];
vi.mock("./useExpressionData", () => ({
  useUpstreamSchema: () => ({ nodes: schema, loading: false, refresh: () => {} }),
  useExpressionFunctions: () => [
    { name: "upper", category: "string", signature: "upper(s)", doc: "Uppercases text.", example: 'upper("a")' },
  ],
}));

import ExpressionField, { ExpressionLintContext } from "./ExpressionField";
import { ExpressionFocusProvider, requestExpressionFieldFocus, useExpressionFocus } from "./ExpressionFocus";

function Wrap({ children }: { children: ReactNode }) {
  return (
    <I18nProvider locale="en" messages={en} enMessages={en}>
      {children}
    </I18nProvider>
  );
}

function Harness({ initial = "", bare = false, multiline = false }: { initial?: string; bare?: boolean; multiline?: boolean }) {
  const [v, setV] = useState(initial);
  return (
    <ExpressionField value={v} onChange={setV} nodeId="n2" field="url" workflowId="wf1" bare={bare} multiline={multiline} ariaLabel="URL" />
  );
}

const area = () => screen.getByRole("combobox") as HTMLTextAreaElement;
function type(el: HTMLTextAreaElement, value: string, caret = value.length) {
  el.focus();
  // Native setter + explicit caret + input event: what a real keystroke does (setting .value alone
  // would park the caret at the end, and assigning el.value would fool React's change tracking).
  const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")!.set!;
  set.call(el, value);
  el.setSelectionRange(caret, caret);
  fireEvent.input(el);
}

beforeEach(() => {
  evaluate.mockReset();
  evaluate.mockResolvedValue({ ok: false, unsupported: true });
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("ExpressionField modes", () => {
  it("starts Fixed for plain text and Expression when the value has braces", () => {
    const { unmount } = render(<Wrap><Harness initial="https://x" /></Wrap>);
    expect(screen.getByRole("button", { name: "Fixed" }).getAttribute("aria-pressed")).toBe("true");
    unmount();
    render(<Wrap><Harness initial="a {{ $json.x }}" /></Wrap>);
    expect(screen.getByRole("button", { name: "Expression" }).getAttribute("aria-pressed")).toBe("true");
  });

  it("switching to Expression on empty text seeds {{ }} with the caret inside", () => {
    render(<Wrap><Harness /></Wrap>);
    fireEvent.click(screen.getByRole("button", { name: "Expression" }));
    expect(area().value).toBe("{{  }}");
    expect(area().selectionStart).toBe(3);
  });

  it("switching to Fixed keeps the text as is and asks nothing", () => {
    render(<Wrap><Harness initial="a {{ $json.x }}" /></Wrap>);
    fireEvent.click(screen.getByRole("button", { name: "Fixed" }));
    expect(area().value).toBe("a {{ $json.x }}");
    expect(screen.getByRole("button", { name: "Fixed" }).getAttribute("aria-pressed")).toBe("true");
  });

  it("typing braces in Fixed mode flips to Expression", () => {
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), "{{ a }}");
    expect(screen.getByRole("button", { name: "Expression" }).getAttribute("aria-pressed")).toBe("true");
  });

  it("closes a typed {{ automatically with the caret inside", () => {
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), "{");
    type(area(), "{{");
    expect(area().value).toBe("{{  }}");
    expect(area().selectionStart).toBe(3);
  });

  it("bare fields have no Fixed/Expression toggle", () => {
    render(<Wrap><Harness bare initial="$json.a > 1" /></Wrap>);
    expect(screen.queryByRole("button", { name: "Fixed" })).toBeNull();
  });
});

describe("ExpressionField autocomplete", () => {
  it("offers roots after typing $ inside braces and applies one with Enter", () => {
    render(<Wrap><Harness initial="{{ }}" /></Wrap>);
    type(area(), "{{ $nod }}", 7);
    const box = area();
    expect(box.getAttribute("aria-expanded")).toBe("true");
    const opts = screen.getAllByRole("option");
    expect(opts[0].textContent).toContain("$node");
    fireEvent.keyDown(box, { key: "Enter" });
    expect(area().value).toBe('{{ $node[""] }}');
  });

  it("completes node labels inside $node[\"", () => {
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), '{{ $node["F"] }}', 11);
    expect(screen.getAllByRole("option").map((o) => o.textContent)).toEqual([expect.stringContaining("Fetch")]);
    fireEvent.keyDown(area(), { key: "Tab" });
    expect(area().value).toBe('{{ $node["Fetch"] }}');
  });

  it("offers upstream fields after a dot and inserts bracket/identifier access", () => {
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), '{{ $node["Fetch"]. }}', 19);
    const labels = screen.getAllByRole("option").map((o) => o.textContent ?? "");
    expect(labels.some((l) => l.includes("items"))).toBe(true);
    expect(labels.some((l) => l.includes("id"))).toBe(true);
  });

  it("moves the active option with the arrow keys and sets aria-activedescendant", () => {
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), "{{ $ }}", 4);
    const box = area();
    const first = box.getAttribute("aria-activedescendant");
    expect(first).toMatch(/-0$/);
    fireEvent.keyDown(box, { key: "ArrowDown" });
    expect(area().getAttribute("aria-activedescendant")).toMatch(/-1$/);
    fireEvent.keyDown(box, { key: "ArrowUp" });
    fireEvent.keyDown(box, { key: "ArrowUp" });
    expect(area().getAttribute("aria-activedescendant")).toMatch(new RegExp(`-${screen.getAllByRole("option").length - 1}$`));
    expect(screen.getAllByRole("option").filter((o) => o.getAttribute("aria-selected") === "true")).toHaveLength(1);
  });

  it("Escape closes only the popup and does not leak to the drawer", () => {
    const outer = vi.fn();
    render(
      <Wrap>
        {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions, jsx-a11y/no-noninteractive-element-interactions -- test wrapper observing bubbled keydown events */}
        <div onKeyDown={outer}>
          <Harness initial="" />
        </div>
      </Wrap>,
    );
    type(area(), "{{ $ }}", 4);
    expect(screen.queryByRole("listbox")).not.toBeNull();
    fireEvent.keyDown(area(), { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(outer).not.toHaveBeenCalled();
  });

  it("does not open outside braces or in fixed text", () => {
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), "https://x/$n");
    expect(screen.queryByRole("listbox")).toBeNull();
  });

  it("accepts an option on mouse down without losing focus semantics", () => {
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), "{{ up }}", 5);
    fireEvent.mouseDown(screen.getAllByRole("option").find((o) => o.textContent?.includes("upper"))!);
    expect(area().value).toBe("{{ upper() }}");
  });

  it("shows the function signature and example for the active function", () => {
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), "{{ up }}", 5);
    expect(screen.getByText("Uppercases text.")).toBeTruthy();
    expect(screen.getByText('upper("a")')).toBeTruthy();
  });
});

describe("ExpressionField bare (condition) mode", () => {
  it("completes inside a bare expression and stores it without braces", () => {
    render(<Wrap><Harness bare initial="" /></Wrap>);
    type(area(), "$nod");
    fireEvent.keyDown(area(), { key: "Enter" });
    expect(area().value).toBe('$node[""]');
  });
});

describe("ExpressionField preview", () => {
  it("debounces, evaluates pinned first, and shows the rendered value with a type chip", async () => {
    vi.useFakeTimers();
    evaluate.mockResolvedValue({
      ok: true,
      data: { ok: true, value: 9, type: "number", rendered: "9", error: null, sources: ["n1"], warnings: [] },
    });
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), '{{ $node["Fetch"].items[0].price }}');
    expect(evaluate).not.toHaveBeenCalled();
    await act(async () => {
      vi.advanceTimersByTime(450);
    });
    expect(evaluate).toHaveBeenCalledTimes(1);
    expect(evaluate.mock.calls[0][0]).toBe("wf1");
    expect(evaluate.mock.calls[0][1]).toMatchObject({ dataSource: "pinned", nodeId: "n2", template: '{{ $node["Fetch"].items[0].price }}' });
    expect(screen.getByText("9")).toBeTruthy();
    expect(screen.getByText("number")).toBeTruthy();
    expect(screen.getByText("from pinned data")).toBeTruthy();
  });

  it("coalesces rapid typing into one request", async () => {
    vi.useFakeTimers();
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), "{{ 1 }}");
    await act(async () => {
      vi.advanceTimersByTime(200);
    });
    type(area(), "{{ 12 }}");
    await act(async () => {
      vi.advanceTimersByTime(450);
    });
    // pinned then lastRun fallback for the single settled value: both carry the settled template
    const templates = new Set(evaluate.mock.calls.map((c) => (c[1] as { template: string }).template));
    expect(templates).toEqual(new Set(["{{ 12 }}"]));
  });

  it("shows the neutral no-data state when the server has nothing to read", async () => {
    vi.useFakeTimers();
    evaluate.mockResolvedValue({
      ok: true,
      data: { ok: true, value: null, type: "null", rendered: "", error: null, sources: [], warnings: [] },
    });
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), "{{ $json.a }}");
    await act(async () => {
      vi.advanceTimersByTime(450);
    });
    expect(screen.getByText(/No sample data yet/)).toBeTruthy();
  });

  it("degrades to unavailable when the route is missing, and the field still edits", async () => {
    vi.useFakeTimers();
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), "{{ $json.a }}");
    await act(async () => {
      vi.advanceTimersByTime(450);
    });
    expect(screen.getByText("Preview unavailable.")).toBeTruthy();
    type(area(), "{{ $json.ab }}");
    expect(area().value).toBe("{{ $json.ab }}");
  });

  it("shows the server's evaluation error message and marks invalid input", async () => {
    vi.useFakeTimers();
    evaluate.mockResolvedValue({
      ok: true,
      data: { ok: false, value: null, type: "null", rendered: "", error: { message: "cannot read x", start: 3, end: 5 }, sources: ["n1"], warnings: [] },
    });
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), "{{ ab }}");
    await act(async () => {
      vi.advanceTimersByTime(450);
    });
    expect(screen.getByText("cannot read x")).toBeTruthy();
    expect(area().getAttribute("aria-invalid")).toBe("true");
  });

  it("makes no request for fixed text", async () => {
    vi.useFakeTimers();
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), "plain");
    await act(async () => {
      vi.advanceTimersByTime(600);
    });
    expect(evaluate).not.toHaveBeenCalled();
  });

  it("wraps a bare expression for evaluation and maps error positions back", async () => {
    vi.useFakeTimers();
    evaluate.mockResolvedValue({
      ok: true,
      data: { ok: false, value: null, type: "null", rendered: "", error: { message: "boom", start: 6, end: 8 }, sources: ["n1"], warnings: [] },
    });
    render(<Wrap><Harness bare initial="" /></Wrap>);
    type(area(), "a > 1");
    await act(async () => {
      vi.advanceTimersByTime(450);
    });
    expect(evaluate.mock.calls[0][1]).toMatchObject({ template: "{{ a > 1 }}" });
    expect(screen.getByText("boom")).toBeTruthy();
  });
});

describe("ExpressionField diagnostics and insertion", () => {
  it("shows a quick unbalanced-braces message without any server", () => {
    render(<Wrap><Harness initial="" /></Wrap>);
    type(area(), "{{ a ");
    expect(screen.getByText('Missing closing "}}".')).toBeTruthy();
  });

  it("shows a server lint diagnostic only while the text still matches what was sent", () => {
    const state = {
      diagnostics: [{ nodeId: "n2", field: "url", severity: "error" as const, code: "UNKNOWN_NODE", message: "No node named Nope", start: 3, end: 7 }],
      sent: { "n2\u0000url": "{{ Nope }}" },
    };
    const { rerender } = render(
      <Wrap>
        <ExpressionLintContext.Provider value={state}>
          <ExpressionField value="{{ Nope }}" onChange={() => {}} nodeId="n2" field="url" workflowId="wf1" />
        </ExpressionLintContext.Provider>
      </Wrap>,
    );
    expect(screen.getByText("No node named Nope")).toBeTruthy();
    rerender(
      <Wrap>
        <ExpressionLintContext.Provider value={state}>
          <ExpressionField value="{{ Nope2 }}" onChange={() => {}} nodeId="n2" field="url" workflowId="wf1" />
        </ExpressionLintContext.Provider>
      </Wrap>,
    );
    expect(screen.queryByText("No node named Nope")).toBeNull();
  });

  it("draws a server diagnostic on a bare expression without the wrapper's 3-character offset", () => {
    const expr = '$node["Fetch"].ok';
    // Core lints the wrapped "{{ expr }}", so its offsets start 3 characters in.
    const state = {
      diagnostics: [{ nodeId: "n2", field: "expression", severity: "error" as const, code: "UNKNOWN_NODE", message: "No node named Fetch", start: 3, end: 15 }],
      sent: { "n2\u0000expression": expr },
    };
    const { container } = render(
      <Wrap>
        <ExpressionLintContext.Provider value={state}>
          <ExpressionField value={expr} onChange={() => {}} nodeId="n2" field="expression" workflowId="wf1" bare />
        </ExpressionLintContext.Provider>
      </Wrap>,
    );
    const marked = Array.from(container.querySelectorAll('.wfx-ex-mirror [data-marker="error"]'))
      .map((el) => el.textContent)
      .join("");
    expect(marked).toBe(expr.slice(0, 12));
  });

  it("inserts a data-picker expression into the last focused field", () => {
    let insert: ((e: string) => boolean) | null = null;
    function Grab(): null {
      insert = useExpressionFocus()?.insert ?? null;
      return null;
    }
    render(
      <Wrap>
        <ExpressionFocusProvider>
          <Harness initial="https://x/" />
          <Grab />
        </ExpressionFocusProvider>
      </Wrap>,
    );
    area().focus();
    area().setSelectionRange(10, 10);
    fireEvent.select(area());
    act(() => {
      expect(insert!('$node["Fetch"].id')).toBe(true);
    });
    expect(area().value).toBe('https://x/{{ $node["Fetch"].id }}');
    expect(screen.getByRole("button", { name: "Expression" }).getAttribute("aria-pressed")).toBe("true");
  });

  it("focuses the field when a jump-to-field request arrives", () => {
    render(<Wrap><Harness initial="{{ a }}" /></Wrap>);
    act(() => requestExpressionFieldFocus("n2", "url"));
    expect(document.activeElement).toBe(area());
  });

  it("disabled fields are inert", () => {
    render(
      <Wrap>
        <ExpressionField value="x" onChange={() => {}} nodeId="n2" field="url" workflowId="wf1" disabled />
      </Wrap>,
    );
    expect(area().disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Expression" }) as HTMLButtonElement).disabled).toBe(true);
  });
});
