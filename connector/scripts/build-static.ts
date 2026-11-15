// Builds the two browser pages for hosts where *.supabase.co would serve them as text/plain:
//   dist/consent.html  (set ATALL_CONSENT_URL to wherever you host it)
//   dist/dev.html      (owner console; same host)
// The function URL is baked in at build time. Host on anything that serves real text/html
// (Cloudflare Pages, GitHub Pages, Netlify…). dist/_headers adds anti-framing for Cloudflare Pages/Netlify.
//
//   ATALL_URL=https://<ref>.supabase.co/functions/v1/atall deno run --allow-read --allow-write --allow-env scripts/build-static.ts

import { externalConsentPageHtml } from "../supabase/functions/atall/src/pages.ts";
import { devPageHtml } from "../supabase/functions/atall/src/admin.ts";

const api = (Deno.args[0] ?? Deno.env.get("ATALL_URL") ?? "").replace(/\/+$/, "");
if (!/^https?:\/\/[^/]+\/.*atall$|^https:\/\/[^/]+$/.test(api)) {
  console.error("usage: ATALL_URL=https://<ref>.supabase.co/functions/v1/atall deno run -A scripts/build-static.ts");
  Deno.exit(2);
}
const out = new URL("../dist/", import.meta.url);
Deno.mkdirSync(out, { recursive: true });
const origin = new URL(api).origin;
const devCsp = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src ${origin}; base-uri 'none'; form-action 'none'"><meta name="referrer" content="no-referrer">`;
Deno.writeTextFileSync(new URL("consent.html", out), externalConsentPageHtml(api));
Deno.writeTextFileSync(new URL("dev.html", out), devPageHtml(api, devCsp));
Deno.writeTextFileSync(new URL("_headers", out), "/*\n  X-Frame-Options: DENY\n  Content-Security-Policy: frame-ancestors 'none'\n  Referrer-Policy: no-referrer\n  Cache-Control: no-store\n");
console.log(`wrote dist/consent.html, dist/dev.html, dist/_headers for ${api}`);
console.log("Then set the function secret ATALL_CONSENT_URL=https://<your-static-host>/consent.html");
