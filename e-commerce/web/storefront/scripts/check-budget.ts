// JavaScript budget (design/16 §16.10): at most 150 kB compressed per route. All client JavaScript together is an upper
// bound for any one route, so checking the total is enough while it stays under the budget.
import { readdir, readFile } from 'node:fs/promises';
import { gzipSync } from 'node:zlib';

const BUDGET_KB = 150;
const dir = new URL('../build/client/assets/', import.meta.url);
const files = (await readdir(dir)).filter((f) => f.endsWith('.js'));
let total = 0;
for (const f of files) total += gzipSync(await readFile(new URL(f, dir))).length;
const kb = total / 1024;
console.log(`client JavaScript: ${kb.toFixed(1)} kB gzip in ${files.length} files (budget ${BUDGET_KB} kB per route)`);
if (kb > BUDGET_KB) {
  console.error('Over budget: measure per route before adding more.');
  process.exit(1);
}
