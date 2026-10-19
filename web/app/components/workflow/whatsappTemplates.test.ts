import { describe, expect, it } from "vitest";
import {
  WHATSAPP_TEMPLATE_MAX_PARAMS,
  clearTemplateConfigPatch,
  findTemplate,
  parseTemplateKey,
  parseWhatsAppTemplates,
  resizeTemplateParams,
  selectedTemplate,
  setTemplateParam,
  templateConfigPatch,
  templateKey,
  templateLoadFailure,
} from "./whatsappTemplates";
import { readFieldStrings } from "./expressionLint";
import { expressionFieldsOf } from "./nodeDefs";

const reminder = { name: "invoice_reminder", language: "en_US", bodyParamCount: 2, preview: "Hi {{1}}, invoice {{2}} is due.", category: "UTILITY" };

describe("parseWhatsAppTemplates", () => {
  it("reads the listing", () => {
    expect(parseWhatsAppTemplates([reminder])).toEqual([reminder]);
  });

  it("drops entries without a name or language, and duplicates", () => {
    expect(
      parseWhatsAppTemplates([
        reminder,
        { ...reminder },
        { name: "no_language" },
        { language: "en_US" },
        null,
        "x",
        { name: " hello ", language: " hi ", bodyParamCount: "3", preview: 5, category: "" },
      ]),
    ).toEqual([reminder, { name: "hello", language: "hi", bodyParamCount: 0, preview: "", category: null }]);
  });

  it("is empty for anything that is not a list", () => {
    expect(parseWhatsAppTemplates(null)).toEqual([]);
    expect(parseWhatsAppTemplates({ templates: [reminder] })).toEqual([]);
  });

  it("bounds the variable count", () => {
    const [t] = parseWhatsAppTemplates([{ ...reminder, bodyParamCount: 9999 }]);
    expect(t.bodyParamCount).toBe(WHATSAPP_TEMPLATE_MAX_PARAMS);
    expect(parseWhatsAppTemplates([{ ...reminder, bodyParamCount: -4 }])[0].bodyParamCount).toBe(0);
  });
});

describe("template key", () => {
  it("round-trips name and language", () => {
    expect(parseTemplateKey(templateKey(reminder))).toEqual({ name: "invoice_reminder", language: "en_US" });
  });

  it("keeps the same name in two languages apart", () => {
    expect(templateKey({ name: "a", language: "en" })).not.toBe(templateKey({ name: "a", language: "hi" }));
  });

  it("rejects the empty choice and garbage", () => {
    for (const bad of ["", "name-only", "\nlang", "name\n", undefined, 4]) expect(parseTemplateKey(bad)).toBeNull();
  });
});

describe("selectedTemplate", () => {
  it("returns the stored ref", () => {
    expect(selectedTemplate({ whatsappTemplate: { name: "a", language: "en" } })).toEqual({ name: "a", language: "en" });
  });

  it("treats absent, empty and half-filled as none", () => {
    for (const cfg of [undefined, null, {}, { whatsappTemplate: {} }, { whatsappTemplate: { name: "a" } }, { whatsappTemplate: { name: "a", language: " " } }, { whatsappTemplate: "a" }]) {
      expect(selectedTemplate(cfg as never)).toBeNull();
    }
  });

  it("finds it in the listing by name and language", () => {
    expect(findTemplate([reminder], { name: "invoice_reminder", language: "en_US" })).toBe(reminder);
    expect(findTemplate([reminder], { name: "invoice_reminder", language: "hi" })).toBeNull();
    expect(findTemplate([reminder], null)).toBeNull();
  });
});

describe("template params", () => {
  it("sizes the params to the template, keeping what was typed", () => {
    expect(resizeTemplateParams(["Asha"], 2)).toEqual(["Asha", ""]);
    expect(resizeTemplateParams(["a", "b", "c"], 2)).toEqual(["a", "b"]);
    expect(resizeTemplateParams(undefined, 1)).toEqual([""]);
    expect(resizeTemplateParams(["a", 7, null], 3)).toEqual(["a", "", ""]);
  });

  it("sets one value without mutating the input", () => {
    const before = ["a", "b"];
    expect(setTemplateParam(before, 1, "{{ $json.total }}")).toEqual(["a", "{{ $json.total }}"]);
    expect(before).toEqual(["a", "b"]);
    expect(setTemplateParam(undefined, 2, "x")).toEqual(["", "", "x"]);
  });

  it("builds the config patch for picking and for clearing", () => {
    expect(templateConfigPatch(reminder, ["Asha"])).toEqual({
      whatsappTemplate: { name: "invoice_reminder", language: "en_US" },
      whatsappTemplateParams: ["Asha", ""],
    });
    const cleared = { whatsappTo: "+1", ...templateConfigPatch(reminder, []), ...clearTemplateConfigPatch() };
    // undefined keys vanish from the saved JSON: the node is back to plain text.
    expect(JSON.parse(JSON.stringify(cleared))).toEqual({ whatsappTo: "+1" });
  });
});

describe("templateLoadFailure", () => {
  it("hides the picker when the feature is off (404)", () => {
    expect(templateLoadFailure({ status: 404, message: "WhatsApp templates are not enabled." })).toEqual({ status: "hidden" });
  });

  it("explains a missing permission (403)", () => {
    expect(templateLoadFailure({ status: 403 })).toEqual({ status: "forbidden" });
  });

  it("carries the server's message for anything else", () => {
    expect(templateLoadFailure(Object.assign(new Error("Meta would not list templates (190)"), { status: 400 }))).toEqual({
      status: "error",
      message: "Meta would not list templates (190)",
    });
    expect(templateLoadFailure(undefined)).toEqual({ status: "error", message: "" });
  });
});

describe("expression field registry (must match core ExpressionFields)", () => {
  it("lists the WhatsApp message and template params on the output node, in core's order", () => {
    expect(expressionFieldsOf("output")).toEqual([
      "emailTo",
      "telegramChatId",
      "teamsConversationId",
      "whatsappTo",
      "driveFolderId",
      "driveFileName",
      "fileName",
      "whatsappMessage",
      "whatsappTemplateParams[]",
    ]);
  });

  it("addresses each template param by index for lint", () => {
    expect(readFieldStrings({ whatsappTemplateParams: ["{{ $json.name }}", "fixed"] }, "whatsappTemplateParams[]")).toEqual([
      { field: "whatsappTemplateParams[0]", value: "{{ $json.name }}" },
      { field: "whatsappTemplateParams[1]", value: "fixed" },
    ]);
  });
});
