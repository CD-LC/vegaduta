import { describe, expect, it } from "vitest";
import { checkHostname, dnsRecordRows, domainErrorMessage, type DomainView } from "./domains";

describe("checkHostname", () => {
  it("normalises valid hosts", () => {
    expect(checkHostname(" App.Example-Co.com. ")).toEqual({ host: "app.example-co.com" });
  });
  it("rejects bad input", () => {
    for (const bad of ["", "single", "*.a.com", "1.2.3.4", "x.example", "foo.invalid", "a..b.com", "-a.com", "a.com:80"]) {
      expect("error" in checkHostname(bad)).toBe(true);
    }
    expect("host" in checkHostname("notexample.com")).toBe(true);
  });
});

describe("dnsRecordRows", () => {
  const base: DomainView = {
    id: "1", hostname: "a.b.com", status: "PENDING", lastError: null,
    dns: { txtName: "_vegaduta-verify.a.b.com", txtValue: "vd-verify-x", cnameTarget: "edge.example.net", aTarget: null },
  };
  it("lists CNAME then TXT, skipping unconfigured targets", () => {
    expect(dnsRecordRows(base).map((r) => r.type)).toEqual(["CNAME", "TXT"]);
    const onlyTxt = dnsRecordRows({ ...base, dns: { ...base.dns, cnameTarget: null } });
    expect(onlyTxt).toHaveLength(1);
    expect(onlyTxt[0].name).toBe("_vegaduta-verify.a.b.com");
  });
  it("maps error codes", () => {
    expect(domainErrorMessage("txt_mismatch")).toMatch(/does not match/);
    expect(domainErrorMessage(null)).toBe("");
  });
});

import { domainErrorText } from "./domains";
describe("domainErrorText", () => {
  it("maps codes, passes prose, falls back", () => {
    expect(domainErrorText(new Error("hostname_claimed"), "x")).toMatch(/another account/);
    expect(domainErrorText(new Error("Service is unavailable (HTTP 503)"), "x")).toBe("Service is unavailable (HTTP 503)");
    expect(domainErrorText(new Error(""), "fallback")).toBe("fallback");
    expect(domainErrorText("boom", "fallback")).toBe("fallback");
  });
});
