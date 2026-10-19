"use client";

import { useCallback, useEffect, useRef, useState } from "react";

// /device — the browser device node. Pair this page (a pairing code from
// Channels -> Mobile SDK, or the ?code= deep link a code page can hand out)
// and the browser becomes a governed device: it declares only the
// capabilities a browser can honestly offer, then polls core for commands
// and executes the ones it supports.
//
//   GET_LOCATION    navigator.geolocation            -> {lat, lng, accuracy}
//   CAPTURE_PHOTO   getUserMedia video still         -> {mime, dataUrl}
//   SCREENSHOT      getDisplayMedia single frame     -> {mime, dataUrl}
//   NOTIFY          Notification (falls back to log) -> {shown}
//   VIBRATE         navigator.vibrate                -> {ms}
//   CLIPBOARD_WRITE navigator.clipboard.writeText    -> {}
//   CLIPBOARD_READ  navigator.clipboard.readText     -> {text}
//
// Every one of these is behind the platform's own permission prompt — the
// browser requires consent per capability (screen share every call), which
// is the whole point of running commands through a governed device node
// instead of an always-on agent.
//
// The page is a PUBLIC route (see lib/publicRoutes.ts): a paired device is
// not a signed-in user, it holds a device token in localStorage. The
// protocol is core/devices' gateway: POST /api/devices/{claim,poll,
// commands/{id}/result,capabilities} with the X-Device-Token header.

const STORE_KEY = "vegaduta.device";
const POLL_MS = 4000;

type Device = { deviceId: string; token: string; name: string };

type Command = { id: string; commandType: string; payload: string | null };

type LogLine = { at: number; text: string };

// Capability keys reported to the server (sorted CSV in mobile_device).
// Deliberately granular: "camera.photo" not "camera", so a tablet without
// one never over-declares.
function detectCapabilities(): string[] {
  const caps: string[] = [];
  if (typeof navigator !== "undefined") {
    if (navigator.geolocation) caps.push("location.get");
    if (navigator.mediaDevices?.getUserMedia) caps.push("camera.photo");
    if (navigator.mediaDevices?.getDisplayMedia) caps.push("screen.share");
    if (navigator.clipboard?.writeText) caps.push("clipboard.write");
    if (navigator.clipboard?.readText) caps.push("clipboard.read");
    if (navigator.vibrate) caps.push("vibrate");
  }
  if (typeof window !== "undefined" && "Notification" in window) caps.push("notify");
  return caps;
}

async function frameToDataUrl(stream: MediaStream): Promise<string> {
  const track = stream.getVideoTracks()[0];
  const video = document.createElement("video");
  video.srcObject = stream;
  await video.play();
  await new Promise((r) => setTimeout(r, 250)); // let a real frame arrive
  const canvas = document.createElement("canvas");
  canvas.width = video.videoWidth || 1280;
  canvas.height = video.videoHeight || 720;
  canvas.getContext("2d")!.drawImage(video, 0, 0);
  track.stop();
  return canvas.toDataURL("image/jpeg", 0.8);
}

async function runCommand(cmd: Command, notify: (text: string) => void): Promise<{ status: string; result: string }> {
  const payload = cmd.payload ? JSON.parse(cmd.payload) : {};
  switch (cmd.commandType) {
    case "GET_LOCATION": {
      const pos = await new Promise<GeolocationPosition>((ok, err) =>
        navigator.geolocation.getCurrentPosition(ok, err, { enableHighAccuracy: true, timeout: 15000 })
      );
      return {
        status: "COMPLETED",
        result: JSON.stringify({ lat: pos.coords.latitude, lng: pos.coords.longitude, accuracy: pos.coords.accuracy }),
      };
    }
    case "CAPTURE_PHOTO": {
      const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: payload.facing || "user" } });
      const dataUrl = await frameToDataUrl(stream);
      return { status: "COMPLETED", result: JSON.stringify({ mime: "image/jpeg", dataUrl }) };
    }
    case "SCREENSHOT": {
      const stream = await navigator.mediaDevices.getDisplayMedia({ video: true });
      const dataUrl = await frameToDataUrl(stream);
      return { status: "COMPLETED", result: JSON.stringify({ mime: "image/jpeg", dataUrl }) };
    }
    case "NOTIFY": {
      const title = String(payload.title || "VegaDūta");
      const body = String(payload.body || "");
      let shown = false;
      if ("Notification" in window) {
        if (Notification.permission === "default") await Notification.requestPermission();
        if (Notification.permission === "granted") {
          new Notification(title, { body });
          shown = true;
        }
      }
      if (!shown) notify(`${title}: ${body}`);
      return { status: "COMPLETED", result: JSON.stringify({ shown }) };
    }
    case "VIBRATE": {
      const ms = Number(payload.ms || 200);
      const ok = navigator.vibrate ? navigator.vibrate(ms) : false;
      return { status: ok ? "COMPLETED" : "FAILED", result: JSON.stringify({ ms, ok }) };
    }
    case "CLIPBOARD_WRITE": {
      await navigator.clipboard.writeText(String(payload.text || ""));
      return { status: "COMPLETED", result: "{}" };
    }
    case "CLIPBOARD_READ": {
      const text = await navigator.clipboard.readText();
      return { status: "COMPLETED", result: JSON.stringify({ text }) };
    }
    default:
      return { status: "FAILED", result: `unsupported command type on this device: ${cmd.commandType}` };
  }
}

export default function DevicePage() {
  const [device, setDevice] = useState<Device | null>(null);
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [log, setLog] = useState<LogLine[]>([]);
  const polling = useRef(false);

  const note = useCallback((text: string) => {
    setLog((l) => [{ at: Date.now(), text }, ...l].slice(0, 40));
  }, []);

  // Restore a previous pairing.
  useEffect(() => {
    try {
      const raw = localStorage.getItem(STORE_KEY);
      if (raw) setDevice(JSON.parse(raw));
    } catch {
      /* corrupt store: start unpaired */
    }
    const params = new URLSearchParams(window.location.search);
    const c = params.get("code");
    if (c) setCode(c);
    setName(`${navigator.platform || "Browser"} device`);
  }, []);

  const claim = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch("/api/devices/claim", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code: code.trim(), deviceName: name || "Web device", platform: "WEB" }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        throw new Error(body.error || `Pairing failed (${res.status})`);
      }
      const { deviceId, deviceToken } = await res.json();
      const d = { deviceId, token: deviceToken, name: name || "Web device" };
      localStorage.setItem(STORE_KEY, JSON.stringify(d));
      setDevice(d);
      // Declare what this browser can actually do, immediately.
      await fetch("/api/devices/capabilities", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Device-Token": deviceToken },
        body: JSON.stringify({ capabilities: detectCapabilities() }),
      });
      note("Paired. Polling for commands…");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Pairing failed");
    } finally {
      setBusy(false);
    }
  }, [code, name, note]);

  // Command poll loop.
  useEffect(() => {
    if (!device || polling.current) return undefined;
    polling.current = true;
    let alive = true;
    const tick = async () => {
      while (alive) {
        try {
          const res = await fetch("/api/devices/poll", {
            method: "POST",
            headers: { "X-Device-Token": device.token },
          });
          if (res.status === 401 || res.status === 403) {
            note("Device revoked — pair again.");
            localStorage.removeItem(STORE_KEY);
            setDevice(null);
            return;
          }
          if (res.ok) {
            const cmd: Command = await res.json();
            note(`Running ${cmd.commandType}…`);
            const outcome = await runCommand(cmd, note).catch((e) => ({
              status: "FAILED",
              result: e instanceof Error ? e.message : "command failed",
            }));
            await fetch(`/api/devices/commands/${cmd.id}/result`, {
              method: "POST",
              headers: { "Content-Type": "application/json", "X-Device-Token": device.token },
              body: JSON.stringify(outcome),
            });
            note(`${cmd.commandType}: ${outcome.status}`);
          }
        } catch (e) {
          note(e instanceof Error ? e.message : "poll error");
        }
        await new Promise((r) => setTimeout(r, POLL_MS));
      }
    };
    void tick();
    return () => {
      alive = false;
      polling.current = false;
    };
  }, [device, note]);

  const caps = detectCapabilities();

  return (
    <main style={{ maxWidth: 560, margin: "48px auto", padding: "0 16px", fontFamily: "system-ui" }}>
      <h1 style={{ fontSize: 22, fontWeight: 700 }}>VegaDūta device</h1>
      <p style={{ color: "#555", marginTop: 8 }}>
        Pair this browser as a device your assistant can ask to do things — read your location, take a
        photo, share a screen frame, write to the clipboard. Every capability asks for the browser&apos;s own
        permission first.
      </p>

      {!device ? (
        <div style={{ marginTop: 24, display: "grid", gap: 12 }}>
          <label style={{ display: "grid", gap: 4 }}>
            <span style={{ fontSize: 13, color: "#555" }}>Pairing code</span>
            <input
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="6-digit code from Channels → Mobile SDK"
              style={{ padding: 10, borderRadius: 8, border: "1px solid #ccc" }}
            />
          </label>
          <label style={{ display: "grid", gap: 4 }}>
            <span style={{ fontSize: 13, color: "#555" }}>Device name</span>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              style={{ padding: 10, borderRadius: 8, border: "1px solid #ccc" }}
            />
          </label>
          <button
            onClick={claim}
            disabled={busy || !code.trim()}
            style={{ padding: "10px 16px", borderRadius: 8, border: 0, background: "#0f1689", color: "#fff" }}
          >
            {busy ? "Pairing…" : "Pair this device"}
          </button>
          {error && <p style={{ color: "#b00020" }}>{error}</p>}
        </div>
      ) : (
        <div style={{ marginTop: 24 }}>
          <p>
            <strong>{device.name}</strong> — paired. Keep this page open (Add to Home Screen keeps it alive
            in the background on most phones).
          </p>
          <p style={{ fontSize: 13, color: "#555", marginTop: 8 }}>Capabilities: {caps.join(", ") || "none"}</p>
          <button
            onClick={() => {
              localStorage.removeItem(STORE_KEY);
              setDevice(null);
            }}
            style={{ marginTop: 8, fontSize: 13, color: "#b00020", background: "none", border: "none", padding: 0, textDecoration: "underline" }}
          >
            Unpair
          </button>
        </div>
      )}

      {log.length > 0 && (
        <div style={{ marginTop: 24, borderTop: "1px solid #eee", paddingTop: 12 }}>
          {log.map((l, i) => (
            <p key={i} style={{ fontSize: 12, color: "#666", margin: "4px 0" }}>
              {new Date(l.at).toLocaleTimeString()} — {l.text}
            </p>
          ))}
        </div>
      )}
    </main>
  );
}
