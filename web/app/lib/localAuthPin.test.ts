import { describe, expect, it } from "vitest";
import { formatRecoveryCode, normalizeRecoveryCode, pinProblem, sanitizePin } from "./localAuthPin";

describe("pinProblem", () => {
  it("accepts 6 to 12 digits that are not trivial", () => {
    for (const pin of ["493827", "135790", "8203957164", "908172635412"]) expect(pinProblem(pin), pin).toBeNull();
  });

  it("flags length and non-digits", () => {
    expect(pinProblem("49382")).toBe("length");
    expect(pinProblem("4938271649382")).toBe("length");
    expect(pinProblem("49a827")).toBe("digits");
    expect(pinProblem("")).toBe("length");
  });

  it("flags one repeated digit, straight runs and the named common PINs", () => {
    for (const pin of ["111111", "000000", "123456", "234567", "654321", "987654", "123123", "112233", "696969", "121212"]) {
      expect(pinProblem(pin), pin).toBe("weak");
    }
  });
});

describe("sanitizePin", () => {
  it("keeps digits and caps the length at 12", () => {
    expect(sanitizePin("12 ab-34")).toBe("1234");
    expect(sanitizePin("1234567890123456")).toBe("123456789012");
  });
});

describe("recovery code formatting", () => {
  it("groups 16 characters as 4-4-4-4 whatever the input spacing", () => {
    expect(formatRecoveryCode("abcdefghjklmnpqr")).toBe("ABCD-EFGH-JKLM-NPQR");
    expect(formatRecoveryCode("abcd efgh-jklm npqr")).toBe("ABCD-EFGH-JKLM-NPQR");
  });
  it("normalises to the characters core compares", () => {
    expect(normalizeRecoveryCode(" ab-cd 12 ")).toBe("ABCD12");
  });
});
