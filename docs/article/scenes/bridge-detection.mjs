import { figure, createTheme } from 'kineglyph';
import themeOptions from './theme.mjs';
import { artwork, workshop } from './workshop.mjs';
export const theme = createTheme(themeOptions);
export default figure('bridge-detection', { title: 'Join the server. Connector checks the connection.' }, f => {
  const server = f.stack([artwork(f, 'server'), f.heading('Join a Connector server'),
    f.caption('The mod detects the plugin and requests its community identity.')], { width: 'fill', gap: 8 });
  const checks = f.stack([
    f.eyebrow('THE MOD CHECKS'),
    f.heading('Server detected'), f.caption('The plugin advertises its tools.'), f.rule(),
    f.heading('Community verified'), f.caption('Schematio checks the signed identity.'), f.rule(),
    f.heading('Server tools available'), f.caption('WorldEdit and your permissions determine which actions appear.'),
    f.stack([f.heading('↓  Load on server', { tone: 'success' }), f.caption('Fetch into your server clipboard.')],
      { padding: 14, gap: 4, width: 'fill', frame: { fill: 'surfaceRaised', stroke: 'border', radius: 2 } }),
  ], { width: 'fill', gap: 9, padding: [16, 0] });
  workshop(f, 'Your mod recognises the server', 'AUTOMATIC WHEN YOU JOIN', [server, checks],
    'No community token in your client. Rejoin after the server owner changes the token.');
});
