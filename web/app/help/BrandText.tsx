import { Fragment } from "react";
import BrandName from "../components/BrandName";
import InlineStrong from "../components/InlineStrong";

// Renders a translated string in which the token {brand} becomes the VegaDūta wordmark (BrandName) and
// <strong>…</strong> is the only other markup (InlineStrong). Keeps the brand out of HTML entities in copy.
export default function BrandText({ text }: { text: string }) {
  const parts = text.split("{brand}");
  return (
    <>
      {parts.map((p, i) => (
        <Fragment key={i}>
          {i > 0 && <BrandName />}
          <InlineStrong text={p} />
        </Fragment>
      ))}
    </>
  );
}
