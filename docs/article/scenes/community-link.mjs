import { figure, createTheme } from 'kineglyph';
import themeOptions from './theme.mjs';
import { artwork, workshop } from './workshop.mjs';
export const theme = createTheme(themeOptions);
export default figure('community-link', { title: 'Give your server its community key' }, f => {
  const token = f.stack([f.eyebrow('COMMUNITY SETTINGS'), artwork(f, 'key'), f.heading('Create a plugin token'),
    f.caption('Plugin API Tokens → Create Plugin Token')], { width: 'fill', gap: 8 });
  const server = f.stack([f.eyebrow('YOUR PAPER SERVER'), artwork(f, 'server'), f.heading('Configure the server'),
    f.caption('Run schematio settoken <token> in its console.')], { width: 'fill', gap: 8 });
  workshop(f, 'Give your server its community key', 'FOR COMMUNITY OWNERS', [token, server],
    'The token stays on the server. Each player keeps their own identity and permissions.');
});
