import { figure, createTheme } from 'kineglyph';
import themeOptions from './theme.mjs';
export const theme = createTheme(themeOptions);
export default figure('minecraft-command', { title: 'Type a WorldEdit paste command', description: 'Illustrated Minecraft chat input. The clipboard has already been loaded.', hold: 1800 }, f => {
  const chat = f.minecraftCommand('//paste', {
    context: 'MINECRAFT CHAT / ILLUSTRATED EXAMPLE',
    history: [{ kind: 'success', text: 'WorldEdit clipboard ready.' }],
    suggestions: ['//paste [-a]'],
    cursor: false,
  });
  f.root(f.stack([chat], { padding: { wide: [24, 24, 64, 24], compact: [20, 20, 60, 20], narrow: [12, 12, 52, 12] }, width: 'fill' }));
  f.sequence([f.typewrite(chat, { characterDuration: 145 })], { start: 500 });
});
