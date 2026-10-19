# Signal channel

Talk to an agent over Signal, from your own number, on your own machine or infrastructure. VegaDūta
does not run Signal for you: you run [signal-cli-rest-api](https://github.com/bbernhard/signal-cli-rest-api)
(a REST and websocket wrapper around signal-cli), register or link a number with it, and connect
the console to it. Nothing about your messages passes through a third-party bot platform.

## How it fits together

```
Signal  <->  signal-cli-rest-api (yours)  <->  VegaDūta core  ->  agent
                  state: signal-cli-config volume       (pulls messages, sends replies)
```

* **Inbound is pulled.** Signal has no push to a public URL, so core keeps a receiver per
  connected number. It follows signal-cli's mode, read from `GET /v1/about`: long polling
  `GET /v1/receive/{number}` for `normal` and `native`, the receive websocket for `json-rpc`.
  A dropped connection backs off (1 s up to 60 s) and reconnects by itself.
* **Outbound goes through the approval gate** like every other channel: a reply is an
  `channel.signal.send` action, so approval policies, the audit log and the send quota apply.
  Long replies are split at paragraph boundaries (`app.signal.max-message-chars`).
* **Secrets.** Signal has no platform key. The only optional secret is a bearer token for a
  reverse proxy in front of signal-cli; it is stored encrypted, sent only over https (or to a host
  you allow-listed), and never returned by the API or written to a log. The base URL may not carry
  a user name or password, and the API endpoints need the channel-management permission.

## Set it up

1. Run signal-cli-rest-api. With Docker Compose next to the Lite stack:

   ```
   docker compose -f deploy/compose/lite.yml -f deploy/compose/signal.yml \
     --env-file deploy/compose/.env --profile signal up -d
   ```

   Link an existing phone (QR code) or register a number you control; the steps are at the top of
   `deploy/compose/signal.yml`. The container publishes no port: the API has no authentication.
2. Let core reach it. Private addresses are refused by default, so list the host (`host:port`):

   ```
   APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS=signal-cli:8080
   ```

   This is the same mechanism a self-hosted Ollama or MCP server uses. A signal-cli on a public
   `https://` URL needs no entry.
3. In the console open **Channels > Signal**, enter the base URL (`http://signal-cli:8080`) and the
   registered number (`+4915112345678`). Connecting checks that the server answers and has an
   account for that number.
4. Choose the agent that answers (**Route inbound to**).

## Who the agent answers (default deny)

The agent never answers someone you have not approved.

* **A first-time sender** gets one reply with a six-digit pairing code. The conversation appears
  under pending pairings (Channels, or the inbox); you approve or reject it there, exactly as for
  Telegram. Until then further messages are ignored. Add the number as a contact, or switch the
  channel to auto-approve in the routing settings, to skip the step.
* **Unknown senders = IGNORE** (setting): strangers get no reply and nothing is stored, unless they
  are already a session or a contact. Use it when the number is public.
* **Groups** are ignored by default. Set **Groups** to `MENTION` (answer only when the agent is
  mentioned or one of its messages is quoted) or `ALL`. A group is its own conversation that you
  approve once; everyone in an approved group can then talk to the agent.
* A person is keyed by their number, or by their Signal UUID when they hide their number, so the
  same person keeps one memory. If someone later hides their number they appear as a new sender.
* Receipts, typing notices, reactions, deletions, stories and your own other devices' messages are
  dropped. An attachment is described to the agent (`[attachment: image/png name]`), not
  downloaded.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `app.signal.receive.enabled` | `true` | Run the receiver. It is idle until a Signal connection exists. `false` removes it from this process. |
| `app.signal.receive.mode` | `auto` | `auto` reads `/v1/about`; `poll` or `websocket` forces one. |
| `app.signal.receive.poll-seconds` | `15` | Long-poll wait per request in poll mode (1 to 25). |
| `app.signal.max-message-chars` | `3500` | A reply longer than this is split into several messages. |
| `app.signal.max-attachment-bytes` | `20971520` | Largest file the agent may send. |
| `app.security.outbound.allowed-private-hosts` | empty | Must list a private signal-cli (`host:port`). |

Run the receiver in ONE core process per number: signal-cli hands each message out once, so two
receivers would split a number's messages between them. The `jobs` and `worker` deployables have
the receiver switched off already; with several API replicas, set
`APP_SIGNAL_RECEIVE_ENABLED=false` on all but one.

## API

| Method and path | Purpose |
|---|---|
| `POST /api/channels/signal/credential` | Connect: `baseUrl`, `number`, optional `authToken`, `unknownSenders` (`PAIR`/`IGNORE`), `groupMode` (`IGNORE`/`MENTION`/`ALL`). |
| `GET /api/channels/signal/credential` | Current connection (never the token), 204 if none. |
| `PUT /api/channels/signal/credential/policy` | Change `unknownSenders` / `groupMode` without reconnecting. |
| `DELETE /api/channels/signal/credential` | Disconnect (leaves the signal-cli registration alone). |
| `POST /api/channels/signal/send` | Send through the approval gate; `to` is a number, a UUID or `group:<id>`. |
| `PUT /api/channels/signal/default-agent` | Which agent answers new conversations. |

## Limits and notes

* No workflow trigger binding for Signal yet (Telegram, WhatsApp and SMS have one).
* Messages that arrive while core is down wait in signal-cli and are delivered when the receiver
  reconnects.
* A turn runs on the receiver's thread, so a slow model delays other Signal chats of that number.
* The websocket path validates the URL just before it connects; unlike the REST calls it cannot pin
  the resolved address, so a DNS answer that changes between check and connect is not caught.
  Listing the host in `allowed-private-hosts` (the usual case) avoids the question.
* Not covered by a test against a real signal-cli: the tests use a fake server that speaks the
  documented endpoints.
