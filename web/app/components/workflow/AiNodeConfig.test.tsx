import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, within } from "@testing-library/react";

// t() returns the key, so assertions bind to keys rather than English copy.
vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

// The console-wide Select is an animated popover; a native select drives the same onChange contract.
vi.mock("../Select", () => ({
  default: ({ value, onChange, options, ...rest }: any) => (
    <select value={value} onChange={onChange} aria-label={rest["aria-label"]}>
      {options.map((o: { value: string; label: string }) => (
        <option key={o.value} value={o.value}>
          {o.label}
        </option>
      ))}
    </select>
  ),
}));

// The real field needs the expression scope, schema fetches and a mirror layer; a plain input is the contract here.
vi.mock("./ExpressionField", () => ({
  ScopedExpressionField: ({ field, value, onChange }: { field: string; value: string; onChange: (v: string) => void }) => (
    <input data-testid={`expr-${field}`} value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}));

import AiNodeConfigForm from "./AiNodeConfig";
import { aiNodeDefaultConfig, type AiNodeConfig } from "./aiNodes";

function lastPatch(fn: ReturnType<typeof vi.fn>): AiNodeConfig {
  return fn.mock.calls[fn.mock.calls.length - 1][0] as AiNodeConfig;
}

describe("question_classifier form", () => {
  const config = aiNodeDefaultConfig("question_classifier");

  it("renders one row per class and adds a class with a fresh handle", () => {
    const setConfig = vi.fn();
    render(<AiNodeConfigForm type="question_classifier" config={config} setConfig={setConfig} />);
    expect(screen.getByTestId("ai-class-0")).toBeInTheDocument();
    expect(screen.getByTestId("ai-class-1")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /addClassButton/ }));
    const classes = lastPatch(setConfig).classes!;
    expect(classes).toHaveLength(3);
    expect(classes[2].handle).toBe("class-3");
    expect(classes[0]).toEqual(config.classes![0]);
  });

  it("edits a handle and a description and removes a class, without touching the others", () => {
    const setConfig = vi.fn();
    render(<AiNodeConfigForm type="question_classifier" config={config} setConfig={setConfig} />);
    fireEvent.change(screen.getAllByLabelText("classHandleLabel")[0], { target: { value: "billing" } });
    expect(lastPatch(setConfig).classes![0].handle).toBe("billing");
    expect(lastPatch(setConfig).classes![1].handle).toBe("class-2");
    fireEvent.change(screen.getAllByLabelText("classDescriptionLabel")[1], { target: { value: "bugs" } });
    expect(lastPatch(setConfig).classes![1].description).toBe("bugs");
    fireEvent.click(screen.getAllByRole("button", { name: "removeClassButton" })[0]);
    expect(lastPatch(setConfig).classes!.map((c) => c.handle)).toEqual(["class-2"]);
  });

  it("cleans a handle to the slug alphabet when the field is left", () => {
    const setConfig = vi.fn();
    const messy: AiNodeConfig = { classes: [{ handle: "Billing Team", description: "" }, { handle: "ok", description: "" }] };
    render(<AiNodeConfigForm type="question_classifier" config={messy} setConfig={setConfig} />);
    fireEvent.blur(screen.getAllByLabelText("classHandleLabel")[0]);
    expect(lastPatch(setConfig).classes![0].handle).toBe("billing-team");
    setConfig.mockClear();
    fireEvent.blur(screen.getAllByLabelText("classHandleLabel")[1]);
    expect(setConfig).not.toHaveBeenCalled();
  });

  it("offers the class handles as the default class and clamps the confidence threshold", () => {
    const setConfig = vi.fn();
    render(<AiNodeConfigForm type="question_classifier" config={config} setConfig={setConfig} />);
    const [defaultSelect] = screen.getAllByRole("combobox");
    expect(within(defaultSelect).getAllByRole("option").map((o) => o.getAttribute("value"))).toEqual(["", "class-1", "class-2"]);
    fireEvent.change(defaultSelect, { target: { value: "class-2" } });
    expect(lastPatch(setConfig).defaultClass).toBe("class-2");
    fireEvent.change(screen.getByRole("spinbutton"), { target: { value: "7" } });
    expect(lastPatch(setConfig).minConfidence).toBe(1);
  });

  it("stores the text override through the expression field", () => {
    const setConfig = vi.fn();
    render(<AiNodeConfigForm type="question_classifier" config={config} setConfig={setConfig} />);
    fireEvent.change(screen.getByTestId("expr-text"), { target: { value: "{{message}}" } });
    expect(lastPatch(setConfig).text).toBe("{{message}}");
  });

  it("lists save-time problems and clears them once fixed", () => {
    const { rerender } = render(
      <AiNodeConfigForm type="question_classifier" config={{ classes: [{ handle: "only", description: "" }] }} setConfig={vi.fn()} />,
    );
    expect(screen.getByTestId("ai-node-problems")).toHaveTextContent("problem_tooFewClasses");
    rerender(<AiNodeConfigForm type="question_classifier" config={config} setConfig={vi.fn()} />);
    expect(screen.queryByTestId("ai-node-problems")).toBeNull();
  });
});

describe("parameter_extractor form", () => {
  const config: AiNodeConfig = {
    parameters: [
      { name: "color", type: "enum", description: "", required: false, options: ["red", "green"] },
      { name: "qty", type: "number", description: "", required: true },
    ],
  };

  it("shows the options field only for enum parameters and stores parsed options", () => {
    const setConfig = vi.fn();
    render(<AiNodeConfigForm type="parameter_extractor" config={config} setConfig={setConfig} />);
    const options = screen.getAllByLabelText("paramOptionsLabel");
    expect(options).toHaveLength(1);
    expect(options[0]).toHaveValue("red, green");
    fireEvent.change(options[0], { target: { value: "red, blue ,red" } });
    expect(lastPatch(setConfig).parameters![0].options).toEqual(["red", "blue"]);
  });

  it("drops the options when an enum parameter changes type", () => {
    const setConfig = vi.fn();
    render(<AiNodeConfigForm type="parameter_extractor" config={config} setConfig={setConfig} />);
    fireEvent.change(screen.getAllByLabelText("paramTypeLabel")[0], { target: { value: "string" } });
    const changed = lastPatch(setConfig).parameters![0];
    expect(changed.type).toBe("string");
    expect("options" in changed).toBe(false);
    expect(lastPatch(setConfig).parameters![1]).toEqual(config.parameters![1]);
  });

  it("toggles required, edits names, adds and removes parameters", () => {
    const setConfig = vi.fn();
    render(<AiNodeConfigForm type="parameter_extractor" config={config} setConfig={setConfig} />);
    fireEvent.click(screen.getAllByRole("switch")[0]);
    expect(lastPatch(setConfig).parameters![0].required).toBe(true);
    fireEvent.change(screen.getAllByLabelText("paramNameLabel")[1], { target: { value: "quantity" } });
    expect(lastPatch(setConfig).parameters![1].name).toBe("quantity");
    fireEvent.click(screen.getByRole("button", { name: /addParamButton/ }));
    expect(lastPatch(setConfig).parameters!.map((p) => p.name)).toEqual(["color", "qty", "param_3"]);
    fireEvent.click(screen.getAllByRole("button", { name: "removeParamButton" })[1]);
    expect(lastPatch(setConfig).parameters!.map((p) => p.name)).toEqual(["color"]);
  });

  it("flags a bad parameter name inline", () => {
    render(
      <AiNodeConfigForm
        type="parameter_extractor"
        config={{ parameters: [{ name: "not valid", type: "string", description: "", required: false }] }}
        setConfig={vi.fn()}
      />,
    );
    expect(screen.getByTestId("ai-node-problems")).toHaveTextContent("problem_invalidParamName");
  });
});

describe("variable_aggregator form", () => {
  it("edits sources through expression fields, adds and removes them, and sets the fallback", () => {
    const setConfig = vi.fn();
    render(<AiNodeConfigForm type="variable_aggregator" config={{ sources: ["a", "b"], fallback: "input" }} setConfig={setConfig} />);
    fireEvent.change(screen.getByTestId("expr-sources[1]"), { target: { value: "{{ $node[\"B\"].x }}" } });
    expect(lastPatch(setConfig).sources).toEqual(["a", "{{ $node[\"B\"].x }}"]);
    fireEvent.click(screen.getByRole("button", { name: /addSourceButton/ }));
    expect(lastPatch(setConfig).sources).toEqual(["a", "b", ""]);
    fireEvent.click(screen.getAllByRole("button", { name: "removeSourceButton" })[0]);
    expect(lastPatch(setConfig).sources).toEqual(["b"]);
    fireEvent.change(screen.getByRole("combobox"), { target: { value: "empty" } });
    expect(lastPatch(setConfig).fallback).toBe("empty");
  });

  it("asks for a source while none has a value", () => {
    render(<AiNodeConfigForm type="variable_aggregator" config={aiNodeDefaultConfig("variable_aggregator")} setConfig={vi.fn()} />);
    expect(screen.getByTestId("ai-node-problems")).toHaveTextContent("problem_noSources");
  });
});

describe("document_extractor form", () => {
  it("stores the source, the format and the output mode", () => {
    const setConfig = vi.fn();
    render(<AiNodeConfigForm type="document_extractor" config={aiNodeDefaultConfig("document_extractor")} setConfig={setConfig} />);
    fireEvent.change(screen.getByTestId("expr-source"), { target: { value: "chat-attachments/a/b" } });
    expect(lastPatch(setConfig).source).toBe("chat-attachments/a/b");
    const [format, output] = screen.getAllByRole("combobox");
    fireEvent.change(format, { target: { value: "pdf" } });
    expect(lastPatch(setConfig).format).toBe("pdf");
    fireEvent.change(output, { target: { value: "json" } });
    expect(lastPatch(setConfig).outputMode).toBe("json");
  });

  it("leaves a limit unset when blank and clamps it otherwise", () => {
    const setConfig = vi.fn();
    render(<AiNodeConfigForm type="document_extractor" config={{ maxPages: 10 }} setConfig={setConfig} />);
    const [chars, pages, rows] = screen.getAllByRole("spinbutton");
    expect(pages).toHaveValue(10);
    fireEvent.change(pages, { target: { value: "" } });
    expect(lastPatch(setConfig).maxPages).toBeUndefined();
    fireEvent.change(chars, { target: { value: "10" } });
    expect(lastPatch(setConfig).maxChars).toBe(500);
    fireEvent.change(rows, { target: { value: "300" } });
    expect(lastPatch(setConfig).maxRows).toBe(300);
  });
});
