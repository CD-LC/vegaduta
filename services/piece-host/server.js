#!/usr/bin/env node
// Entry point: `node /opt/piece-host/server.js --pieces slack` (see README.md).
//
// stdout is the MCP protocol channel. Pieces (and their dependencies) may call
// console.log, which would corrupt the stream, so every console method is
// routed to stderr before any piece code is loaded.

import { main } from './src/main.js';

const printable = (p) => {
  if (typeof p === 'string') return p;
  try {
    return JSON.stringify(p);
  } catch {
    return String(p); // circular structures etc.
  }
};
const toStderr = (...parts) => {
  process.stderr.write(`${parts.map(printable).join(' ')}\n`);
};
console.log = toStderr;
console.info = toStderr;
console.debug = toStderr;

// Unreviewed third-party piece code runs in this process. A piece that leaves a floating rejected promise or
// throws from a timer after its call returned would, under Node's default policy, kill the whole stdio server
// and drop every in-flight call. Log the error NAME and a truncated message only (never call arguments) and
// keep serving; a genuinely fatal condition (OOM, stdin gone) still ends the process through the paths below.
const describeError = (e) => {
  const name = e && typeof e.name === 'string' ? e.name : typeof e;
  const message = String((e && e.message) || e).replace(/\s+/g, ' ').slice(0, 200);
  return `${name}: ${message}`;
};
process.on('unhandledRejection', (reason) => {
  process.stderr.write(`piece-host: unhandled promise rejection (ignored): ${describeError(reason)}\n`);
});
process.on('uncaughtException', (err) => {
  process.stderr.write(`piece-host: uncaught exception (ignored): ${describeError(err)}\n`);
});

main(process.argv.slice(2), process.env).then(
  (code) => {
    // Pieces can leave sockets/timers open; exit explicitly once stdout is flushed.
    process.stdout.write('', () => process.exit(code));
  },
  (err) => {
    process.stderr.write(`piece-host: fatal: ${String((err && err.message) || err).slice(0, 300)}\n`);
    process.exit(1);
  },
);
