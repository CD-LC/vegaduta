// One-shot RTL codemod: convert physical Tailwind utilities to logical ones
// inside class-name strings across app/**. Run: node scripts/rtl-sweep.mjs
import { readdirSync, readFileSync, writeFileSync, statSync } from "node:fs";
import path from "node:path";

const ROOTS = ["app"];
const EXTS = new Set([".tsx", ".ts", ".jsx", ".js"]);

// Implemented as a tokenizer: each string literal is split on whitespace and
// every token's last variant segment is matched against the physical ->
// logical map. Never matches inside a word (the token must be standalone after
// wrapper chars are peeled), so xml-, html-, border-lime, rounded-lg survive.
function convertToken(tok) {
  // Strip wrapper chars tokens can carry inside strings: quotes ('x'), braces
  // (${x}), parens, commas — e.g. "'mr-1'" from `${x ? 'mr-1' : ''}`.
  const leadM = /^[^A-Za-z-]+/.exec(tok);
  const lead = leadM ? leadM[0] : "";
  const core0 = tok.slice(lead.length);
  const trailM = /[^A-Za-z0-9\-\[\]\/!.]+$/.exec(core0);
  const trail = trailM ? trailM[0] : "";
  const core = core0.slice(0, core0.length - trail.length);
  if (!core) return tok;
  // tok includes any leading variant prefixes already split off (we split on
  // whitespace, not on ':'). Variants stay attached to the token: "md:ml-2"
  // arrives whole; only the LAST segment after the final ':' is a candidate.
  const i = core.lastIndexOf(":");
  const head = i >= 0 ? core.slice(0, i + 1) : "";
  let tail = i >= 0 ? core.slice(i + 1) : core;
  const neg = tail.startsWith("-") ? "-" : "";
  const body = neg ? tail.slice(1) : tail;

  const map = {
    "ml-": "ms-",
    "mr-": "me-",
    "pl-": "ps-",
    "pr-": "pe-",
    "text-left": "text-start",
    "text-right": "text-end",
    "scroll-ml-": "scroll-ms-",
    "scroll-mr-": "scroll-me-",
    "float-left": "float-start",
    "float-right": "float-end",
  };
  const wrap = (v) => lead + v + trail;
  for (const [from, to] of Object.entries(map)) {
    if (body.startsWith(from)) return wrap(head + neg + to + body.slice(from.length));
  }
  // Side corners: rounded-l-*/rounded-r-* (NOT rounded-lg), border-l/r only
  // when followed by a width (-2/-4/...) — bare border-l on physically
  // docked panels is reviewed by hand, not swept.
  if (/^rounded-l-/.test(body)) return wrap(head + neg + "rounded-s-" + body.slice(10));
  if (/^rounded-r-/.test(body)) return wrap(head + neg + "rounded-e-" + body.slice(10));
  if (/^rounded-tl(-|$)/.test(body)) return wrap(head + neg + "rounded-ss" + body.slice(10));
  if (/^rounded-tr(-|$)/.test(body)) return wrap(head + neg + "rounded-se" + body.slice(10));
  if (/^rounded-bl(-|$)/.test(body)) return wrap(head + neg + "rounded-es" + body.slice(10));
  if (/^rounded-br(-|$)/.test(body)) return wrap(head + neg + "rounded-ee" + body.slice(10));
  if (/^border-l-[0-9.]+/.test(body)) return head + neg + "border-s-" + body.slice(9);
  if (/^border-r-[0-9.]+/.test(body)) return head + neg + "border-e-" + body.slice(9);
  return tok;
  // re-wrap below via the caller
}

// Only rewrite inside quotes/backticks that plausibly hold class names:
// any string containing a space-separated tailwind-looking token, or any
// string immediately following className={ | className=" | in a cn()/cva()
// arg. Cheap-and-good-enough heuristic for a reviewed one-shot pass: convert
// inside ANY string literal that contains one of our tokens, since `ml-2`
// etc. never legitimately appear in non-class strings here.
const TOKEN_RE =
  /(?<![\w$])-?(m[lr]-|p[lr]-|text-left|text-right|scroll-m[lr]-|float-left|float-right|rounded-[lrtb][lr]?-|border-[lr]-[0-9])/;

function* strings(src) {
  // yields [start, end) ranges of '..' ".." `..` literals (no escape handling
  // beyond \' \" \` - sufficient for class strings).
  let i = 0;
  const n = src.length;
  while (i < n) {
    const c = src[i];
    if (c === '"' || c === "'" || c === "`") {
      const q = c;
      let j = i + 1;
      while (j < n && src[j] !== q) {
        if (src[j] === "\\") j++;
        // template literal ${} - recurse into it so tokens inside expressions
        // are also handled (class strings inside `${}` ternaries)
        j++;
      }
      yield [i, j + 1];
      i = j + 1;
    } else if (c === "/" && src[i + 1] === "/") {
      while (i < n && src[i] !== "\n") i++;
    } else if (c === "/" && src[i + 1] === "*") {
      while (i < n && !(src[i] === "*" && src[i + 1] === "/")) i++;
      i += 2;
    } else {
      i++;
    }
  }
}

function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const p = path.join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) {
      if (name === "node_modules" || name.startsWith(".")) continue;
      walk(p, out);
    } else if (EXTS.has(path.extname(name))) {
      out.push(p);
    }
  }
  return out;
}

let touched = 0;
let replacements = 0;
for (const root of ROOTS) {
  for (const file of walk(root)) {
    const src = readFileSync(file, "utf8");
    let out = "";
    let last = 0;
    let fileRepls = 0;
    for (const [s, e] of strings(src)) {
      const lit = src.slice(s, e);
      if (!TOKEN_RE.test(lit)) continue;
      // Rewrite each whitespace-separated token inside the literal.
      const converted = lit
        .split(/(\s+)/)
        .map((part) => (/^\s+$/.test(part) ? part : convertToken(part)))
        .join("");
      if (converted !== lit) {
        fileRepls++;
        replacements += 1;
        out += src.slice(last, s) + converted;
        last = e;
      }
    }
    if (last > 0) {
      out += src.slice(last);
      writeFileSync(file, out, "utf8");
      touched++;
      console.log(`${file}: ${fileRepls} literal(s)`);
    }
  }
}
console.log(`\n${touched} files, ${replacements} literal(s) rewritten`);
