export default {
  slug: "b10-credentials-secrets",
  title: "VegaDūta Academy — Credentials & secrets",
  subtitle: "The key cabinet, kept locked",
  next: "b11 — Edge",
  voice: "en-US-AndrewNeural",
  scenes: [
    {
      say: "Credentials is where MCP servers and tool connections keep their keys — each entry is a named credential an agent can use without ever seeing the secret itself.",
      do: [["goto", "/tools/credentials"], ["wait", 2500]],
    },
    {
      say: "The distinction that matters: an agent gets told 'use the wire-service credential' — it never reads the key. The value is write-once: stored encrypted, shown masked, rotated in place.",
      do: [["goto", "/tools/credentials"], ["wait", 1500],
           ["tryHighlight", "text=/credential|masked|••/i", 2000], ["scroll", 300]],
    },
    {
      say: "Secrets is the workspace-level vault — the same pattern for anything else that needs a key: publish endpoints, webhook tokens, partner APIs. Named, scoped, never echoed back.",
      do: [["goto", "/secrets"], ["wait", 2500]],
    },
    {
      say: "One rule covers the whole desk: secrets are referenced by name, used by machinery, and revealed to nobody — not to the model, not to the video you're watching. Next: on-device AI at the edge.",
      do: [["goto", "/secrets"], ["wait", 2500]],
    },
  ],
};
