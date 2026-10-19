import type { SVGProps, ReactElement } from "react";
import { Mail, MessageCircle, MessagesSquare, PhoneCall, QrCode, Smartphone } from "lucide-react";

// Real brand marks for the Channels screen — inline SVG path data from Simple
// Icons (https://simpleicons.org, CC0 / public-domain icon paths), so each
// channel tile shows its actual logo (WhatsApp, Telegram, Instagram, Messenger,
// Slack, Discord, X) instead of a generic lucide glyph. Kept as a single
// currentColor path per brand: the tile sets the fill via `color`, so one mark
// works on both a filled brand-color tile and a monochrome context. Email,
// Voice and Mobile SDK have no external brand and reuse the app's lucide set.
type IconProps = SVGProps<SVGSVGElement>;

function base(props: IconProps) {
  return {
    viewBox: "0 0 24 24",
    fill: "currentColor",
    xmlns: "http://www.w3.org/2000/svg",
    "aria-hidden": true,
    ...props,
  } as IconProps;
}

export function WhatsappIcon(props: IconProps) {
  return (
    <svg {...base(props)}>
      <path d="M17.472 14.382c-.297-.149-1.758-.867-2.03-.967-.273-.099-.471-.148-.67.15-.197.297-.767.966-.94 1.164-.173.199-.347.223-.644.075-.297-.15-1.255-.463-2.39-1.475-.883-.788-1.48-1.761-1.653-2.059-.173-.297-.018-.458.13-.606.134-.133.298-.347.446-.52.149-.174.198-.298.298-.497.099-.198.05-.371-.025-.52-.075-.149-.669-1.612-.916-2.207-.242-.579-.487-.5-.669-.51-.173-.008-.371-.01-.57-.01-.198 0-.52.074-.792.372-.272.297-1.04 1.016-1.04 2.479 0 1.462 1.065 2.875 1.213 3.074.149.198 2.096 3.2 5.077 4.487.709.306 1.262.489 1.694.625.712.227 1.36.195 1.871.118.571-.085 1.758-.719 2.006-1.413.248-.694.248-1.289.173-1.413-.074-.124-.272-.198-.57-.347m-5.421 7.403h-.004a9.87 9.87 0 01-5.031-1.378l-.361-.214-3.741.982.998-3.648-.235-.374a9.86 9.86 0 01-1.51-5.26c.001-5.45 4.436-9.884 9.888-9.884 2.64 0 5.122 1.03 6.988 2.898a9.825 9.825 0 012.893 6.994c-.003 5.45-4.437 9.885-9.885 9.885M20.52 3.449C18.24 1.245 15.24 0 12.045 0 5.463 0 .104 5.36.101 11.945c0 2.096.549 4.14 1.595 5.945L0 24l6.335-1.652a11.9 11.9 0 005.71 1.454h.005c6.582 0 11.946-5.361 11.949-11.945a11.9 11.9 0 00-3.495-8.406" />
    </svg>
  );
}

export function TelegramIcon(props: IconProps) {
  return (
    <svg {...base(props)}>
      <path d="M11.944 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.056 0zm4.962 7.224c.1-.002.321.023.465.14a.506.506 0 0 1 .171.325c.016.093.036.306.02.472-.18 1.898-.962 6.502-1.36 8.627-.168.9-.499 1.201-.82 1.23-.696.065-1.225-.46-1.9-.902-1.056-.693-1.653-1.124-2.678-1.8-1.185-.78-.417-1.21.258-1.91.177-.184 3.247-2.977 3.307-3.23.007-.032.014-.15-.056-.212s-.174-.041-.249-.024c-.106.024-1.793 1.14-5.061 3.345-.48.33-.913.49-1.302.48-.428-.008-1.252-.241-1.865-.44-.752-.245-1.349-.374-1.297-.789.027-.216.325-.437.893-.663 3.498-1.524 5.83-2.529 6.998-3.014 3.332-1.386 4.025-1.627 4.476-1.635z" />
    </svg>
  );
}

// Matrix: the two square brackets of the logo around a lower-case m.
export function MatrixIcon(props: IconProps) {
  return (
    <svg {...base(props)}>
      <path d="M.9 1.1v21.8H3V24H0V0h3v1.1zM23.1 22.9V1.1H21V0h3v24h-3v-1.1zM7.3 7.9v1c.1-.2.3-.4.5-.6.5-.4 1-.6 1.7-.6.6 0 1.1.1 1.5.4.3.2.5.5.7.9.2-.4.5-.7.9-.9.5-.3 1-.4 1.6-.4.4 0 .8.1 1.1.2.3.1.6.3.9.6.2.2.4.5.5.9.1.4.2.8.2 1.3v5.4h-1.7v-5c0-.6-.1-1.1-.4-1.3-.3-.3-.6-.4-1-.4-.4 0-.8.2-1.1.5-.3.3-.4.8-.4 1.4v4.8h-1.7v-5c0-.6-.1-1.1-.4-1.3-.2-.3-.6-.4-1-.4-.4 0-.8.2-1.1.5-.3.3-.4.8-.4 1.4v4.8H5.6V7.9z" />
    </svg>
  );
}

export function InstagramIcon(props: IconProps) {
  return (
    <svg {...base(props)}>
      <path d="M12 0C8.74 0 8.333.015 7.053.072 5.775.132 4.905.333 4.14.63c-.789.306-1.459.717-2.126 1.384S.935 3.35.63 4.14C.333 4.905.131 5.775.072 7.053.012 8.333 0 8.74 0 12s.015 3.667.072 4.947c.06 1.277.261 2.148.558 2.913.306.788.717 1.459 1.384 2.126.667.666 1.336 1.079 2.126 1.384.766.296 1.636.499 2.913.558C8.333 23.988 8.74 24 12 24s3.667-.015 4.947-.072c1.277-.06 2.148-.262 2.913-.558.788-.306 1.459-.718 2.126-1.384.666-.667 1.079-1.335 1.384-2.126.296-.765.499-1.636.558-2.913.06-1.28.072-1.687.072-4.947s-.015-3.667-.072-4.947c-.06-1.277-.262-2.149-.558-2.913-.306-.789-.718-1.459-1.384-2.126C21.319 1.347 20.651.935 19.86.63c-.765-.297-1.636-.499-2.913-.558C15.667.012 15.26 0 12 0zm0 2.16c3.203 0 3.585.016 4.85.071 1.17.055 1.805.249 2.227.415.562.217.96.477 1.382.896.419.42.679.819.896 1.381.164.422.36 1.057.413 2.227.057 1.266.07 1.646.07 4.85s-.015 3.585-.074 4.85c-.061 1.17-.256 1.805-.421 2.227-.224.562-.479.96-.899 1.382-.419.419-.824.679-1.38.896-.42.164-1.065.36-2.235.413-1.274.057-1.649.07-4.859.07-3.211 0-3.586-.015-4.859-.074-1.171-.061-1.816-.256-2.236-.421-.569-.224-.96-.479-1.379-.899-.421-.419-.69-.824-.9-1.38-.165-.42-.359-1.065-.42-2.235-.045-1.26-.061-1.649-.061-4.844 0-3.196.016-3.586.061-4.861.061-1.17.255-1.814.42-2.234.21-.57.479-.96.9-1.381.419-.419.81-.689 1.379-.898.42-.166 1.051-.361 2.221-.421 1.275-.045 1.65-.06 4.859-.06zm0 3.678c-3.405 0-6.162 2.76-6.162 6.162 0 3.405 2.76 6.162 6.162 6.162 3.405 0 6.162-2.76 6.162-6.162 0-3.405-2.76-6.162-6.162-6.162zM12 16c-2.21 0-4-1.79-4-4s1.79-4 4-4 4 1.79 4 4-1.79 4-4 4zm7.846-10.405c0 .795-.646 1.44-1.44 1.44-.795 0-1.44-.646-1.44-1.44 0-.794.646-1.439 1.44-1.439.793-.001 1.44.645 1.44 1.439z" />
    </svg>
  );
}

export function MessengerIcon(props: IconProps) {
  return (
    <svg {...base(props)}>
      <path d="M.001 11.639C.001 4.949 5.241 0 12 0s11.999 4.95 11.999 11.639c0 6.689-5.24 11.638-11.999 11.638-1.155 0-2.271-.14-3.354-.402L2.15 24l-.164-5.928C.741 16.283 0 14.088 0 11.639zm7.755-1.755l-3.11 4.94c-.317.503.303 1.084.855.806l3.35-1.75a.639.639 0 01.6 0l2.75 1.397a.65.65 0 00.865-.259l3.11-4.94c.317-.502-.303-1.083-.855-.806l-3.35 1.75a.639.639 0 01-.6 0l-2.751-1.397a.65.65 0 00-.865.26z" />
    </svg>
  );
}

export function SlackIcon(props: IconProps) {
  return (
    <svg {...base(props)}>
      <path d="M5.042 15.165a2.528 2.528 0 0 1-2.52 2.523A2.528 2.528 0 0 1 0 15.165a2.527 2.527 0 0 1 2.522-2.52h2.52v2.52zM6.313 15.165a2.527 2.527 0 0 1 2.521-2.52 2.527 2.527 0 0 1 2.521 2.52v6.313A2.528 2.528 0 0 1 8.834 24a2.528 2.528 0 0 1-2.521-2.522v-6.313zM8.834 5.042a2.528 2.528 0 0 1-2.521-2.52A2.528 2.528 0 0 1 8.834 0a2.528 2.528 0 0 1 2.521 2.522v2.52H8.834zM8.834 6.313a2.528 2.528 0 0 1 2.521 2.521 2.528 2.528 0 0 1-2.521 2.521H2.522A2.528 2.528 0 0 1 0 8.834a2.528 2.528 0 0 1 2.522-2.521h6.312zM18.956 8.834a2.528 2.528 0 0 1 2.522-2.521A2.528 2.528 0 0 1 24 8.834a2.528 2.528 0 0 1-2.522 2.521h-2.522V8.834zM17.688 8.834a2.528 2.528 0 0 1-2.523 2.521 2.527 2.527 0 0 1-2.52-2.521V2.522A2.527 2.527 0 0 1 15.165 0a2.528 2.528 0 0 1 2.523 2.522v6.312zM15.165 18.956a2.528 2.528 0 0 1 2.523 2.522A2.528 2.528 0 0 1 15.165 24a2.527 2.527 0 0 1-2.52-2.522v-2.522h2.52zM15.165 17.688a2.527 2.527 0 0 1-2.52-2.523 2.526 2.526 0 0 1 2.52-2.52h6.313A2.527 2.527 0 0 1 24 15.165a2.528 2.528 0 0 1-2.522 2.523h-6.313z" />
    </svg>
  );
}

export function DiscordIcon(props: IconProps) {
  return (
    <svg {...base(props)}>
      <path d="M20.317 4.3698a19.7913 19.7913 0 00-4.8851-1.5152.0741.0741 0 00-.0785.0371c-.211.3753-.4447.8648-.6083 1.2495-1.8447-.2762-3.68-.2762-5.4868 0-.1636-.3933-.4058-.8742-.6177-1.2495a.077.077 0 00-.0785-.037 19.7363 19.7363 0 00-4.8852 1.515.0699.0699 0 00-.0321.0277C.5334 9.0458-.319 13.5799.0992 18.0578a.0824.0824 0 00.0312.0561c2.0528 1.5076 4.0413 2.4228 5.9929 3.0294a.0777.0777 0 00.0842-.0276c.4616-.6304.8731-1.2952 1.226-1.9942a.076.076 0 00-.0416-.1057c-.6528-.2476-1.2743-.5495-1.8722-.8923a.077.077 0 01-.0076-.1277c.1258-.0943.2517-.1923.3718-.2914a.0743.0743 0 01.0776-.0105c3.9278 1.7933 8.18 1.7933 12.0614 0a.0739.0739 0 01.0785.0095c.1202.099.246.1981.3728.2924a.077.077 0 01-.0066.1276 12.2986 12.2986 0 01-1.873.8914.0766.0766 0 00-.0407.1067c.3604.698.7719 1.3628 1.225 1.9932a.076.076 0 00.0842.0286c1.961-.6067 3.9495-1.5219 6.0023-3.0294a.077.077 0 00.0313-.0552c.5004-5.177-.8382-9.6739-3.5485-13.6604a.061.061 0 00-.0312-.0286zM8.02 15.3312c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9555-2.4189 2.157-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9555 2.4189-2.1569 2.4189zm7.9748 0c-1.1825 0-2.1569-1.0857-2.1569-2.419 0-1.3332.9554-2.4189 2.1569-2.4189 1.2108 0 2.1757 1.0952 2.1568 2.419 0 1.3332-.9459 2.4189-2.1568 2.4189z" />
    </svg>
  );
}

export function XIcon(props: IconProps) {
  return (
    <svg {...base(props)}>
      <path d="M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z" />
    </svg>
  );
}

// Channel-key → brand mark. Email/Voice/Mobile SDK have no external brand, so
// they use the app's existing lucide icons (rendered with the same size/color
// contract as the brand marks above).
export const CHANNEL_ICON: Record<string, (props: IconProps) => ReactElement> = {
  whatsapp: WhatsappIcon,
  // WhatsApp QR (linked device): the WhatsApp colour with a QR glyph - same
  // channel family, visibly a different (personal) connection method.
  whatsappqr: (p) => <QrCode {...p} />,
  telegram: TelegramIcon,
  matrix: MatrixIcon,
  instagram: InstagramIcon,
  messenger: MessengerIcon,
  slack: SlackIcon,
  discord: DiscordIcon,
  x: XIcon,
  email: (p) => <Mail {...p} />,
  voice: (p) => <PhoneCall {...p} />,
  // Teams has no Simple Icons mark (Microsoft asked for its removal) - a
  // lucide chat glyph, same fallback posture as email/voice/mobile-sdk.
  teams: (p) => <MessagesSquare {...p} />,
  // Signal: no brand mark is bundled here, so a lucide chat glyph on Signal's own blue.
  signal: (p) => <MessageCircle {...p} />,
  "mobile-sdk": (p) => <Smartphone {...p} />,
  // iMessage: Apple's mark is not in Simple Icons either; a lucide chat bubble.
  imessage: (p) => <MessageCircle {...p} />,
};

// Channel-key → brand colour. These are the third parties' own identity
// colours (WhatsApp green, Telegram blue, Discord blurple, Slack aubergine,
// ...), not theme tokens: a channel tile must look like the channel in
// light AND dark mode and under every data-accent, so they deliberately do
// not go through globals.css. Email, Voice and Mobile SDK have no external
// brand; their fixed colours were chosen alongside the real ones so the
// set reads as one palette. `ink` is the text colour with real contrast
// against that specific `color` (white vs near-black picked by WCAG
// relative luminance - X is ~1.2:1 and Slack ~1.9:1 against near-black),
// which is why it travels with the brand colour rather than being a theme
// token. Consumed by ChannelsPanel (tile + initials) and ContactsPanel
// (6px dot next to a neutral initials tile).
export const CHANNEL_BRAND: Record<string, { color: string; ink: string }> = {
  whatsapp: { color: "#1faa52", ink: "#05070d" },
  whatsappqr: { color: "#1faa52", ink: "#05070d" },
  telegram: { color: "#2098d1", ink: "#05070d" },
  matrix: { color: "#0dbd8b", ink: "#05070d" },
  instagram: { color: "#c13584", ink: "#ffffff" },
  messenger: { color: "#0a7cff", ink: "#05070d" },
  slack: { color: "#5b2a63", ink: "#ffffff" },
  discord: { color: "#5865f2", ink: "#ffffff" },
  x: { color: "#1a1d26", ink: "#ffffff" },
  teams: { color: "#6264a7", ink: "#ffffff" },
  email: { color: "#2a6f6f", ink: "#ffffff" },
  voice: { color: "#e67e22", ink: "#05070d" },
  signal: { color: "#3a76f0", ink: "#ffffff" },
  "mobile-sdk": { color: "#7c5cff", ink: "#05070d" },
  imessage: { color: "#34c759", ink: "#05070d" },
};
