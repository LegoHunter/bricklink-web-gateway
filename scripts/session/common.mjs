// Shared settings for the PC-side session scripts (they began as the HOMELAB-79 spike).
//
// The browser profile holds live session cookies, so it lives outside every
// Git repository. Nothing in this directory may be committed or logged.
import fs from 'node:fs';
import path from 'node:path';

export const SESSION_DIR = process.env.BRICKLINK_SESSION_DIR ?? 'D:/dev/config/bricklink-session';
export const PROFILE_DIR = path.join(SESSION_DIR, 'profile');
export const REPORT_DIR = path.join(SESSION_DIR, 'reports');

// Pages that only a signed-in user can open.
//
// BrickLink has two session layers. The long-lived layer is the LEGO Account
// token cookies. The classic ASP pages also need a short-lived ASP session,
// held in session-only cookies that vanish when the browser closes. When that
// is missing, a classic page redirects to sessionSync.asp, which rebuilds it
// from the tokens and redirects back. So a single response is not conclusive:
// the redirects must be followed with a cookie jar, and the final URL decides.
export const PROBE_URL = 'https://www.bricklink.com/my.asp';
// Once the ASP session exists this answers 200 directly (for a lot that is not
// ours it shows an error page, still 200); otherwise it redirects.
export const CLASSIC_PROBE_URL = 'https://www.bricklink.com/imgAdd.asp?invID=1';

export function ensureDirs() {
  fs.mkdirSync(PROFILE_DIR, { recursive: true });
  fs.mkdirSync(REPORT_DIR, { recursive: true });
}

export function stamp() {
  return new Date().toISOString();
}

/** Host and path only. Query strings carry OAuth codes and tokens. */
export function safeUrl(raw) {
  try {
    const u = new URL(raw);
    return `${u.host}${u.pathname}`;
  } catch {
    return '<unparseable>';
  }
}

/**
 * Opens the probe page, following redirects with the context's cookie jar.
 * Signed in: the chain ends on a www.bricklink.com page. Not signed in: it ends
 * on the sign-in route or the LEGO Account site.
 */
export async function probeSignedIn(context) {
  const response = await context.request.get(PROBE_URL, { failOnStatusCode: false });
  const final = new URL(response.url());
  const signedIn = response.status() === 200 && final.host === 'www.bricklink.com' && !/\/auth\/|sign-in|login/i.test(final.pathname);
  return { signedIn, status: response.status(), final: safeUrl(response.url()) };
}

/** Cookie metadata without values. */
export function cookieInventory(cookies) {
  return cookies
    .map((c) => ({
      domain: c.domain,
      name: c.name,
      expires: c.expires > 0 ? new Date(c.expires * 1000).toISOString() : 'session',
      httpOnly: c.httpOnly,
      secure: c.secure,
      sameSite: c.sameSite,
      valueLength: c.value.length,
    }))
    .sort((a, b) => (a.domain + a.name).localeCompare(b.domain + b.name));
}
