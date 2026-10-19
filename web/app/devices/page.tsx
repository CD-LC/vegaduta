import { redirect } from "next/navigation";

/**
 * Mobile SDK (#157) used to render its own standalone page here
 * (DevicesPanel.js). IA merge (full-site redesign pass): the Channels
 * screen's Mobile SDK tab is the same surface - pairing-code generation and
 * the live-polled paired-device list were already there, and revoke +
 * command history moved over with this merge - so this route now just
 * forwards there with that tab pre-selected (ChannelsPanel reads the
 * ?channel= param). One place to manage device pairing instead of two
 * near-duplicate screens. Kept as a redirect rather than deleted so any
 * existing bookmark or deep link to /devices keeps working. A server
 * redirect, like /jev, so there is no blank client render before it fires.
 */
export default function DevicesPage(): never {
  redirect("/admin/channels?channel=mobile-sdk");
}
