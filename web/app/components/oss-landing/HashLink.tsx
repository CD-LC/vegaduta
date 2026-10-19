"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

/** Link to "/#section". next/link only updates the hash when the visitor is
 *  already on "/" and the browser does not scroll (SE-23), so on the target page
 *  we scroll the section into view ourselves; elsewhere a normal navigation
 *  carries the hash and the browser scrolls on load. */
export default function HashLink({
  href,
  className,
  children,
}: {
  href: string;
  className?: string;
  children: React.ReactNode;
}) {
  const pathname = usePathname();
  const hashIndex = href.indexOf("#");
  const targetPath = href.slice(0, hashIndex) || "/";
  const id = href.slice(hashIndex + 1);

  return (
    <Link
      href={href}
      className={className}
      onClick={(e) => {
        if (pathname !== targetPath) return;
        const el = document.getElementById(id);
        if (!el) return;
        e.preventDefault();
        el.scrollIntoView({ behavior: "smooth", block: "start" });
        window.history.pushState(null, "", `#${id}`);
      }}
    >
      {children}
    </Link>
  );
}
