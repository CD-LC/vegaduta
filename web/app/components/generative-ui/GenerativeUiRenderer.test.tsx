import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { GenerativeUiRenderer } from "./GenerativeUiRenderer";
import { validateUiSpec, MAX_NODES } from "./schema";
import { extractUiSpec, extractUiSpecFromCustomEvent } from "./extractUiSpec";

const cardSpec = {
  version: "v1",
  root: {
    type: "card",
    props: { title: "Invoice summary" },
    children: [
      { type: "text", props: { text: "3 invoices found" } },
      { type: "button", props: { label: "Send report" }, action: { type: "sendMessage", message: "send the report" } },
    ],
  },
};

describe("validateUiSpec", () => {
  it("accepts a valid spec", () => {
    const { spec, errors } = validateUiSpec(cardSpec);
    expect(spec).not.toBeNull();
    expect(errors).toHaveLength(0);
    expect(spec!.root.type).toBe("card");
  });

  it("returns null spec for non-object input", () => {
    expect(validateUiSpec("nope").spec).toBeNull();
    expect(validateUiSpec({}).spec).toBeNull();
  });

  it("placeholders unknown node types instead of dropping them", () => {
    const { spec } = validateUiSpec({ version: "v1", root: { type: "iframe", props: { src: "https://x" } } });
    expect(spec!.root.type).toBe("__unsupported__");
  });

  it("strips non-whitelisted props", () => {
    const { spec } = validateUiSpec({
      version: "v1",
      root: { type: "text", props: { text: "hi", onclick: "alert(1)", style: "x" } },
    });
    expect(spec!.root.props).toEqual({ text: "hi" });
  });

  it("rejects non-http(s) URLs on src/href", () => {
    const { spec, errors } = validateUiSpec({
      version: "v1",
      root: { type: "link", props: { href: "javascript:alert(1)", text: "x" } },
    });
    expect(spec!.root.props!.href).toBeUndefined();
    expect(errors.some((e) => e.includes("href"))).toBe(true);
  });

  it("caps total nodes", () => {
    const many = Array.from({ length: MAX_NODES + 5 }, () => ({ type: "text", props: { text: "x" } }));
    const { errors } = validateUiSpec({ version: "v1", root: { type: "stack", children: many } });
    expect(errors.some((e) => e.includes("node count"))).toBe(true);
  });

  it("keeps only sendMessage actions", () => {
    const { spec } = validateUiSpec({
      version: "v1",
      root: { type: "button", props: { label: "x" }, action: { type: "eval", message: "bad()" } },
    });
    expect(spec!.root.action).toBeUndefined();
  });
});

describe("extractUiSpec", () => {
  it("extracts a json_ui block and strips it from the text", () => {
    const text = `Here is your dashboard:\n\n\`\`\`json_ui\n${JSON.stringify(cardSpec)}\n\`\`\`\n\nDone.`;
    const out = extractUiSpec(text);
    expect(out).not.toBeNull();
    expect(out!.spec.root.type).toBe("card");
    expect(out!.text).not.toContain("```");
    expect(out!.text).toContain("Here is your dashboard");
  });

  it("returns null when no fenced block exists", () => {
    expect(extractUiSpec("plain text")).toBeNull();
  });

  it("returns null on malformed JSON, leaving text untouched", () => {
    expect(extractUiSpec("```json_ui\n{bad\n```")).toBeNull();
  });

  it("maps AG-UI CUSTOM ui_spec events onto the same validator", () => {
    const evt = { type: "CUSTOM", name: "ui_spec", value: cardSpec };
    const out = extractUiSpecFromCustomEvent(evt);
    expect(out!.spec.root.type).toBe("card");
    expect(extractUiSpecFromCustomEvent({ type: "CUSTOM", name: "plan", value: {} })).toBeNull();
    expect(extractUiSpecFromCustomEvent({ type: "RUN_STARTED" })).toBeNull();
  });
});

describe("GenerativeUiRenderer", () => {
  it("renders known nodes", () => {
    const { spec } = validateUiSpec(cardSpec);
    render(<GenerativeUiRenderer spec={spec!} />);
    expect(screen.getByText("Invoice summary")).toBeTruthy();
    expect(screen.getByText("3 invoices found")).toBeTruthy();
    expect(screen.getByText("Send report")).toBeTruthy();
  });

  it("renders an explicit fallback for unsupported nodes", () => {
    const { spec } = validateUiSpec({ version: "v1", root: { type: "mystery" } });
    render(<GenerativeUiRenderer spec={spec!} />);
    expect(screen.getByText(/Unsupported UI element/)).toBeTruthy();
  });

  it("fires onAction with the action message on button click", () => {
    const onAction = vi.fn();
    const { spec } = validateUiSpec(cardSpec);
    render(<GenerativeUiRenderer spec={spec!} onAction={onAction} />);
    fireEvent.click(screen.getByText("Send report"));
    expect(onAction).toHaveBeenCalledWith({ type: "sendMessage", message: "send the report" }, undefined);
  });

  it("form submit collects field values into the action message", () => {
    const onAction = vi.fn();
    const { spec } = validateUiSpec({
      version: "v1",
      root: {
        type: "form",
        props: { submitLabel: "Go" },
        action: { type: "sendMessage", message: "User filled the form" },
        children: [{ type: "input", props: { name: "email", label: "Email" } }],
      },
    });
    const { container } = render(<GenerativeUiRenderer spec={spec!} onAction={onAction} />);
    const input = container.querySelector("input")!;
    fireEvent.change(input, { target: { value: "a@b.c" } });
    fireEvent.click(screen.getByText("Go"));
    expect(onAction).toHaveBeenCalledOnce();
    const [action, values] = onAction.mock.calls[0];
    expect(values).toEqual({ email: "a@b.c" });
    expect(action.message).toContain("User filled the form");
    expect(action.message).toContain("a@b.c");
  });
});
