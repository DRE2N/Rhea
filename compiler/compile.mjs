import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {chromium} from 'playwright';
import {PNG} from 'pngjs';
import {marked} from 'marked';

// Inputs and limits
const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, item, i, list) => {
  if (item.startsWith('--')) pairs.push([item.slice(2), list[i + 1]]);
  return pairs;
}, []));
const source = path.resolve(args.source || '.');
const output = path.resolve(args.output || 'build/rhea');

if (output === source || source.startsWith(output + path.sep) ||
    output === process.cwd() || process.cwd().startsWith(output + path.sep) ||
    output === path.parse(output).root)
  throw new Error('Output must not replace the source, working directory, or a parent directory');

const manifest = JSON.parse(fs.readFileSync(path.join(source, 'rhea.json'), 'utf8'));
const namespace = manifest.namespace || 'rhea';
const fontId = manifest.fontId || 'ui';
const width = manifest.width;
const height = manifest.height;
const rowHeight = 9;
const renderScale = manifest.renderScale ?? 4;
const bodyFontSize = manifest.bodyFontSize ?? 16;
const bodyFontOversample = manifest.bodyFontOversample ?? 4;
const packVersion = manifest.packVersion ?? [97, 1];

// The atlas has a 256px glyph limit. Keep a little room and convert that
// limit back into dialog pixels before slicing the screenshot.
const maxGlyphWidth = Math.floor(240 / renderScale);
if (!/^[a-z0-9_.-]+$/.test(namespace) || !/^[a-z0-9_.-]+$/.test(fontId) ||
    !Number.isInteger(width) || width < 1 || width > 1000 ||
    !Number.isInteger(height) || height < 1 || height > 720 || height % rowHeight !== 0 ||
    !Number.isInteger(renderScale) || renderScale < 1 || renderScale > 6 ||
    !Number.isInteger(bodyFontSize) || bodyFontSize < 12 || bodyFontSize > 24 ||
    ![2, 4].includes(bodyFontOversample) ||
    !Array.isArray(packVersion) || packVersion.length !== 2 ||
    !packVersion.every(value => Number.isInteger(value) && value >= 0)) {
  throw new Error('Invalid namespace, canvas dimensions, or renderScale; height must be divisible by 9');
}
if (!Array.isArray(manifest.pages) || manifest.pages.length === 0) throw new Error('No pages declared');

// Output directories and shared glyph state
fs.rmSync(output, {recursive: true, force: true});
const pack = path.join(output, 'pack');
const textureDir = path.join(pack, 'assets', namespace, 'textures', 'font');
const fontDir = path.join(pack, 'assets', namespace, 'font');
const templateDir = path.join(output, 'templates');
const previewDir = path.join(output, 'previews');
for (const dir of [textureDir, fontDir, templateDir, previewDir]) fs.mkdirSync(dir, {recursive: true});

// Texture glyphs are placed in the Private Use Area. Stable page order gives stable IDs.
let nextCodepoint = 0xE000;
const providers = [];
const pageIds = new Set();
const fontShifts = new Set();
const slotFonts = new Map();

// Render each page with the same local-asset policy.
const browser = await chromium.launch({headless: true});
try {
  const context = await browser.newContext({viewport: {width, height}, deviceScaleFactor: renderScale});
  await context.route('**/*', route => {
    const url = route.request().url();
    if (url.startsWith('file://')) {
      const assetPath = fileURLToPath(url);
      if (assetPath.startsWith(source + path.sep)) route.fulfill({path: assetPath});
      else route.abort();
    } else if (url.startsWith('data:')) route.continue();
    else route.abort();
  });
  const page = await context.newPage();

  for (const definition of manifest.pages) {
    // Load page content and apply build-time labels and Markdown.
    const failedAssets = [];
    const onRequestFailed = request => failedAssets.push(`${request.url()}: ${request.failure()?.errorText || 'failed'}`);
    page.on('requestfailed', onRequestFailed);
    const id = definition.id;
    if (!/^[a-z0-9_-]+$/.test(id) || pageIds.has(id)) throw new Error(`Invalid or duplicate page id: ${id}`);
    pageIds.add(id);
    const htmlPath = path.resolve(source, definition.html || manifest.html || 'index.html');
    const markdownPath = definition.markdown ? path.resolve(source, definition.markdown) : null;
    if (!htmlPath.startsWith(source + path.sep) ||
        (markdownPath && !markdownPath.startsWith(path.resolve(source, '..') + path.sep)))
      throw new Error(`Page ${id} escapes source project`);
    const htmlSource = fs.readFileSync(htmlPath, 'utf8');
    if (/<script\b/i.test(htmlSource) || /\bon\w+\s*=/i.test(htmlSource)) throw new Error(`Scripts are unsupported: ${id}`);
    const markdown = markdownPath ? fs.readFileSync(markdownPath, 'utf8') : null;
    const selected = selectedKeys(definition);
    await page.goto(pathToFileURL(htmlPath).href);
    await page.evaluate(({definition, selected, article}) => {
      document.documentElement.lang = definition.locale;
      const canvas = document.querySelector('#rhea-page');
      if (!canvas) throw new Error('Missing #rhea-page');
      canvas.setAttribute('data-rhea-selected', selected.join(' '));
      if (definition.chapter !== undefined) canvas.dataset.chapter = definition.chapter;
      document.querySelectorAll('[data-label]').forEach(node => {
        const key = node.getAttribute('data-label');
        if (!(key in (definition.labels || {}))) throw new Error(`Missing label: ${key}`);
        node.textContent = definition.labels[key];
      });
      canvas.querySelectorAll('[data-rhea-selected], [data-chapter]').forEach(node => {
        const keys = (node.getAttribute('data-rhea-selected') ?? node.getAttribute('data-chapter')).trim().split(/\s+/);
        node.classList.toggle('selected', keys.some(key => selected.includes(key)));
      });
      if (article !== null) {
        const slot = document.querySelector('[data-rhea-slot="article"]');
        if (!slot) throw new Error('Markdown preview requires an article slot');
        slot.innerHTML = article;
      }
    }, {definition, selected, article: markdown === null ? null : marked.parse(markdown.replace(/^# .+\r?\n/, '')
      .replaceAll('{{player}}', definition.locale === 'de' ? 'Reisende' : 'Traveller')
      .replaceAll('<', '&lt;'), {async: false})});
    await page.evaluate(() => document.fonts.ready);
    if (failedAssets.length) throw new Error(`${id}: missing or blocked assets: ${failedAssets.join('; ')}`);
    page.off('requestfailed', onRequestFailed);

    // Capture click targets and live-text placement from the browser layout.
    const geometry = await page.evaluate(() => {
      const canvas = document.getElementById('rhea-page');
      if (!canvas) throw new Error('Missing #rhea-page');
      const base = canvas.getBoundingClientRect();

      const rect = node => {
        const r = node.getBoundingClientRect();
        // Browser geometry can be fractional; Minecraft hit boxes use whole dialog pixels.
        return {x: Math.round(r.x - base.x), y: Math.round(r.y - base.y), width: Math.round(r.width), height: Math.round(r.height)};
      };

      const fontShift = node => {
        if (node.hasAttribute('data-rhea-font-shift')) return Number(node.getAttribute('data-rhea-font-shift'));
        const walker = document.createTreeWalker(node, NodeFilter.SHOW_TEXT);
        let textNode;
        while ((textNode = walker.nextNode()) && !textNode.textContent.trim()) { }
        let sample = false;
        if (!textNode) {
          textNode = document.createTextNode('Hg');
          node.append(textNode);
          sample = true;
        }

        try {
          const range = document.createRange();
          range.selectNodeContents(textNode);
          const actualTop = range.getBoundingClientRect().top - node.getBoundingClientRect().top;
          const style = getComputedStyle(textNode.parentElement);
          const probe = document.createElement('div');
          probe.style.cssText = 'position:fixed;left:0;top:0;visibility:hidden;white-space:nowrap';
          probe.style.font = style.font;
          probe.style.lineHeight = style.lineHeight;
          probe.textContent = 'Hg';
          document.body.append(probe);

          try {
            const reference = document.createRange();
            reference.selectNodeContents(probe.firstChild);
            // Subtract the font's ordinary line-box offset. What remains is
            // placement from CSS (padding, flex alignment, relative top, etc.).
            const normalTop = reference.getBoundingClientRect().top - probe.getBoundingClientRect().top;
            return Math.round(actualTop - normalTop);
          } finally {
            probe.remove();
          }
        } finally {
          if (sample) textNode.remove();
        }
      };

      const fontStyle = node => {
        const style = getComputedStyle(node);
        const families = style.fontFamily.match(/"[^"]+"|'[^']+'|[^,]+/g)
          .map(value => value.trim().replace(/^['"]|['"]$/g, '').toLowerCase());
        const cssWeight = value => value === 'bold' ? 700 : value === 'normal' || !value ? 400 : Number(value);
        const weight = cssWeight(style.fontWeight);
        const faces = [...document.styleSheets].flatMap(sheet => [...sheet.cssRules]
          .filter(rule => rule.type === CSSRule.FONT_FACE_RULE)
          .map(rule => ({rule, href: sheet.href})));
        const face = families.flatMap(family => faces.filter(({rule}) =>
          rule.style.fontFamily.replace(/^['"]|['"]$/g, '').toLowerCase() === family))
          .find(({rule}) => {
            const weights = (rule.style.fontWeight || '400').split(/\s+/).map(cssWeight);
            const matchingWeight = weights.length === 1 ? weights[0] === weight : weights[0] <= weight && weight <= weights[1];
            return matchingWeight && (rule.style.fontStyle || 'normal') === style.fontStyle;
          });
        if (!face) throw new Error(`No local @font-face matches ${style.fontFamily} weight ${weight}`);
        const sources = [...face.rule.style.getPropertyValue('src').matchAll(/url\(["']?([^"')]+)["']?\)/g)]
          .map(match => match[1]);
        const fontFile = sources.find(value => /\.ttf(?:[?#].*)?$/i.test(value));
        if (!fontFile) throw new Error(`Live text needs a local TTF in @font-face for ${style.fontFamily}`);
        const size = Number.parseFloat(style.fontSize);
        const lineHeight = Number.parseFloat(style.lineHeight);
        if (!Number.isFinite(size))
          throw new Error(`Use a pixel font-size on live text slot ${node.dataset.rheaSlot}`);
        return {fontUrl: new URL(fontFile, face.href || document.baseURI).href, fontSize: size,
          lineHeight: Math.round(Number.isFinite(lineHeight) ? lineHeight : size * 1.2)};
      };

      return {
        canvas: rect(canvas),
        actions: [...document.querySelectorAll('[data-rhea-action]')].map(node => ({
          id: node.getAttribute('data-rhea-action'), ...rect(node)
        })),
        slots: [...document.querySelectorAll('[data-rhea-slot]')].map(node => ({
          id: node.getAttribute('data-rhea-slot'), align: node.getAttribute('data-rhea-align') || 'left',
          fontShift: fontShift(node), ...fontStyle(node), ...rect(node)
        }))
      };
    });
    validateGeometry(id, geometry, width, height);

    // Register the exact CSS font used by each live slot.
    for (const slot of geometry.slots) {
      if (!slot.fontUrl.startsWith('file://')) throw new Error(`${id}: slot ${slot.id} needs a local @font-face`);
      const fontSource = fileURLToPath(slot.fontUrl);
      if (!fontSource.startsWith(source + path.sep) || !fs.existsSync(fontSource))
        throw new Error(`${id}: font for ${slot.id} must be an existing file inside the source folder`);
      const key = createHash('sha256').update(fs.readFileSync(fontSource)).update(`:${slot.fontSize}:${bodyFontOversample}`)
        .digest('hex').slice(0, 12);
      slot.font = `slot_${key}`;
      slot.fontFile = `slot_${key}${path.extname(fontSource).toLowerCase()}`;
      slot.fontSize = Number(slot.fontSize);
      slot.fontOversample = bodyFontOversample;
      delete slot.fontUrl;
      if (!slotFonts.has(key)) slotFonts.set(key, {source: fontSource, size: slot.fontSize});
      // The runtime draws positive shifts on a later bitmap row so later
      // background strips cannot paint over the lower half of the text.
      const renderShift = slot.fontShift - Math.max(0, Math.ceil(slot.fontShift / rowHeight)) * rowHeight;
      if (renderShift !== 0) fontShifts.add(`${key}:${renderShift}`);
    }

    // Save the readable preview, then blank live text for the pack artwork.
    const preview = await page.locator('#rhea-page').screenshot({animations: 'disabled'});
    fs.writeFileSync(path.join(previewDir, `${id}.png`), preview);
    await page.evaluate(() => { document.querySelectorAll('[data-rhea-slot]').forEach(node => node.innerHTML = ''); });
    const bitmap = PNG.sync.read(await page.locator('#rhea-page').screenshot({animations: 'disabled'}));
    if (bitmap.width !== width * renderScale || bitmap.height !== height * renderScale)
      throw new Error(`Unexpected screenshot dimensions for ${id}`);

    // Slice nine-pixel rows at action edges to preserve click hit boxes.
    const rows = [];
    for (let y = 0; y < height; y += rowHeight) {
      // One bitmap-font line is nine pixels high. A glyph can have only one
      // click event, so action edges must become slice boundaries.
      const active = geometry.actions.filter(a => a.y < y + rowHeight && a.y + a.height > y).sort((a, b) => a.x - b.x);
      for (let i = 1; i < active.length; i++) {
        if (active[i].x < active[i - 1].x + active[i - 1].width)
          throw new Error(`${id}: actions ${active[i - 1].id} and ${active[i].id} share glyph row ${y}; increase vertical spacing`);
      }
      const boundaries = [0, ...active.flatMap(a => [a.x, a.x + a.width]), width]
        .sort((a, b) => a - b);
      const segments = [];
      for (let b = 0; b < boundaries.length - 1; b++) {
        const start = boundaries[b];
        const boundary = boundaries[b + 1];
        if (boundary <= start) continue;
        const action = active.find(a => a.x <= start && a.x + a.width >= boundary);
        for (let x = start; x < boundary; x += maxGlyphWidth) {
          const end = Math.min(boundary, x + maxGlyphWidth);
          if (nextCodepoint === 0xF000) nextCodepoint = 0xF018; // spacing glyphs use F000–F017
          const codepoint = nextCodepoint++;
          if (codepoint > 0xF8FF) throw new Error('Too many glyphs for the reserved Private Use Area');
          const filename = `g_${codepoint.toString(16)}.png`;
          const pixelWidth = (end - x) * renderScale;
          const piece = new PNG({width: pixelWidth, height: rowHeight * renderScale});
          // Copy this logical slice out of Chromium's scaled RGBA screenshot.
          for (let py = 0; py < rowHeight * renderScale; py++) {
            const from = (((y * renderScale) + py) * bitmap.width + x * renderScale) * 4;
            bitmap.data.copy(piece.data, py * pixelWidth * 4, from, from + pixelWidth * 4);
          }
          fs.writeFileSync(path.join(textureDir, filename), PNG.sync.write(piece));
          // Seven-pixel ascent puts the nine-pixel strip on the dialog text baseline.
          providers.push({type: 'bitmap', file: `${namespace}:font/${filename}`, ascent: 7, height: rowHeight,
            chars: [String.fromCodePoint(codepoint)]});
          segments.push({glyph: String.fromCodePoint(codepoint), action: action?.id || null});
        }
      }
      rows.push(segments);
    }

    fs.writeFileSync(path.join(templateDir, `${id}.json`), JSON.stringify({
      id, namespace, font: `${namespace}:${fontId}`, width, height, rows, slots: geometry.slots,
      actions: geometry.actions.map(a => a.id)
    }));
    console.log(`Compiled ${id}: ${rows.length} rows, ${geometry.actions.length} actions`);
  }

  // Shared bitmap font and spacing providers
  // The bitmap font adds one pixel of advance after each segment.
  // Space-provider advances undo the one-pixel bitmap padding and place live text.
  const spacing = {'\uF000': -1, '\uF001': -512, '\uF002': -256,
    '\uF003': -128, '\uF004': -64, '\uF005': -32, '\uF006': -16,
    '\uF007': -8, '\uF008': -4, '\uF009': -2, '\uF00A': 1,
    '\uF00B': 2, '\uF00C': 4, '\uF00D': 8, '\uF00E': 16,
    '\uF00F': 32, '\uF010': 64, '\uF011': 128, '\uF012': 256,
    '\uF013': 512, '\uF014': -0.5, '\uF015': 0.5,
    '\uF016': -0.25, '\uF017': 0.25};
  providers.unshift({type: 'space', advances: spacing});
  fs.writeFileSync(path.join(fontDir, `${fontId}.json`), JSON.stringify({providers}));

  // Legacy body font and fonts discovered from live slots
  if (manifest.bodyFont) {
    const fontSource = path.resolve(source, manifest.bodyFont);
    const fontTarget = path.join(pack, 'assets', namespace, 'font', 'body.ttf');
    fs.copyFileSync(fontSource, fontTarget);
    if (manifest.bodyFontLicense) {
      fs.mkdirSync(path.join(pack, 'LICENSES'), {recursive: true});
      fs.copyFileSync(path.resolve(source, manifest.bodyFontLicense),
        path.join(pack, 'LICENSES', 'OFL-body-font.txt'));
    }
    const bodyProvider = shift => JSON.stringify({providers: [{type: 'ttf', file: `${namespace}:body.ttf`,
      shift: [0, shift], size: bodyFontSize, oversample: bodyFontOversample}]});
    fs.writeFileSync(path.join(fontDir, 'body.json'), bodyProvider(0));
  }

  for (const [key, entry] of slotFonts) {
    const name = `slot_${key}`;
    const filename = `${name}${path.extname(entry.source).toLowerCase()}`;
    fs.copyFileSync(entry.source, path.join(fontDir, filename));
    const runtimeDir = path.join(templateDir, 'fonts');
    fs.mkdirSync(runtimeDir, {recursive: true});
    fs.copyFileSync(entry.source, path.join(runtimeDir, filename));
    const provider = shift => JSON.stringify({providers: [{type: 'ttf', file: `${namespace}:${filename}`,
      shift: [0, shift], size: entry.size, oversample: bodyFontOversample}]});
    fs.writeFileSync(path.join(fontDir, `${name}.json`), provider(0));
    for (const item of fontShifts) {
      const [fontKey, value] = item.split(':');
      if (fontKey !== key) continue;
      const shift = Number(value);
      const suffix = shift > 0 ? `p${shift}` : `m${-shift}`;
      fs.writeFileSync(path.join(fontDir, `${name}_shift_${suffix}.json`), provider(shift));
    }
  }

  // Pack metadata
  fs.writeFileSync(path.join(output, 'pack.mcmeta'), JSON.stringify({pack: {
    description: manifest.packDescription || 'Rhea dialog assets', min_format: packVersion, max_format: packVersion
  }}));
  fs.writeFileSync(path.join(output, 'manifest.json'), JSON.stringify({namespace, renderScale, packVersion, pages: [...pageIds]}));
} finally {
  await browser.close();
}

function selectedKeys(definition) {
  // Old pages used chapter for the same one-of-many choice.
  const value = definition.selected ?? definition.chapter;
  const keys = value == null ? [] : Array.isArray(value) ? value : [value];
  if (!keys.every(key => typeof key === 'string' && key.length > 0 && !/\s/.test(key)))
    throw new Error(`${definition.id}: selected must be a string or an array of non-empty keys without spaces`);
  return [...new Set(keys)];
}

// Reject geometry that cannot map cleanly to the dialog's glyph rows.
function validateGeometry(id, geometry, width, height) {
  if (geometry.canvas.width !== width || geometry.canvas.height !== height) throw new Error(`${id}: canvas mismatch`);
  const entries = [...geometry.actions, ...geometry.slots];
  const ids = new Set();
  for (const item of entries) {
    if (geometry.slots.includes(item) && !['left', 'center', 'right'].includes(item.align))
      throw new Error(`${id}: unsupported slot alignment ${item.align}`);
    if (geometry.slots.includes(item) && !Number.isInteger(item.fontShift))
      throw new Error(`${id}: font shift for ${item.id} must be an integer`);
    if (!/^[a-z0-9:_-]+$/.test(item.id) || ids.has(item.id)) throw new Error(`${id}: invalid/duplicate target ${item.id}`);
    ids.add(item.id);
    if (item.width <= 0 || item.height <= 0)
      throw new Error(`${id}: target ${item.id} has no area`);
    if (item.x < 0 || item.y < 0 || item.x + item.width > width || item.y + item.height > height)
      throw new Error(`${id}: target ${item.id} is clipped`);
  }
  for (let i = 0; i < entries.length; i++) for (let j = i + 1; j < entries.length; j++) {
    const a = entries[i], b = entries[j];
    if (a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y) {
      const action = geometry.actions.includes(a) ? a : geometry.actions.includes(b) ? b : null;
      const slot = action === a ? b : a;
      if (action && geometry.slots.includes(slot) && slot.x >= action.x && slot.y >= action.y &&
          slot.x + slot.width <= action.x + action.width &&
          slot.y + slot.height <= action.y + action.height) {
        slot.action = action.id;
        continue;
      }
      throw new Error(`${id}: overlapping targets ${a.id} and ${b.id}`);
    }
  }
}
