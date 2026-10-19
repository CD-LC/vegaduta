import { describe, it, expect } from "vitest";
import { EDIT_CONFLICT_MESSAGE, errorMessage, isConflictError, saveErrorMessage } from "./errors";

describe("optimistic-lock conflicts (409)", () => {
  it("detects a 409 by status", () => {
    expect(isConflictError(Object.assign(new Error("x"), { status: 409 }))).toBe(true);
    expect(isConflictError(Object.assign(new Error("x"), { status: 400 }))).toBe(false);
    expect(isConflictError(null)).toBe(false);
  });
  it("uses core's sentence on a 409, the shared copy when there is none, and the fallback otherwise", () => {
    expect(saveErrorMessage(Object.assign(new Error("changed by someone else. Reload"), { status: 409 }), "fb")).toBe(
      "changed by someone else. Reload",
    );
    expect(saveErrorMessage(Object.assign(new Error(""), { status: 409 }), "fb")).toBe(EDIT_CONFLICT_MESSAGE);
    expect(saveErrorMessage(new Error(""), "fb")).toBe("fb");
  });
});

describe("errorMessage", () => {
  it("returns an Error's message", () => {
    expect(errorMessage(new Error("boom"))).toBe("boom");
    expect(errorMessage(new TypeError("bad type"), "fallback")).toBe("bad type");
  });

  it("reads message from a non-Error object that has one", () => {
    expect(errorMessage({ message: "api said no", status: 400 })).toBe("api said no");
    expect(errorMessage({ message: "api said no" }, "fallback")).toBe("api said no");
  });

  it("uses the fallback when the message is empty or missing (like `e.message || fallback`)", () => {
    expect(errorMessage(new Error(""), "fallback")).toBe("fallback");
    expect(errorMessage({}, "fallback")).toBe("fallback");
    expect(errorMessage({ message: 42 }, "fallback")).toBe("fallback");
    expect(errorMessage("a thrown string", "fallback")).toBe("fallback");
    expect(errorMessage(null, "fallback")).toBe("fallback");
    expect(errorMessage(undefined, "fallback")).toBe("fallback");
    expect(errorMessage(new Error("x"), "")).toBe("x");
    expect(errorMessage(null, "")).toBe("");
  });

  it("without a fallback, keeps an empty message and stringifies other values", () => {
    expect(errorMessage(new Error(""))).toBe("");
    expect(errorMessage("a thrown string")).toBe("a thrown string");
    expect(errorMessage(42)).toBe("42");
    expect(errorMessage(null)).toBe("Unknown error");
    expect(errorMessage(undefined)).toBe("Unknown error");
  });
});
