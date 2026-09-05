import { figure, createTheme } from 'kineglyph';
import themeOptions from './theme.mjs';
export const theme = createTheme(themeOptions);
export default figure('server-workflow', {
  title: 'A clipboard is not a pasted build',
  description: 'WorldEdit holds the downloaded Copperlight schematic in your clipboard. You choose a position, then run //paste to put its blocks in the world.',
  background: 'transparent', padding: 0,
}, f => {
  const notes = f.stack([
    f.eyebrow('01 / DOWNLOAD', { tone: 'info' }),
    f.heading('Ready in your clipboard'),
    f.body('The plugin loads the schematic into WorldEdit. Your world has not changed yet.'),
    f.rule({ tone: 'border' }),
    f.eyebrow('02 / PASTE', { tone: 'accent' }),
    f.heading('Place it where you stand'),
    f.body('Choose your position, then run //paste.'),
  ], { width: 'fill', grow: .8, gap: 9, padding: { wide: [30, 0, 10, 0], compact: [20, 0, 10, 0], narrow: 0 } });
  const grid = f.path('M12 234L210 138L410 234L212 330Z M62 258L260 162 M112 282L310 186 M162 306L360 210 M62 210L262 306 M112 186L312 282 M162 162L362 258', { width: 420, height: 350 }, { width: 'fill', height: 'fill', stroke: 'border', fill: 'none', strokeWidth: .5, opacity: .45 });
  const image = f.image(new URL('../media/copperlight-render.png', import.meta.url).href, 'The actual Copperlight Observatory schematic, rendered by Nucleation.', { id: 'observatory', width: 'fill', height: 'fill', fit: 'contain' });
  const build = f.coordinates([grid, image], { width: 'fill', grow: 1.2, aspect: .9, label: 'Copperlight Observatory on a schematic grid' });
  f.root(f.stack([f.flow([notes, build], { width: 'fill', gap: { wide: 24, compact: 18, narrow: 8 } })], { width: 'fill', padding: 0 }));
});
