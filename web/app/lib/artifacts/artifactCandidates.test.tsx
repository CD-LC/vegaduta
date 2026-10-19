// Prod signed-in pass 2026-10-05, bug 9: "make it as an artifact" came back as
// an ordinary ```html block with no artifact chip. The web side now OFFERS the
// pane for a complete, substantial html/svg block - and only on a click.

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { findCandidates, mightContainCandidate, promoteCandidates, splitCandidates, MIN_CANDIDATE_CHARS } from "./artifactCandidates";
import { parseArtifacts } from "./artifactParser";
import { ARTIFACTS_STORAGE_KEY } from "./artifactsFlag";

vi.mock("../i18n", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => (vars ? `${key}:${JSON.stringify(vars)}` : key),
}));

const PAGE = [
  "<!doctype html>",
  "<html>",
  "<head><title>e2e artifact</title></head>",
  "<body>",
  "  <h1 id=\"h\">e2e artifact</h1>",
  "  <p>A tiny single-page HTML page, long enough to be a deliverable and not a snippet.</p>",
  "  <script>document.getElementById('h').textContent = 'script ran';</script>",
  "</body>",
  "</html>",
].join("\n");

const fence = (info: string, body: string, ticks = "```") => `${ticks}${info}\n${body}\n${ticks}`;
const REPLY = `Here is the page you asked for:\n\n${fence("html", PAGE)}\n\nLet me know if you want changes.`;

describe("artifactCandidates - which blocks get the offer", () => {
  it("finds a complete, substantial ```html block and titles it from the page", () => {
    expect(PAGE.length).toBeGreaterThan(MIN_CANDIDATE_CHARS);
    expect(mightContainCandidate(REPLY)).toBe(true);
    const found = findCandidates(REPLY);
    expect(found).toHaveLength(1);
    expect(found[0]).toMatchObject({ kind: "html", content: PAGE, title: "e2e artifact" });
  });

  it("finds an svg block", () => {
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">${"<circle cx=\"50\" cy=\"50\" r=\"40\" />".repeat(8)}</svg>`;
    expect(findCandidates(fence("svg", svg))[0]).toMatchObject({ kind: "svg", title: "SVG image" });
  });

  it("splitting is lossless", () => {
    expect(splitCandidates(REPLY).map((p) => p.text).join("\n")).toBe(REPLY);
  });

  it("ignores a block that is still streaming (no closing fence)", () => {
    expect(findCandidates("Here:\n\n```html\n" + PAGE)).toEqual([]);
  });

  it("ignores a small snippet, a non-html language, and html that is not markup", () => {
    expect(findCandidates(fence("html", "<b>bold</b>"))).toEqual([]);
    expect(findCandidates(fence("python", PAGE))).toEqual([]);
    expect(findCandidates(fence("html", "just words ".repeat(40)))).toEqual([]);
  });

  it("never offers a block that is already an artifact, or one nested in another code block", () => {
    expect(findCandidates(fence("artifact:html:Mine", PAGE))).toEqual([]);
    expect(findCandidates(fence("markdown", fence("html", PAGE), "````"))).toEqual([]);
  });
});

describe("artifactCandidates - promotion is just a header rewrite", () => {
  it("returns the same string when nothing is promoted", () => {
    expect(promoteCandidates(REPLY, () => false)).toBe(REPLY);
  });

  it("turns the chosen block into a real artifact the existing parser accepts, body untouched", () => {
    const [candidate] = findCandidates(REPLY);
    const promoted = promoteCandidates(REPLY, (fp) => fp === candidate.fingerprint);
    expect(promoted).toContain("```artifact:html:e2e artifact\n");
    const artifacts = parseArtifacts(promoted);
    expect(artifacts).toHaveLength(1);
    expect(artifacts[0]).toMatchObject({ kind: "html", title: "e2e artifact", content: PAGE, complete: true });
  });

  it("a hostile <title> cannot break out of the fence header", () => {
    const evil = PAGE.replace("e2e artifact</title>", "x`‮```\nartifact:html:pwn</title>");
    const [c] = findCandidates(fence("html", evil));
    expect(c.title).not.toMatch(/[`\n‮]/);
    const out = parseArtifacts(promoteCandidates(fence("html", evil), () => true));
    expect(out).toHaveLength(1);
    expect(out[0].content).toBe(evil);
  });
});

// ---------------------------------------------------------------------------
// In the chat: offer -> click -> the ordinary artifact chip + pane, scripts off
// ---------------------------------------------------------------------------

async function renderChat(turns: Array<{ role: string; content: string }>, sending = false) {
  const { useArtifactsPane, ArtifactLayout } = await import("../../components/artifacts/useArtifactsPane");
  function Chat() {
    const artifacts = useArtifactsPane({ turns, sending, onAskPrefill: () => {} });
    return (
      <ArtifactLayout enabled={artifacts.enabled} pane={artifacts.pane}>
        {turns.map((turn, i) => (
          <div key={i} data-testid={`turn-${i}`}>
            {turn.role === "assistant"
              ? artifacts.renderAssistantText(turn.content, i, sending && i === turns.length - 1, (seg) => <pre>{seg}</pre>)
              : turn.content}
          </div>
        ))}
      </ArtifactLayout>
    );
  }
  return render(<Chat />);
}

describe("chat - Open as artifact", () => {
  beforeEach(() => window.localStorage.setItem(ARTIFACTS_STORAGE_KEY, "1"));
  afterEach(() => {
    cleanup();
    window.localStorage.clear();
  });
  const turns = [
    { role: "user", content: "make it as an artifact" },
    { role: "assistant", content: REPLY },
  ];

  it("shows the offer under the code block; nothing is opened or run until it is clicked", async () => {
    await renderChat(turns);
    expect(await screen.findByTestId("artifact-candidate-open")).toBeTruthy();
    expect(screen.queryByTestId("artifact-chip")).toBeNull();
    expect(document.querySelector("iframe")).toBeNull();
    // The block itself is still shown as the ordinary code block it was.
    expect(screen.getByTestId("turn-1").textContent).toContain("<!doctype html>");
  });

  it("clicking turns the block into the normal artifact chip and opens the pane with scripts NOT running", async () => {
    await renderChat(turns);
    fireEvent.click(await screen.findByTestId("artifact-candidate-open"));

    expect(await screen.findByTestId("artifact-chip")).toBeTruthy();
    expect(screen.queryByTestId("artifact-candidate-open")).toBeNull();
    // Same gate as a real artifact: any frame that exists has an empty sandbox (no allow-scripts).
    for (const frame of Array.from(document.querySelectorAll("iframe"))) {
      expect(frame.getAttribute("sandbox") ?? "").not.toContain("allow-scripts");
      expect(frame.getAttribute("sandbox") ?? "").not.toContain("allow-same-origin");
    }
    expect(document.body.textContent).toContain("e2e artifact");
  });

  it("makes no offer while the reply is still streaming", async () => {
    await renderChat(turns, true);
    expect(screen.queryByTestId("artifact-candidate-open")).toBeNull();
  });

  it("is inert when the artifacts feature is off", async () => {
    window.localStorage.clear();
    await renderChat(turns);
    expect(screen.queryByTestId("artifact-candidate-open")).toBeNull();
  });
});
