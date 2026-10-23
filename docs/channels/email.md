# Email (IMAP)

Let an agent read and answer a **dedicated support mailbox**. Core polls the mailbox over IMAP every five minutes
and sends replies through the platform's own outbound mail relay.

> **Use a mailbox that only the agent reads.** Core opens it read-write and marks every unread message as read
> after one attempt, whether or not the agent managed to handle it; a message that failed is not retried. A
> person sharing the mailbox would lose their unread markers.

## How it fits together

* **Inbound is polled.** There is no webhook: every five minutes (30 seconds after start-up) core connects to the
  tenant's IMAP server, reads the `INBOX` messages that are unread, handles each and flags it read. One replica
  polls at a time.
* **Outbound is not per mailbox.** Replies go through core's SMTP relay (`spring.mail.host`, `spring.mail.port`,
  `spring.mail.username`, `spring.mail.password`; the sender is `app.mail.from`, falling back to the SMTP user),
  through the approval gate as `channel.email.send`. With no SMTP host configured, sending fails with a
  configuration error and the mailbox is receive-only.

## Set it up

1. Find your provider's IMAP host and port (Gmail `imap.gmail.com` 993, Outlook `outlook.office365.com` 993).
2. If two-factor authentication is on, create an **app-specific password**; the normal login password will not do.
3. In the console open **Channels > Email - IMAP** and enter the host, port, user name and password (SSL is on by
   default). Core opens the mailbox and reads the inbox folder once before saving; the password is stored
   encrypted.
4. Choose the agent that answers.

**A mail server on a private address** (a LAN server, a local bridge) is refused by default. An operator must list
it once in `app.security.outbound.allowed-private-hosts` (`host` or `host:port`); see
[configuration](../configuration.md). The host and port are checked before any connection and again on every poll,
the connection goes only to the addresses that passed, and a failed connection returns a fixed message (the detail
is in the server log).

## What the agent does with a message

* The sender's address is the conversation. A first-time sender gets one reply with a **pairing code** and waits
  under **Channels > Pairing** for an admin to approve them.
* Bounces and auto-replies (`Auto-Submitted`, out-of-office, `MAILER-DAEMON`) are never given to the agent.
* A message with no plain-text part is skipped. The first `text/plain` part is what the agent sees; attachments
  are not read.
* A message that is read twice (a connection drop before it was flagged) is processed once, de-duplicated on its
  `Message-ID`.

## Security and trust

* **The sender is the `From` header, which the platform does not authenticate.** It does not check SPF, DKIM or
  DMARC. Pairing therefore proves nothing about who really sent a message; it relies on your mail provider
  rejecting spoofed mail. Use a dedicated mailbox at a provider that enforces DMARC, and do not approve email
  contacts for agents with high-impact tools unless those tools sit behind the approval gate.
* Replies leave through the approval gate, so the first send of this action type waits for approval until you
  auto-approve it.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| "Could not connect to this IMAP mailbox" | Wrong host, port, SSL setting or password (an app password is needed with 2FA); the cause is in the server log. |
| "refused by the outbound request policy" | The host is on a private address (or resolves to one); ask the operator to allow-list it. |
| Mail is read but nothing is answered | The sender is waiting for approval, no default agent is chosen, or no SMTP relay is configured. |
| A message was marked read but never answered | It failed once; failures are not retried. Check the log, then resend. |
