"use client";

import { SmsInspector } from "../SmsInspector";
import type { ChannelAdapter, ChannelCtx } from "./types";

function SmsChannelInspector({ ctx }: { ctx: ChannelCtx }) {
  const { agents, cardsByKey, statusLoaded, refreshStatus, selectChannel } = ctx;
  return (
    <SmsInspector
      agents={agents}
      entry={cardsByKey.get("sms")}
      loaded={statusLoaded}
      refreshStatus={refreshStatus}
      onConnectTwilio={() => selectChannel("whatsapp")}
    />
  );
}

export const adapter: ChannelAdapter = { keys: ["sms"], Inspector: SmsChannelInspector };
