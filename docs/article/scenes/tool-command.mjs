import { figure, createTheme } from 'kineglyph';
import themeOptions from './theme.mjs';
export const theme = createTheme(themeOptions);
export default figure('tool-command', { title: 'List the tools you can run', description: 'Type /schematio tool list in chat to see the tools your community role allows.', hold: 900, background: 'transparent', padding: 0 }, f => {
  const chat = f.minecraftCommand('/schematio tool list', { cursor: false });
  f.root(f.stack([chat], { padding: 0, width: 'fill' }));
  f.sequence([f.typewrite(chat, { characterDuration: 70 })], { start: 450 });
});
