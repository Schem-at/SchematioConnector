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
const { exportSvg, exportPng, exportVideo } = await import(pathToFileURL(`${kg}/packages/export/dist/index.js`));
const previews = resolve(article, '../../build/release-readiness/figure-review');
await fs.mkdir(previews, { recursive: true });
for (const name of ['server-workflow', 'community-link', 'local-tools', 'bridge-detection']) {
  const scene = await import(pathToFileURL(`${article}/scenes/${name}.mjs`));
  for (const width of [320, 390, 720, 960]) {
    const result = resolveScene(scene.default, { width, theme: scene.theme });
    if (result.diagnostics.length) throw new Error(JSON.stringify(result.diagnostics));
    await fs.writeFile(`${article}/media/${name}-${width}.svg`, exportSvg(result));
    if (width === 960 || width === 320) await fs.writeFile(`${previews}/${name}-${width}.png`, await exportPng(result));
  }
}
console.log('Rendered 16 Kineglyph SVGs; zero layout diagnostics.');
const command = await import(pathToFileURL(`${article}/scenes/minecraft-command.mjs`));
for (const width of [320, 720]) {
  const result = resolveScene(command.default, { width, theme: command.theme });
  if (result.diagnostics.length) throw new Error(JSON.stringify(result.diagnostics));
  await fs.writeFile(`${article}/media/minecraft-command-${width}.svg`, exportSvg(result, { time: 2000 }));
  await fs.writeFile(`${article}/media/minecraft-command-${width}.mp4`, await exportVideo(result, { fps: 20, format: 'mp4' }));
  await fs.writeFile(`${previews}/minecraft-command-${width}.png`, await exportPng(result, { time: 2000 }));
}
console.log('Rendered Minecraft command typing at two widths, with reduced-motion stills.');
