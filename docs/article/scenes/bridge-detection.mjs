import { figure, createTheme } from 'kineglyph';
import themeOptions from './theme.mjs';
export const theme = createTheme(themeOptions);
export default figure('bridge-detection', { title: 'Server actions appear after verification', description: 'Joining detects the Paper plugin. Schematio verifies the community identity. WorldEdit and your permissions determine which server actions become available.', background: 'transparent', padding: 0, breakpoints: { wide: 600, compact: 440 } }, f => {
  const step = (n, title, detail, tone) => f.stack([
    f.eyebrow(n, { tone }), f.heading(title), f.caption(detail),
  ], { width: 'fill', gap: 7 });
  f.root(f.stack([
    f.rule({ tone: 'border' }),
    f.flow([
      step('01 / JOIN', 'Plugin detected', 'Your mod recognises the server.', 'info'),
      step('02 / VERIFY', 'Community checked', 'Schematio verifies its signed identity.', 'accent'),
      step('03 / USE', 'Server tools appear', 'WorldEdit and permissions decide which.', 'success'),
    ], { width: 'fill', gap: { wide: 24, compact: 20, narrow: 18 } }),
    f.rule({ tone: 'border' }),
  ], { width: 'fill', padding: 0, gap: 17 }));
});
