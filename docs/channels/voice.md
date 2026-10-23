# Voice (phone calls)

Callers dial a phone number and talk to an agent, which answers aloud. This page is about **phone calls over Twilio**
(Twilio Conversation Relay); in-app voice calling from the console runs on a separate stack (LiveKit, in the
browser) and needs no phone number.

## How it fits together

```
Caller  ->  Twilio number  --POST /webhook/twilio/voice-->  core   (checks X-Twilio-Signature, mints a ticket)
Twilio  <--TwiML <ConversationRelay url="wss://<core>/webhook/twilio/voice-relay">--  core
Twilio  <==WebSocket (speech to text, text to speech by Twilio)==>  core  ->  agent
```

* **There is no separate voice credential.** Voice reuses the Twilio connection from **Channels > WhatsApp > Twilio**
  (Account SID and Auth Token), and the number you point at core.
* **A reply is spoken live**, so it does **not** go through the human approval gate (a call cannot pause for an
  approval). What protects a call is the pairing gate below, and the agent's own tool and autonomy guardrails still
  apply to what it can do mid-call. There is no outbound "send" for voice.
* Spoken text is cleaned of markdown, emoji and raw URLs before Twilio reads it out.

## Set it up

1. Connect Twilio (see [WhatsApp](whatsapp.md#twilio-whatsapp)); voice, WhatsApp and SMS share it.
2. In Twilio set the number's **"A call comes in"** webhook to `https://<your-core-host>/webhook/twilio/voice`
   (HTTP POST). Set `OMNICHANNEL_PUBLIC_BASE_URL` so core can verify Twilio's signature and build the `wss://` URL
   Twilio is told to connect to.
3. Choose the agent that answers voice calls (the tenant's default agent for the channel), and optionally an agent
   **per dialled number** and a keypad (DTMF) menu, in **Channels > Voice**.
4. Call the number.

## Who answers, and what the caller can do

* **Pairing.** A caller is identified by their phone number. An unknown caller hears that an admin must approve them,
  is given a pairing code, and the call ends; after approval the next call connects. The same number keeps one
  memory from call to call.
* **Routing precedence.** A keypad digit chosen mid-call wins over the per-number agent, which wins over the tenant's
  default agent. These are per-call choices and never change the number's default.
* **A workflow bound to calls.** If a workflow is bound to the CALL trigger, the caller is asked to speak instead of
  being handed to the agent, and the workflow runs when they finish.

## Security

* **The inbound-call webhook** must carry a valid `X-Twilio-Signature` for the tenant's Auth Token.
* **The WebSocket is permitted without a login** (`/webhook/**`), so it is authenticated by a **single-use admission
  ticket** minted by the signature-checked call webhook: an encrypted, tamper-evident value that binds the Twilio
  account, call id and caller and expires after 5 minutes. The socket takes the tenant, call and caller from the
  ticket, ignores what the frame claims, and a ticket that was already presented is refused.
* **Origins.** Twilio's servers send no `Origin`; a browser page does, and is refused unless
  `app.voice.relay.allowed-origins` lists it.
* Twilio's voice webhooks are deliberately not de-duplicated: a Twilio retry means the call is still ringing and must
  be answered.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| The call connects and is dropped at once | The ticket expired or was already used, or `OMNICHANNEL_PUBLIC_BASE_URL` is not core's public HTTPS address so Twilio cannot reach the `wss://` URL. |
| The caller is told to ask for approval | The number is not paired yet; approve it under **Channels > Pairing**. |
| Every call is rejected | The signature check fails: wrong Auth Token or a mismatched `OMNICHANNEL_PUBLIC_BASE_URL`. |
