// @vitest-environment jsdom
//
// ?tour=field:<data-tour-id> - the parse/strip/resolve half of the deep link
// (the spotlight itself is FieldSpotlight). Pure helpers, so the rules are
// tested directly: a value that cannot be a data-tour marker never reaches a
// querySelector, and stripping the parameter never eats anyone else's query.

import { afterEach, describe, expect, it } from "vitest";
import { fieldFromSearch, fieldHref, parseFieldParam, resolveFieldTarget, stripFieldParam } from "./deepLink";

afterEach(() => {
  document.body.innerHTML = "";
});

describe("parseFieldParam", () => {
  it("reads the id after field:", () => {
    expect(parseFieldParam("field:secret-add")).toBe("secret-add");
    expect(parseFieldParam("  field:workbench_max_tokens ")).toBe("workbench_max_tokens");
  });

  it("ignores other tour modes and anything that is not a plain id", () => {
    for (const bad of [null, undefined, "", "court", "field:", "field:a b", 'field:x"]', "field:a[b]", "FIELD:x", "x:field:y"]) {
      expect(parseFieldParam(bad as string | null | undefined)).toBeNull();
    }
  });
});

describe("fieldFromSearch", () => {
  it("finds the parameter among others, with or without the leading ?", () => {
    expect(fieldFromSearch("?name=TALLY&tour=field:secret-add")).toBe("secret-add");
    expect(fieldFromSearch("tour=field:secret-add")).toBe("secret-add");
  });

  it("decodes an encoded colon", () => {
    expect(fieldFromSearch("?tour=field%3Asecret-add")).toBe("secret-add");
  });

  it("is null when absent or not a field link", () => {
    expect(fieldFromSearch("")).toBeNull();
    expect(fieldFromSearch("?a=1")).toBeNull();
    expect(fieldFromSearch("?tour=court")).toBeNull();
    expect(fieldFromSearch(undefined)).toBeNull();
  });
});

describe("stripFieldParam", () => {
  it("removes only the tour parameter and keeps the rest and the hash", () => {
    expect(stripFieldParam("/secrets?tour=field:secret-add&name=TALLY_API_KEY#top")).toBe("/secrets?name=TALLY_API_KEY#top");
  });

  it("drops the ? when nothing else is left", () => {
    expect(stripFieldParam("/secrets?tour=field:secret-add")).toBe("/secrets");
  });

  it("leaves a URL that is not a field link exactly as it was", () => {
    expect(stripFieldParam("/secrets")).toBe("/secrets");
    expect(stripFieldParam("/x?tour=court&a=1")).toBe("/x?tour=court&a=1");
  });
});

describe("fieldHref", () => {
  it("round-trips through fieldFromSearch and keeps an existing query and hash", () => {
    const href = fieldHref("/secrets?name=A#h", "secret-add");
    expect(href).toBe("/secrets?name=A&tour=field:secret-add#h");
    expect(fieldFromSearch(href.slice(href.indexOf("?"), href.indexOf("#")))).toBe("secret-add");
  });
});

describe("resolveFieldTarget", () => {
  it("finds the marked element and never throws on a hostile id", () => {
    document.body.innerHTML = '<button data-tour="secret-add">Add</button>';
    expect(resolveFieldTarget("secret-add")?.textContent).toBe("Add");
    expect(resolveFieldTarget("missing")).toBeNull();
    expect(resolveFieldTarget('x"] , body')).toBeNull();
  });
});
