import { describe, expect, it } from "vitest";
import { render } from "@testing-library/react";
import InlineStrong from "./InlineStrong";

describe("InlineStrong", () => {
  it("renders <strong> spans as elements and the rest as text", () => {
    const { container } = render(<p><InlineStrong text="Use <strong>agents</strong> for work." /></p>);
    expect(container.querySelector("strong")?.textContent).toBe("agents");
    expect(container.textContent).toBe("Use agents for work.");
  });

  it("never turns other markup into elements", () => {
    const hostile = 'Hi <img src=x onerror="alert(1)"><script>alert(2)</script> <strong>ok</strong>';
    const { container } = render(<p><InlineStrong text={hostile} /></p>);
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("script")).toBeNull();
    expect(container.textContent).toContain('<img src=x onerror="alert(1)">');
    expect(container.querySelectorAll("strong")).toHaveLength(1);
  });

  it("does not interpret markup nested inside <strong>", () => {
    const { container } = render(<p><InlineStrong text={"<strong><img src=x></strong>"} /></p>);
    expect(container.querySelector("img")).toBeNull();
    expect(container.querySelector("strong")?.textContent).toBe("<img src=x>");
  });
});
