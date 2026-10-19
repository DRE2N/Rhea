import fs from 'node:fs';
import path from 'node:path';
import {PNG} from 'pngjs';

// Load the compiler output and its bitmap font.
const output = path.resolve(process.argv[2] || 'build/rhea');
const readJson = file => JSON.parse(fs.readFileSync(file, 'utf8'));
const {namespace, renderScale = 1, packVersion, pages} = readJson(path.join(output, 'manifest.json'));
const expected = Number(process.argv[3] || 0);
if (!pages.length || (expected && pages.length !== expected)) throw new Error(`Unexpected page count: ${pages.length}`);

const firstPage = readJson(path.join(output, 'templates', `${pages[0]}.json`));
const fontName = firstPage.font.split(':')[1];
const font = readJson(path.join(output, 'pack/assets', namespace, `font/${fontName}.json`));

// Check spacing glyphs and every bitmap referenced by the font.
const spacing = font.providers.find(p => p.type === 'space')?.advances;
if (!spacing || spacing['\uF014'] !== -0.5 || spacing['\uF015'] !== 0.5 ||
    spacing['\uF016'] !== -0.25 || spacing['\uF017'] !== 0.25)
  throw new Error('Fractional spacing glyphs are missing');

const glyphs = new Map();
for (const provider of font.providers.filter(p => p.type === 'bitmap')) {
  const char = provider.chars[0];
  if (glyphs.has(char)) throw new Error(`Duplicate glyph ${char}`);
  const filename = path.join(output, 'pack/assets', namespace, 'textures', provider.file.split(':')[1]);
  const png = PNG.sync.read(fs.readFileSync(filename));
  if (png.height !== 9 * renderScale || png.width < 1 || png.width > 240 || png.width % renderScale !== 0)
    throw new Error(`Invalid glyph image ${filename}: ${png.width}×${png.height}`);
  glyphs.set(char, png.width / renderScale);
}

// Check page targets, live fonts, and the width of each glyph row.
for (const id of pages) {
  const page = readJson(path.join(output, 'templates', `${id}.json`));
  if (page.rows.length * 9 !== page.height) throw new Error(`${id}: row count mismatch`);
  if (new Set(page.actions).size !== page.actions.length || new Set(page.slots.map(s => s.id)).size !== page.slots.length)
    throw new Error(`${id}: duplicate targets`);

  for (const slot of page.slots) {
    if (slot.action && !page.actions.includes(slot.action))
      throw new Error(`${id}: slot ${slot.id} references unknown action ${slot.action}`);
    if (slot.font) {
      const font = readJson(path.join(output, 'pack/assets', namespace, `font/${slot.font}.json`));
      if (font.providers[0]?.size !== slot.fontSize || font.providers[0]?.oversample !== slot.fontOversample)
        throw new Error(`${id}: slot ${slot.id} has no matching font`);
      if (!fs.existsSync(path.join(output, 'templates/fonts', slot.fontFile)))
        throw new Error(`${id}: slot ${slot.id} has no runtime font`);
    }
    if (slot.fontShift) {
      const renderShift = slot.fontShift - Math.max(0, Math.ceil(slot.fontShift / 9)) * 9;
      if (renderShift === 0) continue;
      const suffix = renderShift > 0 ? `p${renderShift}` : `m${-renderShift}`;
      const shifted = readJson(path.join(output, 'pack/assets', namespace,
        `font/${slot.font || 'body'}_shift_${suffix}.json`));
      if (shifted.providers[0]?.shift?.[1] !== renderShift)
        throw new Error(`${id}: slot ${slot.id} has no matching shifted font`);
    }
  }

  for (const row of page.rows) {
    const total = row.reduce((sum, item) => {
      if (!glyphs.has(item.glyph)) throw new Error(`${id}: missing glyph`);
      if (item.action && !page.actions.includes(item.action)) throw new Error(`${id}: unknown action`);
      return sum + glyphs.get(item.glyph);
    }, 0);
    if (total !== page.width) throw new Error(`${id}: row width ${total} differs from ${page.width}`);
  }
}

// The standalone pack must declare the same version as the manifest.
const pack = readJson(path.join(output, 'pack.mcmeta'));
if (JSON.stringify(pack.pack.min_format) !== JSON.stringify(packVersion) ||
    JSON.stringify(pack.pack.max_format) !== JSON.stringify(packVersion))
  throw new Error('Pack format does not match the manifest');
console.log(`Verified ${pages.length} pages and ${glyphs.size} glyphs`);
