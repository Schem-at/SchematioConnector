import { figure, createTheme } from 'kineglyph';
import themeOptions from './theme.mjs';
export const theme = createTheme(themeOptions);
export default figure('minecraft-command', { title: 'Paste the loaded schematic', description: 'After the download finishes, open Minecraft chat and type //paste.', hold: 900, background: 'transparent', padding: 0 }, f => {
  const chat = f.minecraftCommand('//paste', {
    history: [{ kind: 'success', text: 'WorldEdit clipboard ready.' }],
    cursor: false,
  });
  f.root(f.stack([chat], { padding: 0, width: 'fill' }));
  f.sequence([f.typewrite(chat, { characterDuration: 155 })], { start: 450 });
});
