// agentkit — the native bridge: NOT IMPLEMENTED, and this file says so.
//
// A terminal needs a native process. This repo has native hosts — the VS Code
// extension and the JetBrains and Eclipse plugins under clients/ — and two of
// them already run real commands for their OWN in-editor agent
// (clients/vscode/src/agent/workspaceTools.ts runCommand;
// clients/jetbrains/…/agent/ProjectToolExecutor.kt). What none of them has is a
// way for a browser tab to reach that capability. Checked 2026-09-19:
//   - no HTTP or WebSocket server is started by any client for tools;
//   - the only 127.0.0.1 listeners are MODEL runtimes (the bundled
//     llama-server, Ollama, LM Studio) — OpenAI-compatible text completion,
//     nothing that executes a command;
//   - clients/shared/src/webview/protocol.ts is the host<->webview contract for
//     the plugin's OWN chat panel (postMessage / cefQuery / in-page loopback).
//     It is not reachable from a page on another origin, and it has no
//     tool-execution message;
//   - the "18 offline tools" of clients 0.4.0 are in-panel text utilities
//     (base64, regex tester, timestamps…), not a tool protocol.
//
// So there is nothing to connect to, and this module does NOT ship a class that
// looks like a working bridge. It ships the two things that are true today: the
// sentence the browser host reports, and a probe that answers "no".
//
// WHAT A REAL BRIDGE WOULD NEED (so the next person does not start from zero,
// and does not start from the dangerous version): a loopback listener inside a
// client, bound to 127.0.0.1 only; an Origin allow-list of the platform's own
// app hostnames; a per-session pairing secret the person copies from the IDE
// into the page (a localhost port with no pairing is reachable by EVERY web
// page the person visits, which would turn "run a command" into a drive-by);
// the IDE's own approval prompt in front of every command, in addition to this
// page's; and Chrome's Private Network Access preflight handled. The HostTerminal
// interface in types.ts is the shape it would plug into — the loop, the deny
// list (commands.ts) and the approval queue already handle a host that has one.

import type { HostTerminal } from "./types";

/** Shown to the person AND fed to the model when run_command is attempted in
 *  the browser. States the limit and the real alternative; promises nothing. */
export const TERMINAL_UNAVAILABLE_IN_BROWSER =
  "Terminal unavailable: a browser tab cannot run commands. To run commands with an agent, open this project in the VegaDūta extension for VS Code or the JetBrains plugin, which run them on your machine with your approval.";

export interface NativeBridgeProbe {
  available: false;
  reason: string;
}

/** Always "not available" — there is no bridge to find (see the header). Exists
 *  so the UI asks ONE place, and the answer changes in one place the day a
 *  bridge is real. Never throws, touches no network. */
export function probeNativeBridge(): NativeBridgeProbe {
  return { available: false, reason: TERMINAL_UNAVAILABLE_IN_BROWSER };
}

/** Deliberately returns undefined rather than a HostTerminal whose run() fails:
 *  the loop keys "is there a terminal?" off the PRESENCE of this capability,
 *  and only offers the model run_command when it is present. */
export function nativeBridgeTerminal(): HostTerminal | undefined {
  return undefined;
}
