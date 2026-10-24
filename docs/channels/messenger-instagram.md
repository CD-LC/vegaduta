# Messenger and Instagram

Answer direct messages sent to a **Facebook Page** (Messenger) and to its linked **Instagram professional account**,
through one connection and one webhook.

## How it fits together

* Meta's Messenger Platform sends both surfaces to `https://<your-core-host>/webhook/meta/social`; the payload's
  `object` field says whether a message is for the Page (`page`) or Instagram (`instagram`). The platform finds
  your tenant from the recipient id in the payload: the **Page ID** for Messenger, the **Instagram Business Account
  ID** for Instagram.
* Replies go back through Meta's Graph API with the Page access token, through the approval gate as
  `channel.messenger.send` or `channel.instagram.send`.
* Connecting is a paid-plan entitlement (`plan_limits.external_channels_allowed`), enforced when you connect.

## Set it up

1. In the same Meta app you use for WhatsApp (or a new Business app) at
   [developers.facebook.com](https://developers.facebook.com), add the **Messenger** product and select your Page.
2. Under **Messenger > API Setup** generate a **Page Access Token** and note the **Page ID**. Under **App Settings >
   Basic** copy the **App Secret**. If the Page has a linked Instagram professional account, also copy its
   **Instagram Business Account ID**; it is optional, and leaving it blank means Messenger only.
3. In the console open **Channels > Messenger** (or **Instagram DM**) and enter the Page ID, Page access token, App
   Secret and, optionally, the Instagram Business Account ID. They are stored encrypted.
4. In Meta's webhook settings set the **Callback URL** to `https://<your-core-host>/webhook/meta/social` and the
   **Verify token** to `META_WEBHOOK_VERIFY_TOKEN` (the same platform-wide value WhatsApp uses). Subscribe to the
   Page's `messages` field (and the Instagram `messages` field if you added Instagram).
5. Choose the agent that answers, then message the Page.

## Who the agent answers, and limits

* A first-time sender gets a pairing code and waits under **Channels > Pairing**.
* Only plain **text** messages are answered; attachments, stickers and quick-reply payloads are skipped.
* Every `POST` must carry a valid `X-Hub-Signature-256` for the tenant's App Secret, checked before anything is
  processed; the verification `GET` needs the verify token and is refused if none is configured. A redelivered
  message is processed once.
* Meta's own policies apply (the messaging window, app review for production use); they are Meta's, not the
  platform's.
