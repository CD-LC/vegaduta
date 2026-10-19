# iMessage (through BlueBubbles)

Let people message a VegaDūta agent in iMessage. Be clear about the price of
this before you start: **Apple has no API for iMessage.** The only workable open
path is a Mac that stays powered on, awake and signed in to Messages, running
the free open-source [BlueBubbles](https://bluebubbles.app) server. VegaDūta
talks to that server over its REST API. If the Mac sleeps, loses its network or
signs out, iMessage stops until it is back, and Apple can change or block this
at any time. Nothing here works from Linux, Windows or the cloud alone.

## Set it up

1. **On the Mac**: install BlueBubbles, sign in to Messages, and set a *Server
   Password* in BlueBubbles. Turn off sleep for the Mac (or use a Mac mini).
2. **Give the server an address VegaDūta can reach**: a LAN address
   (`http://192.168.1.20:1234`), a Tailscale name, or a tunnel.
3. **Allow the private address.** VegaDūta refuses to call private network
   addresses on a tenant's say-so (SSRF protection). The operator lists the
   host, once, in `app.security.outbound.allowed-private-hosts` (for example
   `192.168.1.20:1234`). Without it, connecting fails with a message that names
   this setting. A public hostname needs no entry.
4. **Connect** in Channels → iMessage: enter the address and the password.
   VegaDūta calls the server's info endpoint first and stores nothing unless
   that works. The password is encrypted at rest (`APP_ENCRYPTION_KEY`, like
   every channel credential) and never returned or logged.
5. **Receive messages**, one of two ways:
   - **Webhook** (default). In BlueBubbles → API & Webhooks, add the URL the
     inspector shows (`/webhook/imessage/<id>?token=<secret>`) and tick *New
     Messages*. The address must be reachable from the Mac. Calls without the
     secret are rejected with 403 and a constant-time comparison.
   - **Polling** (for a platform the Mac cannot reach). Choose *Polling* and the
     operator sets `app.imessage.polling.enabled=true`. VegaDūta asks BlueBubbles
     for new messages every `app.imessage.polling.interval-ms` (5 s). It starts
     from "now": history is never replayed into an agent. Run it in one process.
6. **Choose who may talk to the agent** (below) and pick the agent that answers.

## Who may message the agent (default deny)

| Setting | Default | Meaning |
|---|---|---|
| Allowed senders | empty | Phone numbers (`+15550100100`) or Apple ID emails. Listed people are approved on their first message. |
| Everyone else | pairing | They get a pairing code and wait in Channels → Pairing for an admin. *Ignore silently* drops them with no reply and nothing stored. |
| Group chats | off | When on, only allowed senders are answered in a group. A stranger in a group is ignored and never sent a pairing code. |

Numbers are compared in a normalised form, so `+1 (555) 010-0100` and
`+15550100100` are the same person. The agent never sees the Mac's own messages
(they would loop), reactions, group renames or non-message events.

## Limits

- **Rate limits** (per replica, in memory): `app.imessage.rate-limit.inbound-per-minute`
  (default 20, per chat; excess is dropped) and `outbound-per-minute` (default 30,
  per tenant; excess is retried by the approval queue). `0` disables a window.
  Messages sent through a Mac's consumer account can be throttled or locked by
  Apple if they burst, so keep these low.
- Replies go through the approval gate as `channel.imessage.send`
  like every other channel; the first reply on a fresh
  tenant waits for approval until the action type is auto-approved.
- Outbound text and attachments (images, files, up to the server's limit) work.
  Inbound attachments are not read yet; a message with no text is ignored.
- No typing indicator, no read receipts, no tapback replies.

## Honest status

Built against BlueBubbles' documented REST API and tested against a fake
server; **not yet run against a real Mac**. Treat it as experimental until you
have exercised it on yours.
