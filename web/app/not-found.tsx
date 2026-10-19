import Link from "next/link";
import { BookOpen, Home } from "lucide-react";
import Logo from "./components/Logo";

// Standard 404 (2026-09-24, paper & ink redesign). One centred glass slab on
// the shared body mesh, so a dead link still looks like the product. Inline
// English on purpose (messages/en.json is fragile — see web-i18n-rules).
export default function NotFound() {
  return (
    <main className="std-screen">
      <div className="std-screen__card">
        <div className="mb-6 flex justify-center">
          <Logo size={30} />
        </div>
        <div className="std-screen__code" aria-hidden="true">404</div>
        <h1>This page does not exist.</h1>
        <p>The link may be old, or the page moved. Nothing on your workspace was touched.</p>
        <div className="std-screen__actions">
          <Link href="/" className="std-screen__btn std-screen__btn--primary">
            <Home className="size-4" aria-hidden="true" />
            Go home
          </Link>
          <Link href="/docs" className="std-screen__btn std-screen__btn--secondary">
            <BookOpen className="size-4" aria-hidden="true" />
            Read the docs
          </Link>
        </div>
      </div>
    </main>
  );
}
