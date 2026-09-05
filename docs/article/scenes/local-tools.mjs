import { figure, createTheme } from 'kineglyph';
import themeOptions from './theme.mjs';
import { stage, workshop } from './workshop.mjs';
export const theme = createTheme(themeOptions);
export default figure('local-tools', { title: 'A placement to follow, or a clipboard to paste' }, f => {
  workshop(f, 'Choose where the build goes', 'FABRIC MOD / LOCAL TOOLS', [
    stage(f, { number: 'LITEMATICA', title: 'A hologram to build against', detail: 'Load into Litematica creates a client placement. Singleplayer or multiplayer.', mode: 'ghost', tone: 'info' }),
    stage(f, { number: 'LOCAL WORLDEDIT', title: 'A clipboard ready to paste', detail: 'To WorldEdit clipboard uses your integrated server. Singleplayer or hosted LAN.', mode: 'world', tone: 'success' }),
  ], 'Install the matching building tool. Save to disk is also available.');
});
