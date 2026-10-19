// agentkit — path confinement and secret-file detection.
//
// Every path in a tool call is MODEL OUTPUT, i.e. untrusted: a small model
// asked to "look at the config" will happily try "../../.ssh/config", and a
// repository's own files can carry text that talks the model into it. The loop
// normalises every path through confinePath() BEFORE any host sees it, so the
// rule holds identically for the browser host and for any native host added
// later.
//
// What this does and does not cover (stated because it is a security check):
//   - LEXICAL escape — "..", absolute paths, drive letters, UNC, home "~",
//     NUL bytes, URL-ish schemes — is rejected here, not resolved. Rejecting is
//     the point: "a/../../b" is never a path a coding task needs, and resolving
//     it is how an off-by-one becomes an escape.
//   - SYMLINK escape cannot be seen from a string. The browser host never has
//     an OS path to follow: it walks FileSystemDirectoryHandles down from the
//     granted root one segment at a time, and that API has no "parent" and no
//     way to open a path — what a symlink inside the folder does is the
//     browser's decision, not ours, and we do not claim more than that. A
//     NATIVE host must real-path every resolved path against the root
//     (clients/vscode/src/agent/containment.ts is the reference); that duty is
//     written into the HostFs contract in types.ts.

export type ConfinedPath = { ok: true; path: string; segments: string[] } | { ok: false; reason: string };

const MAX_PATH_CHARS = 1024;
/** Windows reserved device names — opening "con" or "nul" as a file does not do
 *  what any tool call means. */
const WINDOWS_DEVICE = /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$/i;

/**
 * Normalise a model-supplied path to a root-relative, "/"-separated one, or
 * refuse it. "." and "" mean the root itself (`segments: []`), which only
 * list_dir accepts.
 */
export function confinePath(raw: unknown): ConfinedPath {
  if (typeof raw !== "string") return { ok: false, reason: "the path must be a string" };
  let path = raw.trim();
  if (path.length > MAX_PATH_CHARS) return { ok: false, reason: "the path is too long" };
  if (path.includes("\0")) return { ok: false, reason: "the path contains a NUL byte" };
  // Wrapping quotes/backticks are a small-model habit, not part of the name.
  path = path.replace(/^["'`]+|["'`]+$/g, "");
  path = path.replace(/\\/g, "/");

  if (/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(path)) {
    return { ok: false, reason: "that is a URL, not a path inside the folder" };
  }
  if (/^[a-zA-Z]:/.test(path)) return { ok: false, reason: "drive-letter paths are outside the folder" };
  if (path.startsWith("//")) return { ok: false, reason: "network paths are outside the folder" };
  if (path.startsWith("/")) return { ok: false, reason: "absolute paths are outside the folder — use a path relative to the project root" };
  if (path === "~" || path.startsWith("~/")) return { ok: false, reason: "home-directory paths are outside the folder" };

  const segments: string[] = [];
  for (const part of path.split("/")) {
    if (part === "" || part === ".") continue;
    if (part === "..") return { ok: false, reason: '".." would leave the folder you opened' };
    // A segment of only dots/spaces ("...", ". .") is a traversal trick on
    // Windows, which strips trailing dots and spaces when resolving.
    if (/^[.\s]+$/.test(part)) return { ok: false, reason: "that path segment is not a real file name" };
    if (/[<>:"|?*]/.test(part)) return { ok: false, reason: "the path contains characters a file name cannot have" };
    if (WINDOWS_DEVICE.test(part)) return { ok: false, reason: "that is a reserved device name, not a file" };
    segments.push(part);
  }
  return { ok: true, path: segments.join("/"), segments };
}

// ---------------------------------------------------------------------------
// Secrets
// ---------------------------------------------------------------------------

const SECRET_BASENAMES = new Set([
  ".env",
  ".npmrc",
  ".pypirc",
  ".netrc",
  ".htpasswd",
  ".git-credentials",
  "id_rsa",
  "id_dsa",
  "id_ecdsa",
  "id_ed25519",
  "credentials",
  "credentials.json",
  "secrets.json",
  "secrets.yaml",
  "secrets.yml",
  "service-account.json",
  "terraform.tfvars",
  "kubeconfig",
]);

const SECRET_EXTENSIONS = new Set(["pem", "key", "p12", "pfx", "jks", "keystore", "ppk", "asc", "gpg", "tfstate"]);

const SECRET_DIRECTORIES = new Set([".ssh", ".aws", ".gnupg", ".azure", ".kube"]);

/**
 * Does this path look like it holds credentials? Deliberately over-inclusive
 * (".env.example" matches): the cost of a false positive is one extra click,
 * the cost of a false negative is a private key in a model prompt and in the
 * run state saved for resume. Reading such a file needs an explicit yes for
 * THAT file; search_text skips them without asking.
 */
export function isSecretPath(path: string): boolean {
  const segments = path.replace(/\\/g, "/").toLowerCase().split("/").filter(Boolean);
  if (segments.length === 0) return false;
  for (let i = 0; i < segments.length - 1; i += 1) {
    if (SECRET_DIRECTORIES.has(segments[i])) return true;
  }
  const base = segments[segments.length - 1];
  if (SECRET_BASENAMES.has(base)) return true;
  if (base === ".env" || base.startsWith(".env.") || base.endsWith(".env")) return true;
  if (base.startsWith("id_rsa") || base.startsWith("id_ed25519")) return true;
  const dot = base.lastIndexOf(".");
  if (dot > 0 && SECRET_EXTENSIONS.has(base.slice(dot + 1))) return true;
  return false;
}

// ---------------------------------------------------------------------------
// Include globs for search_text
// ---------------------------------------------------------------------------

/**
 * Compile the small glob dialect search_text's `include` accepts ("src/**",
 * "**\/*.ts", "*.{ts,tsx}") to a RegExp over root-relative paths. Returns null
 * for an empty or unusable pattern, which callers read as "no filter" — a
 * malformed include must narrow nothing rather than silently match nothing.
 */
export function compileIncludeGlob(glob: unknown): RegExp | null {
  if (typeof glob !== "string") return null;
  const pattern = glob.trim().replace(/\\/g, "/").replace(/^\.?\//, "");
  if (!pattern || pattern === "**" || pattern === "**/*" || pattern.length > 256) return null;
  let source = "";
  for (let i = 0; i < pattern.length; i += 1) {
    const ch = pattern[i];
    if (ch === "*") {
      if (pattern[i + 1] === "*") {
        // "**/" spans zero or more directories; a bare "**" spans anything.
        if (pattern[i + 2] === "/") {
          source += "(?:.*/)?";
          i += 2;
        } else {
          source += ".*";
          i += 1;
        }
      } else {
        source += "[^/]*";
      }
    } else if (ch === "?") {
      source += "[^/]";
    } else if (ch === "{") {
      const close = pattern.indexOf("}", i);
      if (close === -1) return null;
      const options = pattern
        .slice(i + 1, close)
        .split(",")
        .map((o) => o.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
      source += `(?:${options.join("|")})`;
      i = close;
    } else {
      source += ch.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    }
  }
  // A pattern with no slash ("*.ts") matches at any depth, the way people mean it.
  const anchored = pattern.includes("/") ? `^${source}$` : `^(?:.*/)?${source}$`;
  try {
    return new RegExp(anchored, "i");
  } catch {
    return null;
  }
}

/**
 * The leading folder segments of an include glob that contain no wildcard:
 * "src/lib/**" → ["src", "lib"], "**\/*.ts" → [], "*.ts" → []. Lower-cased,
 * because compileIncludeGlob matches case-insensitively. A search walk uses it
 * to skip whole folders the glob can never match, which is what makes "narrow
 * it with include" real advice on a large folder. Only ever a subset of what
 * the compiled glob accepts is pruned: the LAST segment is never part of the
 * prefix (it names files, not a folder to descend into).
 */
export function includeLiteralPrefix(glob: unknown): string[] {
  if (typeof glob !== "string" || !compileIncludeGlob(glob)) return [];
  const parts = glob.trim().replace(/\\/g, "/").replace(/^\.?\//, "").split("/");
  const prefix: string[] = [];
  for (let i = 0; i < parts.length - 1; i += 1) {
    if (!parts[i] || /[*?{}]/.test(parts[i])) break;
    prefix.push(parts[i].toLowerCase());
  }
  return prefix;
}

/** Could a file matching a glob with this literal prefix live in, or under, the
 *  folder at `dirSegments`? */
export function dirCanMatchPrefix(dirSegments: string[], prefix: string[]): boolean {
  const shared = Math.min(dirSegments.length, prefix.length);
  for (let i = 0; i < shared; i += 1) {
    if (dirSegments[i].toLowerCase() !== prefix[i]) return false;
  }
  return true;
}

// ---------------------------------------------------------------------------
// Version-control internals
// ---------------------------------------------------------------------------

/**
 * Is this path inside a `.git` folder (at any depth — submodules and nested
 * checkouts have their own)? The agent never reads, edits or writes there:
 * `.git/hooks/*` and `.git/config` (core.sshCommand, core.fsmonitor) are text
 * that the person's NEXT git command executes, and `.git/config` can hold a
 * remote URL with a token in it. No coding task needs any of it. Compared
 * case-insensitively because Windows and default macOS volumes resolve ".GIT"
 * to the same folder.
 */
export function isVcsInternalPath(path: string): boolean {
  return path
    .replace(/\\/g, "/")
    .split("/")
    .some((segment) => segment.toLowerCase() === ".git");
}
