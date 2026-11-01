// Renders every **/design/diagrams/src/<name>.mmd in this system to ../<name>.light.svg and ../<name>.dark.svg.
// Only diagrams whose source is newer than their images are re-rendered (pass --all to force).
//
//   cd tools/diagrams && npm install && npm run render
//
// Output is optimised with SVGO (svgo.config.mjs).
// Uses the Chromium given by CHROME_PATH, or Puppeteer's own download when unset.
import { readdir, readFile, writeFile, stat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer';
import { renderMermaid } from '@mermaid-js/mermaid-cli';
import { optimize } from 'svgo';
import svgoConfig from './svgo.config.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '../..');
const force = process.argv.includes('--all');
// optional paths limit rendering to those folders (e.g. one system)
const roots = process.argv.slice(2).filter(a => !a.startsWith('--')).map(a => resolve(process.cwd(), a));

const themes = {
  light: { theme: 'default', themeVariables: { fontFamily: 'Inter, Segoe UI, Roboto, sans-serif' } },
  dark: {
    theme: 'dark',
    themeVariables: { fontFamily: 'Inter, Segoe UI, Roboto, sans-serif', background: 'transparent' },
  },
};

// Mermaid emits width="100%"; give the image its real size so small diagrams are not stretched.
function naturalSize(svg) {
  const vb = svg.match(/viewBox="[\d.-]+ [\d.-]+ ([\d.]+) ([\d.]+)"/);
  if (!vb) return svg;
  return svg.replace(/<svg([^>]*?) width="100%"/, `<svg$1 width="${Math.ceil(+vb[1])}" height="${Math.ceil(+vb[2])}"`)
            .replace(/(<svg[^>]*?style="[^"]*?)max-width:\s*[\d.]+px;?/, '$1');
}

const mtime = async p => { try { return (await stat(p)).mtimeMs; } catch { return 0; } };

// every design/diagrams/src folder, skipping dependency and build folders
async function findSourceDirs(dir, found = []) {
  if (!(await stat(dir)).isDirectory()) return found;
  for (const e of await readdir(dir, { withFileTypes: true })) {
    if (!e.isDirectory() || ['node_modules', '.git', 'build', '.gradle', '.venv'].includes(e.name)) continue;
    const p = join(dir, e.name);
    if (e.name === 'src' && p.endsWith(join('design', 'diagrams', 'src'))) found.push(p);
    else await findSourceDirs(p, found);
  }
  return found;
}

const sources = [];
for (const srcDir of (await Promise.all((roots.length ? roots : [repo]).map(r => findSourceDirs(r)))).flat())
  for (const f of (await readdir(srcDir)).filter(f => f.endsWith('.mmd')).sort()) sources.push({ srcDir, file: f });
const todo = [];
for (const { srcDir, file } of sources) {
  const outDir = dirname(srcDir);
  const name = file.slice(0, -4);
  const srcTime = await mtime(join(srcDir, file));
  for (const variant of Object.keys(themes)) {
    const out = join(outDir, `${name}.${variant}.svg`);
    if (force || (await mtime(out)) < srcTime) todo.push({ srcDir, file, name, variant, out });
  }
}
if (!todo.length) { console.log(`diagrams up to date (${sources.length})`); process.exit(0); }

const browser = await puppeteer.launch({
  executablePath: process.env.CHROME_PATH || undefined,
  headless: true,
  args: ['--no-sandbox', '--disable-gpu'],
});
let failed = 0;
try {
  for (const job of todo) {
    const definition = await readFile(join(job.srcDir, job.file), 'utf8');
    try {
      const { data } = await renderMermaid(browser, definition, 'svg', {
        backgroundColor: 'transparent',
        mermaidConfig: { ...themes[job.variant], securityLevel: 'strict', flowchart: { htmlLabels: true } },
      });
      await writeFile(job.out, naturalSize(optimize(new TextDecoder().decode(data), svgoConfig).data));
    } catch (e) {
      failed++;
      console.error(`FAILED ${job.file} (${job.variant}): ${e.message.split('\n')[0]}`);
    }
  }
} finally {
  await browser.close();
}
console.log(`rendered ${todo.length - failed} image(s), ${failed} failed`);
process.exit(failed ? 1 : 0);
