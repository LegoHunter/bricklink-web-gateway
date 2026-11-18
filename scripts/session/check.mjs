// Session check: reopens the saved profile without a window and reports
// whether BrickLink still treats it as signed in. Each run appends one line to
// reports/session-log.csv (time, result and cookie expiry dates; no values).
//
//   node check.mjs          browser-held session only
//   node check.mjs --http   also replay the cookies from a plain HTTP client
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright-core';
import { CLASSIC_PROBE_URL, PROFILE_DIR, REPORT_DIR, ensureDirs, probeSignedIn, stamp } from './common.mjs';

ensureDirs();
const withHttp = process.argv.includes('--http');

const context = await chromium.launchPersistentContext(PROFILE_DIR, { channel: 'chrome', headless: true });
const browser = await probeSignedIn(context);

let http = { status: 'skipped' };
if (withHttp) {
  // The browser probe above has just rebuilt the ASP session in the cookie jar.
  // Replay the jar from a plain HTTP client against a classic page; values stay
  // in memory.
  const cookies = await context.cookies(CLASSIC_PROBE_URL);
  const response = await fetch(CLASSIC_PROBE_URL, {
    redirect: 'manual',
    headers: { cookie: cookies.map((c) => `${c.name}=${c.value}`).join('; '), 'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' },
  });
  http = { status: response.status, signedIn: response.status === 200, cookiesSent: cookies.length };
}
// Expiry dates of the cookies that carry each session layer. If regular use
// renews a layer, its date moves forward from one check to the next.
const allCookies = await context.cookies();
const expiry = (name) => {
  const cookie = allCookies.find((c) => c.name === name);
  return cookie && cookie.expires > 0 ? new Date(cookie.expires * 1000).toISOString() : '';
};
await context.close();

const log = path.join(REPORT_DIR, 'session-log.csv');
if (!fs.existsSync(log)) {
  fs.writeFileSync(log, 'checked_at,browser_signed_in,browser_status,http_status,bl_token_expires,bl_session_expires,lego_sso_expires\n');
}
fs.appendFileSync(log, `${stamp()},${browser.signedIn},${browser.status},${http.status},${expiry('bricklink.lego-account.refresh-token')},${expiry('bricklink.portal.session-id')},${expiry('id.a')}\n`);

console.log(`browser session: ${browser.signedIn ? 'SIGNED IN' : 'NOT SIGNED IN'} (HTTP ${browser.status}, ended at ${browser.final})`);
if (withHttp) {
  console.log(`plain HTTP replay: ${http.signedIn ? 'SIGNED IN' : 'NOT SIGNED IN'} (HTTP ${http.status}, ${http.cookiesSent} cookies sent)`);
}
process.exit(browser.signedIn ? 0 : 1);
