// Original block illustrations, authored as Kineglyph paths. No Minecraft textures or fonts.
const W = 320, H = 238;
export function artwork(f, mode = 'world') {
  const nodes = [];
  const path = (d, fill, stroke = 'none', opacity = 1, strokeWidth = .6) => {
    nodes.push(f.path(d, { width: W, height: H }, {
      width: 'fill', height: 'fill', fill, stroke, strokeWidth, opacity,
    }));
  };
  const rect = (x, y, w, h, fill, stroke = 'none') => path(`M${x},${y}h${w}v${h}h-${w}Z`, fill, stroke);
  const line = (d, tone = 'border', opacity = 1) => path(d, 'none', tone, opacity, 1);
  if (mode === 'server') {
    path('M60 187L158 136L270 188L170 232Z', 'surfaceRaised');
    path('M90 38L214 52L244 33L120 20Z', 'chart3');
    path('M214 52L244 33V181L214 204Z', 'surface');
    rect(90, 38, 124, 150, 'surfaceRaised', 'border');
    for (let i = 0; i < 3; i++) {
      const y = 52 + i * 43;
      rect(100, y, 104, 31, 'surfaceMuted', 'border');
      rect(109, y + 12, 7, 7, 'success');
      for (let n = 0; n < 6; n++) rect(145 + n * 7, y + 8, 2, 16, 'border');
    }
    line('M109 189V207H166V222', 'accent');
    return f.coordinates(nodes, { width: 'fill', aspect: H / W, label: 'Paper server with running services' });
  }
  if (mode === 'key') {
    path('M74 55h58v14h14v58h-14v14h-16v42h18v18h-18v16H93v-76H74v-14H60V69h14Z', 'accent');
    rect(87, 82, 32, 32, 'canvas');
    for (let i = 0; i < 6; i++) rect(173 + (i % 3) * 28, 82 + Math.floor(i / 3) * 38, 13, 13, 'textMuted');
    line('M158 161h105M158 168h72', 'border');
    return f.coordinates(nodes, { width: 'fill', aspect: H / W, label: 'Community token represented by a key and masked characters' });
  }
  const library = mode === 'library';
  const ghost = mode === 'ghost' || mode === 'clipboard';
  if (library) {
    rect(19, 13, 282, 211, 'surfaceMuted', 'border');
    rect(19, 13, 282, 27, 'surfaceRaised', 'border');
    rect(30, 22, 8, 8, 'accent');
    line('M50 26h94M252 26h34', 'textMuted');
    rect(30, 53, 49, 156, 'surface');
    for (let i = 0; i < 4; i++) {
      rect(39, 63 + i * 36, 30, 22, i === 0 ? 'chart4' : 'surfaceRaised');
      line(`M42 ${91 + i * 36}h21`, 'border');
    }
  }
  const scale = library ? .7 : 1;
  const ox = library ? 193 : 160, oy = 143;
  const p = (x, z, y) => [ox + (x - z) * 10 * scale, oy + (x + z) * 5 * scale - y * 11 * scale];
  const polygon = (points, tone) => {
    const d = points.map(([x, y], i) => `${i ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`).join('') + 'Z';
    path(d, ghost ? 'surfaceMuted' : tone, ghost ? 'info' : 'canvas', ghost ? .55 : 1, ghost ? .65 : .3);
  };
  for (let a = -6; a <= 6; a += 2) {
    line(`M${p(a,-6,-3)}L${p(a,6,-3)}`, ghost ? 'chart5' : 'surfaceRaised', .7);
    line(`M${p(-6,a,-3)}L${p(6,a,-3)}`, ghost ? 'chart5' : 'surfaceRaised', .7);
  }
  const blocks = new Map();
  const put = (x, z, y, kind) => blocks.set(`${x},${z},${y}`, { x, z, y, kind });
  for (let x = -5; x <= 5; x++) for (let z = -5; z <= 5; z++) {
    if (x*x + z*z > 30) continue;
    put(x, z, 0, 'grass');
    if ((x + z) % 3 !== 0) put(x, z, -1, 'stone');
  }
  for (let x = -2; x <= 2; x++) for (let z = -2; z <= 2; z++) {
    put(x, z, 1, 'quartz');
    for (let y = 2; y <= 4; y++) {
      if (Math.abs(x) === 2 && Math.abs(z) === 2) put(x, z, y, 'quartz');
      else if (Math.abs(x) === 2 || Math.abs(z) === 2) put(x, z, y, 'glass');
    }
    put(x, z, 5, 'copper');
    if (Math.abs(x) <= 1 && Math.abs(z) <= 1) put(x, z, 6, 'copper');
  }
  put(0, 0, 7, 'copper');
  for (let y = 1; y <= 3; y++) put(-4, 1, y, y === 1 ? 'copper' : 'grass');
  for (let y = -3; y <= 0; y++) put(3, 3, y, 'glass');
  const tones = {
    grass: ['chart1', 'chart2', 'chart2'], stone: ['chart3', 'surfaceRaised', 'border'],
    quartz: ['text', 'chart6', 'textMuted'], copper: ['accent', 'chart4', 'warning'],
    glass: ['info', 'chart5', 'chart5'],
  };
  for (const { x, z, y, kind } of [...blocks.values()].sort((a, b) => (a.x + a.z - b.x - b.z) || a.y - b.y)) {
    const [top, left, right] = tones[kind];
    if (!blocks.has(`${x},${z + 1},${y}`)) polygon([p(x,z+1,y),p(x+1,z+1,y),p(x+1,z+1,y+1),p(x,z+1,y+1)], left);
    if (!blocks.has(`${x + 1},${z},${y}`)) polygon([p(x+1,z,y),p(x+1,z+1,y),p(x+1,z+1,y+1),p(x+1,z,y+1)], right);
    if (!blocks.has(`${x},${z},${y + 1}`)) polygon([p(x,z,y+1),p(x+1,z,y+1),p(x+1,z+1,y+1),p(x,z+1,y+1)], top);
  }
  if (ghost) {
    const corners = [[-6,-6,-2],[6,-6,-2],[6,6,-2],[-6,6,-2]];
    const ring = corners.map(([x,z,y]) => p(x,z,y));
    const roof = corners.map(([x,z]) => p(x,z,8));
    line('M' + ring.join('L') + 'Z', 'info', .55);
    line('M' + roof.join('L') + 'Z', 'info', .55);
    for (let i = 0; i < 4; i++) line(`M${ring[i]}L${roof[i]}`, 'info', .55);
  }
  return f.coordinates(nodes, { width: 'fill', aspect: H / W,
    label: ghost ? 'Outlined hologram of a copper-roofed block observatory' : 'Isometric block observatory on a floating garden island' });
}
export function stage(f, { number, title, detail, mode, tone = 'accent' }) {
  return f.stack([f.eyebrow(number, { tone }), artwork(f, mode), f.heading(title),
    f.caption(detail, { tone: 'textMuted' })], { width: 'fill', gap: 8 });
}
export function workshop(f, title, subtitle, children, note) {
  const items = [f.eyebrow(subtitle, { tone: 'accent' }), f.title(title),
    f.flow(children, { width: 'fill', gap: { wide: 30, compact: 24, narrow: 26 } })];
  if (note) items.push(f.rule({ tone: 'border' }), f.caption(note));
  f.root(f.stack(items, { width: 'fill', gap: 20, padding: { wide: 30, compact: 24, narrow: 18 } }));
}
