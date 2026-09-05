import { figure, createTheme } from 'kineglyph';
import themeOptions from './theme.mjs';
import { stage, workshop } from './workshop.mjs';
export const theme = createTheme(themeOptions);
export default figure('server-workflow', { title: 'From a download to blocks in your world' }, f => {
  workshop(f, 'From the library into your world', 'PAPER PLUGIN + WORLDEDIT', [
    stage(f, { number: '01 / CHOOSE', title: 'Your community library', detail: 'Find a build on Schematio.', mode: 'library' }),
    stage(f, { number: '02 / LOAD', title: 'Your WorldEdit clipboard', detail: 'Connector fetches the schematic.', mode: 'clipboard', tone: 'info' }),
    stage(f, { number: '03 / PASTE', title: 'Blocks in the world', detail: 'Run //paste to place the build.', mode: 'world', tone: 'success' }),
  ], 'To upload: select a build, run //copy, then /schematio upload.');
});
