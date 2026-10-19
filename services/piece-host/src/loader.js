// Allow-list parsing and piece loading.
//
// pieces.txt is the single source of truth for which packages exist in an
// image: the Dockerfile installs exactly its entries (exact versions, no
// ranges) and this loader refuses to load anything that is not listed, even if
// a tenant-typed `--pieces` argument asks for it. Piece packages live under
// <PIECE_HOST_PIECES_DIR>/node_modules and are loaded with a plain dynamic
// import - the host bundles no third-party code of its own.
//
// Package naming/layout verified 2026-10-04:
//   https://registry.npmjs.org/@activepieces/piece-slack/latest  (main: ./src/index.js, CommonJS)
//   https://www.activepieces.com/docs/build-pieces/building-pieces/piece-definition.md
//     (package naming convention @activepieces/piece-{name})

import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { isValidPieceKey, packageForPieceKey, pieceKeyForPackage } from './names.js';

const VERSION = '\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?';
const PIECE_LINE_RE = new RegExp(`^(@activepieces/piece-[a-z0-9][a-z0-9-]*)@(${VERSION})$`);
// Framework support packages: installed so pieces that import them externally resolve, never loaded as pieces.
const SUPPORT_LINE_RE = new RegExp(`^(@activepieces/(?:pieces-framework|pieces-common|shared))@(${VERSION})$`);

/**
 * Parses pieces.txt. One `<package>@<exact version>` per line, `#` starts a
 * comment. Returns { pieces: [{ key, packageName, version }], support: [...], errors: [string] }.
 */
export function parseAllowlist(text) {
  const pieces = [];
  const support = [];
  const errors = [];
  const seen = new Set();
  String(text || '')
    .split(/\r?\n/)
    .forEach((rawLine, i) => {
      const line = rawLine.replace(/#.*$/, '').trim();
      if (!line) return;
      let m = PIECE_LINE_RE.exec(line);
      if (m) {
        const key = pieceKeyForPackage(m[1]);
        if (!key) {
          errors.push(`line ${i + 1}: invalid piece package name '${m[1]}'`);
        } else if (seen.has(key)) {
          errors.push(`line ${i + 1}: duplicate piece '${key}'`);
        } else {
          seen.add(key);
          pieces.push({ key, packageName: m[1], version: m[2] });
        }
        return;
      }
      m = SUPPORT_LINE_RE.exec(line);
      if (m) {
        support.push({ packageName: m[1], version: m[2] });
        return;
      }
      errors.push(`line ${i + 1}: '${line}' must be @activepieces/piece-<name>@<exact version> (no ranges, no other scopes)`);
    });
  return { pieces, support, errors };
}

export async function readAllowlist(file) {
  return parseAllowlist(await readFile(file, 'utf8'));
}

/** Importer that resolves packages from <piecesDir>/node_modules only via Node's normal resolution. */
export function createImporter(piecesDir) {
  const req = createRequire(path.join(path.resolve(piecesDir), 'noop.js'));
  return async (packageName) => {
    const resolved = req.resolve(packageName);
    return import(pathToFileURL(resolved).href);
  };
}

function isPiece(v) {
  return v !== null && typeof v === 'object' && typeof v.actions === 'function' && typeof v.displayName === 'string';
}

/**
 * Finds the Piece object in a loaded module. Published pieces are CommonJS
 * bundles that export the piece under a named key (e.g. `slack`); depending on
 * how Node's CJS named-export detection fares they may only be reachable via
 * `default`, so both are scanned.
 */
export function findPiece(mod) {
  if (isPiece(mod)) return mod;
  const holders = [mod, mod && mod.default].filter((h) => h !== null && typeof h === 'object');
  for (const holder of holders) {
    if (isPiece(holder)) return holder;
    for (const value of Object.values(holder)) {
      if (isPiece(value)) return value;
    }
  }
  return null;
}

function firstLine(err) {
  return String((err && err.message) || err).split('\n')[0].slice(0, 300);
}

/**
 * Loads the requested pieces (all allow-listed pieces when `requested` is
 * null/empty). Never throws for a single bad piece: failures are returned in
 * `errors` so the caller decides (serve the rest vs. --strict).
 */
export async function loadPieces({ allowlist, requested, importer }) {
  const byKey = new Map(allowlist.pieces.map((p) => [p.key, p]));
  const keys = requested && requested.length > 0 ? [...new Set(requested)] : allowlist.pieces.map((p) => p.key);
  const loaded = [];
  const errors = [];
  for (const key of keys) {
    if (!isValidPieceKey(key)) {
      errors.push({ pieceKey: String(key).slice(0, 80), message: 'not a valid piece key (lowercase letters, digits and dashes only)' });
      continue;
    }
    const entry = byKey.get(key);
    if (!entry) {
      errors.push({ pieceKey: key, message: `'${packageForPieceKey(key)}' is not on this image's allow-list (pieces.txt)` });
      continue;
    }
    try {
      const mod = await importer(entry.packageName);
      const piece = findPiece(mod);
      if (!piece) {
        errors.push({ pieceKey: key, message: 'package loaded but no Activepieces piece export was found in it' });
        continue;
      }
      loaded.push({ pieceKey: key, packageName: entry.packageName, version: entry.version, piece });
    } catch (err) {
      errors.push({ pieceKey: key, message: `failed to load: ${firstLine(err)}` });
    }
  }
  return { loaded, errors };
}
