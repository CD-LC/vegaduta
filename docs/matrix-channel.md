# Matrix channel

Talk to an agent from any Matrix homeserver you choose: your own Synapse or Dendrite on a laptop or
home server, or a public one. The agent is a bot user; people message it in a direct chat or mention
it in a room.

There is no webhook and no public URL to set up. The platform connects out to the homeserver and
pulls messages with the Client-Server API `/sync` long poll, so it works behind NAT.

## Connect

1. On your homeserver create a normal user for the bot. Use a separate account, not your own.
2. Log in as that user in Element and copy its access token (Settings > Help & About > Access
   Token). Treat it like a password.
3. In the console open **Channels > Matrix**, enter the homeserver URL and the token, and connect.
   The platform proves the token with `whoami` before storing anything, and stores it encrypted.
4. Pick the agent that answers (same "route inbound to" card as every channel).
5. Start the receive loop (see below), message the bot, and approve the chat in **Pairing**.

## Receiving: `app.matrix.sync.enabled`

The sync loop runs only when `app.matrix.sync.enabled=true` (default `false`). Run it in exactly one
process per bot, like Telegram long polling. A single-process profile (lite, personal) can turn it on
with this one property. The console tells you
when a connected bot has no loop in this process. A connected bot can always send; it just receives
nothing while the loop is off.

The loop keeps its position (`next_batch`) in the database, so a restart resumes where it stopped.
The first sync of a new connection only records the position and skips history. Failures back off
exponentially up to 60 seconds and honour the homeserver's `retry_after_ms`. A rejected token stops the
loop and marks the connection as needing attention in the console.

## Who can talk to the bot (default-closed)

Every sender goes through the same pairing flow as other channels: the first message gets a pairing
code, and nothing reaches the agent until an admin approves the chat. Two settings tighten that:

| Setting | Values | Default |
|---|---|---|
| Who can start a conversation | `PAIRING`: anyone, after admin approval. `ALLOWLIST`: only allow-listed senders; everyone else is ignored without a reply, a session or a log row. | `PAIRING` |
| Join rooms when invited | `OFF`: never. `ALLOWLIST`: join when the inviter is on the allow-list. | `OFF` |

The allow-list takes one Matrix ID per line (`@alice:example.org`) or a whole server
(`@*:example.org`). The allow-list never approves a chat by itself.

Sessions are per room: a direct chat and a group room are each one conversation, approved once. In a
room with more than two members the bot answers only when it is mentioned, so it does not answer every
line of a human conversation. The bot ignores its own messages, `m.notice` events (other bots) and
message edits.

## Messages

- **Text and emotes** go to the agent. Replies are plain text, split across several events when long.
- **Replies and threads:** a message in a thread is answered in that thread. In a group room the
  answer quotes the message it replies to.
- **Files:** an inbound file reaches the agent as a description (name, type, size). A small text file
  (`text/plain`, `text/markdown`, `text/csv`, `application/json`, at most 64 KB) is downloaded and
  included as untrusted data. Other files are never downloaded. Agents can send files into a room
  through the usual forward-attachment tool (upload to the media repository, then an `m.file`,
  `m.image`, `m.audio` or `m.video` event).
- Every reply goes through the approval gate like every other channel.

## Not supported: end-to-end encryption

This channel does not implement Matrix end-to-end encryption (Olm/Megolm). The bot cannot read
messages in an encrypted room and does not try. When it sees an encrypted room (an `m.room.encrypted`
event or the `m.room.encryption` state event) from a sender the policy admits, it sends one notice per
room explaining that, then stays quiet. Use an unencrypted direct chat or room with the bot. Because
the bot's messages are sent unencrypted, do not put it in a room where you expect encryption.

## Security

- The homeserver URL is chosen by the operator, so every request goes through the platform's SSRF
  guard: private and internal addresses are refused, DNS is pinned to the checked addresses, redirects
  are off. A homeserver on your own network must be listed in
  `app.security.outbound.allowed-private-hosts` (`host:port`), and only such a host may use plain
  `http`. A public homeserver must be `https`. The egress allow-list
  (`app.security.outbound.egress-allow-list`) applies too.
- The access token is encrypted at rest, sent only in the `Authorization` header, and never written
  to a URL, a log line or an error message.
- Console endpoints under `/api/channels/matrix/**` need `PERM_MANAGE_CHANNELS`.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `app.matrix.sync.enabled` | `false` | Run the `/sync` receive loop in this process. |
| `app.security.outbound.allowed-private-hosts` | empty | Operator list of private homeservers (`host:port`). |

## API

`POST/GET/DELETE /api/channels/matrix/credential`, `PUT /api/channels/matrix/policy`,
`POST /api/channels/matrix/send` (approval-gated test send, `to` is a room id like `!abc:example.org`),
`PUT /api/channels/matrix/default-agent`.
