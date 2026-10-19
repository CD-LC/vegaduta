import { describe, expect, it } from "vitest";
import {
  initialValues,
  normalizeServerErrors,
  parsePublicForm,
  safeRedirect,
  toPayload,
  validateValues,
  type PublicField,
} from "./formModel";

function field(over: Partial<PublicField> & Pick<PublicField, "key" | "type">): PublicField {
  return {
    label: over.key,
    required: false,
    placeholder: "",
    options: [],
    maxLength: null,
    min: null,
    max: null,
    ...over,
  };
}

describe("parsePublicForm", () => {
  it("reads the server definition", () => {
    const form = parsePublicForm({
      title: "Contact",
      description: "Hi",
      submitLabel: "Send",
      honeypotField: "_hp",
      fields: [
        { key: "name", label: "Name", type: "text", required: true, maxLength: 50 },
        { key: "topic", type: "select", options: ["a", "b", 3] },
      ],
    });
    expect(form?.title).toBe("Contact");
    expect(form?.fields[0]).toMatchObject({ key: "name", required: true, maxLength: 50 });
    expect(form?.fields[1].label).toBe("topic"); // label falls back to the key
    expect(form?.fields[1].options).toEqual(["a", "b"]);
    expect(form?.honeypotField).toBe("_hp");
  });

  it("rejects anything that is not a usable form", () => {
    expect(parsePublicForm(null)).toBeNull();
    expect(parsePublicForm({})).toBeNull();
    expect(parsePublicForm({ fields: [] })).toBeNull();
    expect(parsePublicForm({ fields: [{ key: "x", type: "color" }] })).toBeNull();
    expect(parsePublicForm({ fields: [{ type: "text" }] })).toBeNull();
  });
});

describe("validateValues", () => {
  const fields = [
    field({ key: "name", type: "text", required: true, maxLength: 5 }),
    field({ key: "qty", type: "number", min: 1, max: 10 }),
    field({ key: "mail", type: "email" }),
    field({ key: "topic", type: "select", options: ["sales", "support"] }),
    field({ key: "agree", type: "checkbox", required: true }),
    field({ key: "when", type: "date" }),
  ];

  it("starts empty and reports only what is required", () => {
    expect(validateValues(fields, initialValues(fields))).toEqual({ name: "required", agree: "required" });
  });

  it("accepts a good submission", () => {
    expect(
      validateValues(fields, { name: "Asha", qty: "5", mail: "a@b.co", topic: "sales", agree: true, when: "2026-10-05" })
    ).toEqual({});
  });

  it("flags each rule with the server's code", () => {
    const errors = validateValues(fields, {
      name: "Ashaaa",
      qty: "11",
      mail: "nope",
      topic: "billing",
      agree: true,
      when: "05/10/2026",
    });
    expect(errors).toEqual({
      name: "too_long",
      qty: "out_of_range",
      mail: "invalid_email",
      topic: "invalid_option",
      when: "invalid_date",
    });
    expect(validateValues(fields, { ...initialValues(fields), name: "A", agree: true, qty: "abc" }).qty).toBe("invalid_number");
  });
});

describe("toPayload", () => {
  it("trims strings, keeps booleans and carries the honeypot", () => {
    const fields = [field({ key: "name", type: "text" }), field({ key: "agree", type: "checkbox" })];
    expect(toPayload(fields, { name: "  Asha ", agree: true }, "_hp", "")).toEqual({
      values: { name: "Asha", agree: true },
      _hp: "",
    });
  });
});

describe("safeRedirect", () => {
  it("only ever allows absolute https URLs", () => {
    expect(safeRedirect("https://example.com/thanks")).toBe("https://example.com/thanks");
    for (const bad of ["http://example.com", "javascript:alert(1)", "//example.com", "/relative", "https://u:p@example.com", "", null, 5]) {
      expect(safeRedirect(bad)).toBeNull();
    }
  });
});

describe("normalizeServerErrors", () => {
  it("keeps known codes and degrades unknown ones", () => {
    expect(normalizeServerErrors({ a: "required", b: "something_new", c: 7 })).toEqual({
      a: "required",
      b: "invalid_type",
      c: "invalid_type",
    });
    expect(normalizeServerErrors(null)).toEqual({});
  });
});
