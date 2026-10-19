// Best-effort SSRF guard for outbound connections made by pieces.
//
// Pieces take user/model-supplied values (instance URLs, webhook targets,
// file URLs) and call out with the tenant's credential attached. The runner
// container has no route to the platform's own networks, but cloud metadata
// endpoints (169.254.169.254), loopback and RFC1918 ranges can still be
// reachable from it, so this host refuses to connect to non-public addresses
// unless PIECE_HOST_ALLOW_PRIVATE_EGRESS=true.
//
// What this covers: sockets opened through http.Agent / https.Agent (axios,
// node-fetch style clients, the pieces' own httpClient) and global fetch().
// What it does NOT cover: DNS rebinding between
// the fetch() pre-check and the connection, redirects followed by fetch(),
// pieces that open raw net/tls sockets or spawn processes. The container's
// network policy remains the real egress boundary.

import dns from 'node:dns';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';

/** Parses a dotted IPv4 string into 4 numbers, or null. */
function parseIPv4(addr) {
  if (!net.isIPv4(addr)) return null;
  const parts = addr.split('.').map((p) => Number(p));
  return parts.length === 4 && parts.every((p) => Number.isInteger(p) && p >= 0 && p <= 255) ? parts : null;
}

function isBlockedIPv4(parts) {
  const [a, b, c] = parts;
  if (a === 0) return true; // 0.0.0.0/8 "this network"
  if (a === 10) return true; // RFC1918
  if (a === 100 && b >= 64 && b <= 127) return true; // 100.64/10 CGNAT
  if (a === 127) return true; // loopback
  if (a === 169 && b === 254) return true; // link-local incl. cloud metadata
  if (a === 172 && b >= 16 && b <= 31) return true; // RFC1918
  if (a === 192 && b === 0 && c === 0) return true; // 192.0.0.0/24 IETF protocol assignments
  if (a === 192 && b === 168) return true; // RFC1918
  if (a === 198 && (b === 18 || b === 19)) return true; // benchmarking
  if (a >= 224) return true; // multicast + reserved + broadcast
  return false;
}

/**
 * Expands an IPv6 literal to 8 numeric 16-bit groups, or null when malformed.
 * Handles "::" compression and a trailing embedded IPv4 (::ffff:1.2.3.4).
 */
export function expandIPv6(addr) {
  let text = String(addr).toLowerCase();
  const zone = text.indexOf('%');
  if (zone >= 0) text = text.slice(0, zone);
  if (!net.isIPv6(text)) return null;

  // Convert a trailing dotted quad into two hex groups.
  const lastColon = text.lastIndexOf(':');
  const tail = text.slice(lastColon + 1);
  if (tail.includes('.')) {
    const v4 = parseIPv4(tail);
    if (!v4) return null;
    const hi = ((v4[0] << 8) | v4[1]).toString(16);
    const lo = ((v4[2] << 8) | v4[3]).toString(16);
    text = `${text.slice(0, lastColon + 1)}${hi}:${lo}`;
  }

  const halves = text.split('::');
  if (halves.length > 2) return null;
  const head = halves[0] === '' ? [] : halves[0].split(':');
  const rest = halves.length === 2 ? (halves[1] === '' ? [] : halves[1].split(':')) : [];
  let groups;
  if (halves.length === 2) {
    const missing = 8 - head.length - rest.length;
    if (missing < 0) return null;
    groups = [...head, ...new Array(missing).fill('0'), ...rest];
  } else {
    groups = head;
  }
  if (groups.length !== 8) return null;
  const nums = groups.map((g) => parseInt(g, 16));
  return nums.every((n) => Number.isInteger(n) && n >= 0 && n <= 0xffff) ? nums : null;
}

function isBlockedIPv6(groups) {
  const [g0, g1, g2, g3, g4, g5, g6, g7] = groups;
  const allZeroPrefix = g0 === 0 && g1 === 0 && g2 === 0 && g3 === 0 && g4 === 0;
  if (allZeroPrefix && g5 === 0 && g6 === 0 && (g7 === 0 || g7 === 1)) return true; // :: and ::1
  // IPv4-mapped (::ffff:a.b.c.d) and deprecated IPv4-compatible (::a.b.c.d): judge by the IPv4 part.
  if (allZeroPrefix && (g5 === 0xffff || g5 === 0)) {
    return isBlockedIPv4([g6 >> 8, g6 & 0xff, g7 >> 8, g7 & 0xff]);
  }
  // 64:ff9b::/96 NAT64: judge by the embedded IPv4.
  if (g0 === 0x64 && g1 === 0xff9b && g2 === 0 && g3 === 0 && g4 === 0 && g5 === 0) {
    return isBlockedIPv4([g6 >> 8, g6 & 0xff, g7 >> 8, g7 & 0xff]);
  }
  if ((g0 & 0xfe00) === 0xfc00) return true; // fc00::/7 unique local
  if ((g0 & 0xffc0) === 0xfe80) return true; // fe80::/10 link-local
  if ((g0 & 0xff00) === 0xff00) return true; // ff00::/8 multicast
  return false;
}

/** True when `ip` is an IP literal in a loopback / private / link-local / reserved range. */
export function isBlockedAddress(ip) {
  if (typeof ip !== 'string') return true; // fail closed on garbage
  const v4 = parseIPv4(ip);
  if (v4) return isBlockedIPv4(v4);
  const v6 = expandIPv6(ip);
  if (v6) return isBlockedIPv6(v6);
  return true;
}

function blockedError(host) {
  const err = new Error(`piece-host: connection to non-public address refused (${host})`);
  err.code = 'EPIECEHOST_EGRESS_BLOCKED';
  return err;
}

/** dns.lookup replacement that rejects non-public results. Signature matches net's `lookup` option. */
export function makeGuardedLookup(baseLookup = dns.lookup) {
  return function guardedLookup(hostname, options, callback) {
    let opts = options;
    let cb = callback;
    if (typeof opts === 'function') {
      cb = opts;
      opts = {};
    }
    baseLookup(hostname, opts, (err, address, family) => {
      if (err) {
        cb(err);
        return;
      }
      const addrs = Array.isArray(address) ? address.map((a) => a.address) : [address];
      const bad = addrs.find((a) => isBlockedAddress(a));
      if (bad !== undefined) {
        cb(blockedError(bad));
        return;
      }
      cb(null, address, family);
    });
  };
}

function wrapCreateConnection(AgentClass) {
  const original = AgentClass.prototype.createConnection;
  if (typeof original !== 'function' || original.__pieceHostGuarded) return;
  const guarded = function createConnection(options, cb) {
    if (options && typeof options === 'object') {
      const host = options.host !== undefined ? options.host : options.hostname;
      if (typeof host === 'string' && net.isIP(host) && isBlockedAddress(host)) {
        // Hand back an already-failed socket so the HTTP client reports a normal request error.
        const socket = new net.Socket();
        const err = blockedError(host);
        process.nextTick(() => socket.destroy(err));
        return socket;
      }
      return original.call(this, { ...options, lookup: makeGuardedLookup(options.lookup || dns.lookup) }, cb);
    }
    return original.call(this, options, cb);
  };
  guarded.__pieceHostGuarded = true;
  AgentClass.prototype.createConnection = guarded;
}

async function checkFetchTarget(input) {
  let url;
  try {
    url = new URL(typeof input === 'string' || input instanceof URL ? input : input.url);
  } catch {
    return; // let fetch itself report the malformed URL
  }
  const host = url.hostname.startsWith('[') ? url.hostname.slice(1, -1) : url.hostname;
  if (net.isIP(host)) {
    if (isBlockedAddress(host)) throw blockedError(host);
    return;
  }
  const results = await dns.promises.lookup(host, { all: true });
  const bad = results.find((r) => isBlockedAddress(r.address));
  if (bad) throw blockedError(bad.address);
}

/**
 * Installs the guard process-wide. Idempotent. Call once at startup, before
 * any piece code runs. Returns a function that reports whether it is active.
 */
export function installEgressGuard({ allowPrivate = false } = {}) {
  if (allowPrivate) return false;
  wrapCreateConnection(http.Agent);
  wrapCreateConnection(https.Agent);
  const originalFetch = globalThis.fetch;
  if (typeof originalFetch === 'function' && !originalFetch.__pieceHostGuarded) {
    const guardedFetch = async function fetch(input, init) {
      await checkFetchTarget(input);
      return originalFetch.call(globalThis, input, init);
    };
    guardedFetch.__pieceHostGuarded = true;
    globalThis.fetch = guardedFetch;
  }
  return true;
}
