import { figure, createTheme } from 'kineglyph';
import themeOptions from './theme.mjs';
export const theme = createTheme(themeOptions);
export default figure('tool-workflow', { title: 'A tool result lands in your clipboard', description: 'List the Schematio tools your role allows, run one from chat, then paste the result it puts in your WorldEdit clipboard.', background: 'transparent', padding: 0, breakpoints: { wide: 600, compact: 440 } }, f => {
  const step = (n, title, detail, tone) => f.stack([
    f.eyebrow(n, { tone }), f.heading(title), f.caption(detail),
  ], { width: 'fill', gap: 7 });
  f.root(f.stack([
    f.rule({ tone: 'border' }),
    f.flow([
      step('01 / LIST', 'Pick a tool', 'Run /schematio tool list.', 'info'),
      step('02 / RUN', 'Run it on Schematio', 'Add settings, then press Enter.', 'accent'),
      step('03 / PASTE', 'Paste the result', 'It fills your clipboard; run //paste.', 'success'),
    ], { width: 'fill', gap: { wide: 24, compact: 20, narrow: 18 } }),
    f.rule({ tone: 'border' }),
  ], { width: 'fill', padding: 0, gap: 17 }));
});
