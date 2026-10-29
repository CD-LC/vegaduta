"use client";

import { Fragment, type ReactNode } from "react";
import NextLink from "next/link";
import SafeModelImage from "./SafeModelImage";

// Lightweight markdown renderer for LLM chat output (playground, chat panel,
// demo sandbox, quick-task history). Before this, assistant replies were shown
// as raw `whitespace-pre-wrap` text, so a model answering with markdown printed
// literal "[Docs](https://…)" and bare URLs were not clickable at all - the
// exact complaint this fixes.
//
// SECURITY: this deliberately does NOT use dangerouslySetInnerHTML and does not
// depend on a sanitiser. Everything below builds React nodes, so model output
// can never inject markup - the worst a hostile string can do is render as
// text. Link hrefs are additionally scheme-checked (http/https/mailto, or a console page path), so
// a `javascript:` or `data:` URL from a prompt-injected model reply cannot
// become a clickable exploit. External links carry noopener/noreferrer.
//
// Scope is deliberately small (links, bold, italic, inline code, fenced code,
// bullet/numbered lists, headings). It is not a CommonMark implementation; it
// covers what chat models actually emit. Anything unrecognised falls through as
// plain text rather than being mangled.

const SAFE_SCHEME = /^(https?:|mailto:)/i;

/**
 * A console page link ("/tools", "/admin/channels?channel=slack"): one slash, then a path. This is how the
 * Assistant sends someone to the page that sets a thing up. Protocol-relative ("//host") and backslash
 * forms are not pages of this app, and "/api/" is data, not a page, so none of those become links.
 */
const APP_PAGE = /^\/(?![/\\])(?!api(\/|$|\?))[A-Za-z0-9_\-./?=&%#]*$/;

/** Bare URLs and markdown links, tokenized in one pass so we never double-wrap. */
const INLINE_PATTERN = new RegExp(
  [
    "!\\[([^\\]]*)\\]\\(((?:[^()\\s]|\\([^()\\s]*\\))+)\\)", // ![alt](src), one level of (parens) in src - gated by SafeModelImage
    "\\[([^\\]]+)\\]\\(([^)\\s]+)\\)", // [label](url)
    "`([^`]+)`", // `code`
    "\\*\\*([^*]+)\\*\\*", // **bold**
    "\\*([^*]+)\\*", // *italic*
    "(https?://[^\\s<>()]+[^\\s<>().,;:!?'\"])", // bare url
  ].join("|"),
  "g"
);

function Link({ href, children }: { href: string; children: ReactNode }) {
  if (APP_PAGE.test(href)) {
    return (
      <NextLink
        href={href}
        className="font-medium text-primary underline underline-offset-2 hover:opacity-80 break-words"
      >
        {children}
      </NextLink>
    );
  }
  if (!SAFE_SCHEME.test(href)) return <>{children}</>; // refuse javascript:/data:
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer nofollow"
      className="font-medium text-primary underline underline-offset-2 hover:opacity-80 break-words"
    >
      {children}
    </a>
  );
}

function renderInline(text: string, keyPrefix: string): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  INLINE_PATTERN.lastIndex = 0;
  let i = 0;
  while ((m = INLINE_PATTERN.exec(text)) !== null) {
    if (m.index > last) out.push(text.slice(last, m.index));
    const k = `${keyPrefix}-i${i++}`;
    const [, imgAlt, imgSrc, label, url, code, bold, italic, bareUrl] = m;
    if (imgSrc) {
      out.push(<SafeModelImage key={k} src={imgSrc} alt={imgAlt} />);
    } else if (label && url) {
      out.push(
        <Link key={k} href={url}>
          {label}
        </Link>
      );
    } else if (code) {
      out.push(
        <code key={k} className="rounded bg-muted px-1.5 py-0.5 font-mono text-[0.85em]">
          {code}
        </code>
      );
    } else if (bold) {
      out.push(
        <strong key={k} className="font-semibold">
          {bold}
        </strong>
      );
    } else if (italic) {
      out.push(<em key={k}>{italic}</em>);
    } else if (bareUrl) {
      out.push(
        <Link key={k} href={bareUrl}>
          {bareUrl}
        </Link>
      );
    }
    last = m.index + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

type Block =
  | { kind: "code"; lang: string; lines: string[] }
  | { kind: "ul" | "ol"; items: string[] }
  | { kind: "h"; level: number; text: string }
  | { kind: "p"; lines: string[] }
  | { kind: "table"; header: string[]; rows: string[][] }
  | { kind: "quote"; lines: string[] }
  | { kind: "hr" };

/** `| a | b |` row -> cell text array, trimmed. Tolerates a missing leading/trailing pipe. */
function splitTableRow(line: string): string[] {
  return line
    .trim()
    .replace(/^\|/, "")
    .replace(/\|$/, "")
    .split("|")
    .map((c) => c.trim());
}

/** `| --- | :--- | ---: |` (with optional alignment colons) - the separator row every real markdown table has between header and body. */
const TABLE_SEPARATOR = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/;

function parseBlocks(src: string): Block[] {
  const lines = src.replace(/\r\n/g, "\n").split("\n");
  const blocks: Block[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];

    if (/^```/.test(line)) {
      const lang = line.slice(3).trim();
      const body: string[] = [];
      i++;
      while (i < lines.length && !/^```/.test(lines[i])) body.push(lines[i++]);
      i++; // closing fence (or EOF)
      blocks.push({ kind: "code", lang, lines: body });
      continue;
    }

    const heading = /^(#{1,4})\s+(.*)$/.exec(line);
    if (heading) {
      blocks.push({ kind: "h", level: heading[1].length, text: heading[2] });
      i++;
      continue;
    }

    if (/^\s*[-*•]\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*[-*•]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*[-*•]\s+/, ""));
        i++;
      }
      blocks.push({ kind: "ul", items });
      continue;
    }

    if (/^\s*\d+[.)]\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*\d+[.)]\s+/.test(lines[i])) {
        items.push(lines[i].replace(/^\s*\d+[.)]\s+/, ""));
        i++;
      }
      blocks.push({ kind: "ol", items });
      continue;
    }

    // GitHub-style table: a `| cell | cell |` row immediately followed by a
    // `| --- | --- |` separator row. Without this, every pipe character in a
    // model's tabular reply (a common shape for structured tool output - a
    // created ticket's fields, a comparison, search results) rendered as
    // literal `|`s in a run-on paragraph instead of an actual table.
    if (line.includes("|") && i + 1 < lines.length && TABLE_SEPARATOR.test(lines[i + 1])) {
      const header = splitTableRow(line);
      i += 2; // header + separator
      const rows: string[][] = [];
      while (i < lines.length && lines[i].includes("|") && lines[i].trim() !== "") {
        rows.push(splitTableRow(lines[i]));
        i++;
      }
      blocks.push({ kind: "table", header, rows });
      continue;
    }

    if (/^\s*>\s?/.test(line)) {
      const qLines: string[] = [];
      while (i < lines.length && /^\s*>\s?/.test(lines[i])) {
        qLines.push(lines[i].replace(/^\s*>\s?/, ""));
        i++;
      }
      blocks.push({ kind: "quote", lines: qLines });
      continue;
    }

    if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      blocks.push({ kind: "hr" });
      i++;
      continue;
    }

    if (line.trim() === "") {
      i++;
      continue;
    }

    const para: string[] = [];
    while (
      i < lines.length &&
      lines[i].trim() !== "" &&
      !/^```/.test(lines[i]) &&
      !/^\s*[-*•]\s+/.test(lines[i]) &&
      !/^\s*\d+[.)]\s+/.test(lines[i]) &&
      !/^#{1,4}\s+/.test(lines[i]) &&
      !/^\s*>\s?/.test(lines[i]) &&
      !/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(lines[i]) &&
      !(lines[i].includes("|") && i + 1 < lines.length && TABLE_SEPARATOR.test(lines[i + 1]))
    ) {
      para.push(lines[i++]);
    }
    blocks.push({ kind: "p", lines: para });
  }
  return blocks;
}

export default function RichText({ text, className = "" }: { text: string; className?: string }) {
  if (!text) return null;
  const blocks = parseBlocks(text);
  return (
    <div className={`space-y-2 text-sm leading-relaxed [overflow-wrap:anywhere] ${className}`}>
      {blocks.map((b, bi) => {
        const key = `b${bi}`;
        if (b.kind === "code") {
          return (
            <div key={key} className="overflow-hidden rounded-lg border border-border">
              {b.lang && (
                <div className="border-b border-border bg-muted px-3 py-1 font-mono text-[0.7rem] uppercase tracking-wide text-muted-foreground">
                  {b.lang}
                </div>
              )}
              <pre className="overflow-x-auto bg-muted/60 p-3">
                <code className="font-mono text-xs">{b.lines.join("\n")}</code>
              </pre>
            </div>
          );
        }
        if (b.kind === "table") {
          return (
            <div key={key} className="overflow-x-auto rounded-lg border border-border">
              <table className="w-full border-collapse text-start text-sm">
                <thead>
                  <tr className="border-b border-border bg-muted/60">
                    {b.header.map((cell, ci) => (
                      <th key={`${key}-h${ci}`} className="px-3 py-1.5 font-semibold">
                        {renderInline(cell, `${key}-h${ci}`)}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {b.rows.map((row, ri) => (
                    <tr key={`${key}-r${ri}`} className={ri > 0 ? "border-t border-border" : undefined}>
                      {row.map((cell, ci) => (
                        <td key={`${key}-r${ri}-c${ci}`} className="px-3 py-1.5 align-top">
                          {renderInline(cell, `${key}-r${ri}-c${ci}`)}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          );
        }
        if (b.kind === "quote") {
          return (
            <div key={key} className="space-y-1 border-s-2 border-primary/40 ps-3 text-muted-foreground italic">
              {b.lines.map((ln, li) => (
                <p key={`${key}-${li}`}>{renderInline(ln, `${key}-${li}`)}</p>
              ))}
            </div>
          );
        }
        if (b.kind === "hr") {
          return <hr key={key} className="border-border" />;
        }
        if (b.kind === "h") {
          const size = b.level <= 2 ? "text-base" : "text-sm";
          return (
            <p key={key} className={`${size} font-semibold`}>
              {renderInline(b.text, key)}
            </p>
          );
        }
        if (b.kind === "ul" || b.kind === "ol") {
          const ListTag = b.kind === "ul" ? "ul" : "ol";
          return (
            <ListTag key={key} className={`ml-5 space-y-1 ${b.kind === "ul" ? "list-disc" : "list-decimal"}`}>
              {b.items.map((it, ii) => (
                <li key={`${key}-${ii}`}>{renderInline(it, `${key}-${ii}`)}</li>
              ))}
            </ListTag>
          );
        }
        if (b.kind !== "p") return null;
        return (
          <p key={key}>
            {b.lines.map((ln, li) => (
              <Fragment key={`${key}-${li}`}>
                {li > 0 && <br />}
                {renderInline(ln, `${key}-${li}`)}
              </Fragment>
            ))}
          </p>
        );
      })}
    </div>
  );
}
