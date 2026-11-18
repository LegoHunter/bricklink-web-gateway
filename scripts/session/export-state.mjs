// Exports the saved session's cookies as a Playwright storage-state file, the
// form the gateway takes on POST /admin/session/import. The Chrome profile
// itself is not portable (Windows encrypts its cookie store), so the cookies
// are exported instead.
//
// The output file holds live session cookies. It is written next to the
// profile, outside every Git repository, and must be deleted once imported.
//   node export-state.mjs
import path from 'node:path';
import { chromium } from 'playwright-core';
import { PROFILE_DIR, SESSION_DIR, probeSignedIn } from './common.mjs';

const out = path.join(SESSION_DIR, 'portable-state.json');
const context = await chromium.launchPersistentContext(PROFILE_DIR, { channel: 'chrome', headless: true });
const probe = await probeSignedIn(context);
if (!probe.signedIn) {
  console.log('NOT SIGNED IN here; nothing exported');
  await context.close();
  process.exit(1);
}
const state = await context.storageState({ path: out });
await context.close();
console.log(`exported ${state.cookies.length} cookies to ${out} (values not shown)`);
