import { afterEach, describe, expect, it } from "vitest";
import { clearAuthedHint, hasAuthedHint, markAuthedHint } from "./authHint";

function hintCookie(): string | undefined {
  return document.cookie
    .split("; ")
    .find((c) => c.startsWith("vd_authed="))
    ?.split("=")[1];
}

describe("vd_authed hint", () => {
  afterEach(() => clearAuthedHint());

  it("lives exactly as long as the session it describes", () => {
    markAuthedHint(Date.now() + 60_000);
    expect(hasAuthedHint()).toBe(true);
    expect(hintCookie()).toBe("1");
  });

  it("an expired session's hint reads as absent and is purged", () => {
    window.localStorage.setItem("vd_authed", "1");
    window.localStorage.setItem("vd_authed_exp", String(Date.now() - 1));
    expect(hasAuthedHint()).toBe(false);
    expect(window.localStorage.getItem("vd_authed")).toBeNull();
  });

  it("is never written for a session that has already ended", () => {
    markAuthedHint(Date.now() - 1000);
    expect(hasAuthedHint()).toBe(false);
    expect(hintCookie()).toBeUndefined();
  });

  it("clear removes both the localStorage half and the server-read cookie", () => {
    markAuthedHint(Date.now() + 60_000);
    clearAuthedHint();
    expect(hasAuthedHint()).toBe(false);
    expect(hintCookie()).toBeUndefined();
  });
});
