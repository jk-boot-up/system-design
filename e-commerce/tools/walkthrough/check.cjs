// Plays every step of every scenario in each walkthrough and fails on any page or console error.
// Usage: node tools/walkthrough/check.cjs <index.html> [...]   (needs Playwright with Chromium)
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  let failed = 0;
  for (const file of process.argv.slice(2)) {
    const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text()); });
    await page.goto('file://' + require('path').resolve(file));
    const total = await page.evaluate(() => window.__walkthrough.scenarios);
    let steps = 0;
    for (let s = 0; s < total; s++) {
      const n = await page.evaluate(i => { window.__walkthrough.select(i); return window.__walkthrough.steps(i); }, s);
      for (let i = 0; i < n; i++) { await page.evaluate(k => window.__walkthrough.show(k), i); steps++; }
    }
    for (const t of ['light', 'medium', 'dark']) await page.click(`[data-set-theme="${t}"]`);
    console.log(`${errors.length ? 'FAIL' : 'OK  '} ${file}: ${total} scenarios, ${steps} steps${errors.length ? ' — ' + errors.join(' | ') : ''}`);
    if (errors.length) failed++;
    await page.close();
  }
  await browser.close();
  process.exit(failed ? 1 : 0);
})();
