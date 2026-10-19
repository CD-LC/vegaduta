import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { NODE_FORMS, resolveNodeForm } from "./registry";
import { SIMPLE_FORMS } from "./descriptors";
import SimpleFieldsForm from "./forms/SimpleFieldsForm";
import type { NodeFormProps } from "./registryTypes";

vi.mock("../../../lib/i18n", () => ({ useTranslations: () => (k: string) => k }));

function props(over: Partial<NodeFormProps>): NodeFormProps {
  return {
    nodeId: "n1",
    nodeType: "loop",
    workflowId: "w1",
    config: {},
    setConfig: vi.fn(),
    agents: null,
    collections: null,
    devices: null,
    ...over,
  };
}

describe("node form registry", () => {
  it("registers a form for every simple descriptor type", () => {
    for (const type of Object.keys(SIMPLE_FORMS)) expect(NODE_FORMS[type]).toBeDefined();
  });
  it("resolves known types and returns null for unknown", () => {
    expect(resolveNodeForm("trigger")).toBe(NODE_FORMS.trigger);
    expect(resolveNodeForm("no_such_node")).toBeNull();
  });
});

describe("SimpleFieldsForm", () => {
  it("renders loop defaults and writes number patches", () => {
    const setConfig = vi.fn();
    render(<SimpleFieldsForm {...props({ nodeType: "loop", setConfig })} />);
    const num = screen.getByDisplayValue("25");
    fireEvent.change(num, { target: { value: "10" } });
    expect(setConfig).toHaveBeenCalledWith({ maxIterations: 10 });
  });
  it("writes text patches for fan_out", () => {
    const setConfig = vi.fn();
    render(<SimpleFieldsForm {...props({ nodeType: "fan_out", setConfig })} />);
    fireEvent.change(screen.getByPlaceholderText("loopListFieldPlaceholder"), { target: { value: "a.b" } });
    expect(setConfig).toHaveBeenCalledWith({ itemsField: "a.b" });
  });
});
