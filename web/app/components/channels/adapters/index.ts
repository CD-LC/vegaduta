import type { ChannelAdapter } from "./types";
import { adapter as discord } from "./discord";
import { adapter as email } from "./email";
import { adapter as matrix } from "./matrix";
import { adapter as imessage } from "./imessage";
import { adapter as metaSocial } from "./metaSocial";
import { adapter as mobileSdk } from "./mobile-sdk";
import { adapter as signal } from "./signal";
import { adapter as slack } from "./slack";
import { adapter as sms } from "./sms";
import { adapter as teams } from "./teams";
import { adapter as telegram } from "./telegram";
import { adapter as voice } from "./voice";
import { adapter as whatsapp } from "./whatsapp";
import { adapter as whatsappqr } from "./whatsappqr";
import { adapter as x } from "./x";

/** Add a channel: create adapters/<channel>.tsx exporting `adapter`, then list it here. */
export const CHANNEL_ADAPTERS: ChannelAdapter[] = [
  whatsapp,
  whatsappqr,
  telegram,
  matrix,
  email,
  slack,
  discord,
  teams,
  imessage,
  metaSocial,
  x,
  voice,
  sms,
  signal,
  mobileSdk,
];

const BY_KEY: Record<string, ChannelAdapter> = Object.fromEntries(
  CHANNEL_ADAPTERS.flatMap((a) => a.keys.map((k) => [k, a] as const))
);

export function adapterFor(key: string): ChannelAdapter | undefined {
  return BY_KEY[key];
}

export type { ChannelAdapter, ChannelCtx } from "./types";
