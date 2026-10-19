import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import BrandText from "./BrandText";

describe("BrandText", () => {
  it("renders the wordmark for {brand} and bold for <strong>", () => {
    const html = renderToStaticMarkup(<BrandText text="On {brand} you <strong>build</strong>." />);
    expect(html).toContain("brand-name");
    expect(html).toContain("<strong>build</strong>");
    expect(html).not.toContain("{brand}");
  });
  it("passes plain text through", () => {
    expect(renderToStaticMarkup(<BrandText text="plain" />)).toBe("plain");
  });
});
