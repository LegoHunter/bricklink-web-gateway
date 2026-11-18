// Manual sign-in: opens a real Chrome window on a dedicated profile and waits
// for a person to sign in to BrickLink by hand, email code included. The
// script never types a credential and there is no automated sign-in.
//
// While the person signs in, the script records the shape of the sign-in flow
// (hosts, paths, status codes, form field names) in a report, which helps when
// LEGO changes the flow. It never records field values, query strings, cookie
// values or page text typed by the person.
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright-core';
import { PROFILE_DIR, REPORT_DIR, cookieInventory, ensureDirs, probeSignedIn, safeUrl, stamp } from './common.mjs';

const WAIT_MINUTES = Number(process.env.LOGIN_WAIT_MINUTES ?? 20);
const FLOW_HOSTS = /(^|\.)(bricklink\.com|lego\.com)$/;
const BOT_VENDORS = /captcha|recaptcha|hcaptcha|turnstile|datadome|perimeterx|px-cdn|akamai|imperva|incapsula|kasada|arkoselabs|funcaptcha|fingerprint/i;

ensureDirs();
const flow = [];
const botSignals = new Set();
const note = (kind, detail) => {
  flow.push({ at: stamp(), kind, ...detail });
  console.log(`[${kind}] ${Object.values(detail).join(' ')}`);
};

const context = await chromium.launchPersistentContext(PROFILE_DIR, {
  channel: 'chrome',
  headless: false,
  viewport: null,
});
const page = context.pages()[0] ?? (await context.newPage());

context.on('response', (response) => {
  const request = response.request();
  let host;
  try {
    host = new URL(request.url()).host;
  } catch {
    return;
  }
  if (BOT_VENDORS.test(request.url())) {
    botSignals.add(host);
  }
  const type = request.resourceType();
  if (!FLOW_HOSTS.test(host) || !['document', 'xhr', 'fetch'].includes(type)) {
    return;
  }
  note('http', { method: request.method(), status: response.status(), type, url: safeUrl(request.url()) });
});

page.on('load', async () => {
  try {
    const fields = await page.evaluate(() =>
      [...document.querySelectorAll('input, button[type=submit]')]
        .filter((el) => el.type !== 'hidden')
        .map((el) => ({
          tag: el.tagName.toLowerCase(),
          type: el.type,
          name: el.name || el.id || '',
          autocomplete: el.autocomplete || '',
          label: el.type === 'checkbox' || el.tagName === 'BUTTON'
            ? (el.labels?.[0]?.innerText || el.innerText || el.getAttribute('aria-label') || '').trim().slice(0, 80)
            : '',
        })),
    );
    note('page', { url: safeUrl(page.url()), fields: JSON.stringify(fields) });
  } catch {
    // The page navigated away while it was being inspected.
  }
});

console.log(`\nA Chrome window is open. Sign in to BrickLink there, including the email code.`);
console.log(`Waiting up to ${WAIT_MINUTES} minutes. Do not close the window; it closes itself.\n`);
await page.goto('https://www.bricklink.com/my.asp', { waitUntil: 'domcontentloaded' });

const deadline = Date.now() + WAIT_MINUTES * 60_000;
let result = { signedIn: false };
while (Date.now() < deadline) {
  await new Promise((resolve) => setTimeout(resolve, 3000));
  if (context.pages().length === 0) {
    console.log('The window was closed before sign-in completed.');
    break;
  }
  let host = '';
  try {
    host = new URL(page.url()).host;
  } catch {
    continue;
  }
  if (!host.endsWith('bricklink.com') || page.url().includes('/auth/')) {
    continue; // Still on the LEGO Account pages.
  }
  result = await probeSignedIn(context);
  if (result.signedIn) {
    break;
  }
}

const report = {
  finishedAt: stamp(),
  signedIn: result.signedIn,
  webdriverFlag: await page.evaluate(() => navigator.webdriver).catch(() => 'unknown'),
  botVendorHosts: [...botSignals],
  cookies: cookieInventory(await context.cookies()),
  flow,
};
const file = path.join(REPORT_DIR, `login-${report.finishedAt.replace(/[:.]/g, '-')}.json`);
fs.writeFileSync(file, JSON.stringify(report, null, 2));
await context.close();

console.log(`\n${result.signedIn ? 'SIGNED IN' : 'NOT SIGNED IN'}; report written to ${file}`);
process.exit(result.signedIn ? 0 : 1);
