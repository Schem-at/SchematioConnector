// node scripts/render-article-figures.mjs /path/to/kineglyph
import { registerHooks } from 'node:module';
import { pathToFileURL, fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';
import fs from 'node:fs/promises';
const kg = resolve(process.argv[2] ?? '../kineglyph');
const article = resolve(dirname(fileURLToPath(import.meta.url)), '../docs/article');
registerHooks({ resolve(s, c, next) {
  return next(s === 'kineglyph' ? pathToFileURL(`${kg}/packages/core/dist/index.js`).href : s, c);
} });
const { resolveScene } = await import(pathToFileURL(`${kg}/packages/core/dist/index.js`));
const { exportSvg, exportPng, exportAnimatedSvg } = await import(pathToFileURL(`${kg}/packages/export/dist/index.js`));
const previews = resolve(article, '../../build/release-readiness/figure-review');
await fs.mkdir(previews, { recursive: true });
const build = `data:image/png;base64,${(await fs.readFile(`${article}/media/copperlight-render.png`)).toString('base64')}`;
for (const name of ['server-workflow', 'bridge-detection', 'minecraft-command']) {
  const module = await import(pathToFileURL(`${article}/scenes/${name}.mjs`));
  for (const width of [320, 390, 720, 960]) {
    const scene = resolveScene(module.default, { width, theme: module.theme });
    if (scene.diagnostics.length) throw new Error(JSON.stringify(scene.diagnostics));
    const options = { background: 'transparent', surfaces: () => ({ observatory: build }), idPrefix: `${name}-${width}` };
    const svg = name === 'minecraft-command' ? exportAnimatedSvg(scene, { ...options, fps: 24 }) : exportSvg(scene, options);
    await fs.writeFile(`${article}/media/${name}-${width}.svg`, svg);
    if (width === 320 || width === 960) await fs.writeFile(`${previews}/${name}-${width}.png`, await exportPng(scene, options));
  }
}
console.log('Rendered responsive Nucleation/Kineglyph compositions and script-free animated SVG commands; zero layout diagnostics.');
