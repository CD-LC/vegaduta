// Server-only structured logger (pino). Do NOT import this from a
// "use client" component. proxy.js (formerly middleware.js, migrated to
// Next.js 16's proxy.js convention) always runs on the Node.js runtime -
// not configurably, unlike the old Edge-runtime middleware.js - so pino's
// Node.js APIs (fs, worker_threads for transports) are actually safe to
// import there now. It still doesn't import this logger today;
// middleware/proxy logging was deliberately left out rather than guessed at.
//
// Mirrors the backend's structured-logging convention (see
// core/app/src/main/resources/application.yml's
// logging.structured.format.console: ecs): one JSON object per log
// line, so Promtail (deploy/promtail/promtail-config.yaml) picks these
// up the same way it already tails every other container's stdout —
// no extra shipping config needed here.
import pino from "pino";

const isProd = process.env.NODE_ENV === "production";

export const logger = pino({
  level: process.env.LOG_LEVEL || (isProd ? "info" : "debug"),
  base: { service: "web" },
  timestamp: pino.stdTimeFunctions.isoTime,
});

export default logger;
