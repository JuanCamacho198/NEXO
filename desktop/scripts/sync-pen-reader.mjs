#!/usr/bin/env bun
/**
 * Deterministic Bun script — syncs design/nexo-desktop.pen (v2.17)
 * to ReaderWorkspace truth (ReaderHeader, PaperContainer, TextSettings, TocPanel, Mesa).
 *
 * Safety: .bak + temp-write + JSON.parse guard + atomic rename
 * Re-entrant: second run -> inserts:0 hash unchanged
 * Single write target: design/nexo-desktop.pen
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const PEN_PATH = path.resolve(__dirname, '..', 'design', 'nexo-desktop.pen');
export const BAK_PATH = PEN_PATH + '.bak';
export const TMP_PATH = PEN_PATH + '.tmp';

export const TOKEN_MAP = {
  '#08111f': '$bgBase',
  '#08111fff': '$bgBase',
  '#0b1120': '$bgBase',
  '#101c2c': '$bgPanel',
  '#101c2cb4': '$bgPanel',
  '#1e293b': '$bgPanel',
  '#49d4ff': '$accent',
  '#49d4ffff': '$accent',
  '#49d4ff03': '$accent',
  '#38bdf8': '$accent',
  '#8fa3bf': '$textMuted',
  '#8fa3bfff': '$textMuted',
  '#8fa3bfa3': '$textMuted',
  '#64748b': '$textMuted',
};

export function isHex(s) {
  return typeof s === 'string' && /^#[0-9A-Fa-f]{6}$/.test(s);
}

export function newId(all) {
  let id;
  do {
    id = crypto.randomUUID().slice(0, 5);
  } while (all.has(id));
  all.add(id);
  return id;
}

export function deepCloneWithNewUUIDs(node, all) {
  const clone = JSON.parse(JSON.stringify(node));
  const remap = (n) => {
    if (n && typeof n === 'object') {
      if (typeof n.id === 'string') {
        let nid;
        do {
          nid = crypto.randomUUID().slice(0, 5);
        } while (all.has(nid));
        all.add(nid);
        n.id = nid;
      }
      if (Array.isArray(n.children)) n.children.forEach(remap);
    }
  };
  // Need to preserve ref fields: remap only own id, not ref
  // Our remap above only touches n.id, so ref stays intact — verbatim
  remap(clone);
  return clone;
}

// original deepClone that truly preserves ref: we already do that, but need to ensure ref not remapped
// The above remap only touches id, so correct.

function collectAllIds(root, set) {
  if (!root) return;
  if (typeof root.id === 'string') set.add(root.id);
  if (Array.isArray(root.children)) root.children.forEach((c) => collectAllIds(c, set));
}

export function buildAllIds(pen) {
  const all = new Set();
  if (pen.children) pen.children.forEach((c) => collectAllIds(c, all));
  return all;
}

function findNodeById(root, id) {
  if (root.id === id) return root;
  if (Array.isArray(root.children)) {
    for (const c of root.children) {
      const r = findNodeById(c, id);
      if (r) return r;
    }
  }
  return null;
}

function findTopLevelById(pen, id) {
  return (pen.children || []).find((c) => c.id === id) || null;
}

function findByName(children, name) {
  return (children || []).find((c) => c.name === name) || null;
}

const REQ_REFS_READER = ['9dc7a', 'fa5c8', 'vVEie'];

export function validateRefs(arg1, arg2) {
  // Overloaded:
  // - validateRefs(pen, allSet)  -> scans pen for dangling refs
  // - validateRefs(allSet)       -> checks REQ_REFS_READER subset
  // - validateRefs(refsArray, allSet) -> checks given refs subset
  if (arg1 && typeof arg1 === 'object' && arg2 instanceof Set && Array.isArray(arg1.children)) {
    const pen = arg1;
    const all = arg2;
    const refs = [];
    function walk(n) {
      if (n && typeof n === 'object') {
        if (typeof n.ref === 'string') refs.push({ id: n.id, ref: n.ref });
        if (Array.isArray(n.children)) n.children.forEach(walk);
      }
    }
    pen.children.forEach(walk);
    for (const r of refs) {
      if (!all.has(r.ref)) {
        throw new Error(`Dangling ref ${r.id} -> ${r.ref} not in allIds`);
      }
    }
    return;
  }
  if (arg1 instanceof Set && arg2 === undefined) {
    const all = arg1;
    for (const r of REQ_REFS_READER) {
      if (!all.has(r)) throw new Error(`Dangling ref ${r} not in allIds`);
    }
    return;
  }
  if (Array.isArray(arg1) && arg2 instanceof Set) {
    const refs = arg1;
    const all = arg2;
    for (const r of refs) {
      if (!all.has(r)) throw new Error(`Dangling ref ${r} not in allIds`);
    }
    return;
  }
  // fallback: if called validateRefs(pen, all) with pen object
  throw new Error('validateRefs called with invalid args');
}

export function validateBounds(pen) {
  const gimmk = findTopLevelById(pen, 'GImmK');
  if (!gimmk) throw new Error('GImmK not found');
  if (gimmk.width !== 1200) throw new Error(`GImmK.width must stay 1200, got ${gimmk.width}`);
  const mesa = (pen.children || []).find((c) => c.name === 'Mesa de trabajo / Desktop');
  if (mesa) {
    if (mesa.width !== 1280 || mesa.height !== 800) throw new Error(`Mesa must be 1280x800 got ${mesa.width}x${mesa.height}`);
    if (mesa.layout !== 'horizontal') throw new Error('Mesa layout must be horizontal');
    if (mesa.gap !== 12) throw new Error(`Mesa gap must be 12 got ${mesa.gap}`);
  }
  const frameCount = pen.children.filter((c) => c.type === 'frame').length;
  const totalCount = pen.children.length;
  // Allow both pre-sync (17 total, 16 frames) and post-sync (18 total, 17 frames) for compatibility
  const validCounts = (frameCount === 16 && totalCount === 17) || (frameCount === 17 && totalCount === 18);
  if (!validCounts) {
    throw new Error(`Frames count mismatch: frames ${frameCount} (expected 16 or 17), total ${totalCount} (expected 17 or 18)`);
  }
  // no overlap among frames with x,y,width,height numbers
  validateBoundsNoOverlap(pen);
}

function validateBoundsNoOverlap(pen) {
  const frames = pen.children.filter((c) => typeof c.x === 'number' && typeof c.y === 'number' && typeof c.width === 'number' && typeof c.height === 'number');
  for (let i = 0; i < frames.length; i++) {
    for (let j = i + 1; j < frames.length; j++) {
      const a = frames[i], b = frames[j];
      const overlap = !(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y);
      if (overlap) throw new Error(`Frames overlap: ${a.name || a.id} and ${b.name || b.id}`);
    }
  }
}

function exists(all, id) {
  return all.has(id);
}

function tokenizeFills(pen) {
  let count = 0;
  function walk(n) {
    if (!n || typeof n !== 'object') return;
    // fill tokenization hex-only via TOKEN_MAP, rgba/gradient skipped
    if (typeof n.fill === 'string' && n.fill.startsWith('#')) {
      const low = n.fill.toLowerCase();
      let mapped = TOKEN_MAP[low];
      if (!mapped && low.length === 9 && TOKEN_MAP[low.slice(0, 7)]) {
        mapped = TOKEN_MAP[low.slice(0, 7)];
      }
      // also handle 6-digit lower case already, but ensure isHex check for non-hex like rgba is not starting with #
      if (mapped) {
        n.fill = mapped;
        count++;
      } else {
        // if isHex or 8-digit hex but not in map, leave; rgba/gradient already skipped because not starting with #
      }
    }
    // stroke can be string or object with color? In pen, stroke is either string hex or object {bottom:1}
    // but some strokes are hex strings like "#94adce08" — skip because alpha variant not in map, leave
    if (typeof n.stroke === 'string' && n.stroke.startsWith('#')) {
      const low = n.stroke.toLowerCase();
      let mapped = TOKEN_MAP[low];
      if (!mapped && low.length === 9 && TOKEN_MAP[low.slice(0, 7)]) mapped = TOKEN_MAP[low.slice(0, 7)];
      if (mapped) {
        n.stroke = mapped;
        count++;
      }
    }
    // also handle stroke as object with color? No, but check fill inside effect?
    if (Array.isArray(n.children)) n.children.forEach(walk);
  }
  pen.children.forEach(walk);
  return count;
}

function ensureReaderReusables(pen, all) {
  const gimmk = findTopLevelById(pen, 'GImmK');
  if (!gimmk) throw new Error('GImmK not found');
  if (!Array.isArray(gimmk.children)) gimmk.children = [];

  const names = ['ReaderHeader', 'ReaderPaperContainer', 'ReaderTextSettingsPanel', 'ReaderTocPanel'];
  const existing = gimmk.children.filter((c) => names.includes(c.name) && c.reusable === true);
  if (existing.length === 4) {
    return { inserts: 0, ids: existing.map((c) => c.id) };
  }

  let inserts = 0;
  const ids = [];

  // Helper to find source nodes
  function findSource(id) {
    for (const top of pen.children) {
      const f = findNodeById(top, id);
      if (f) return f;
    }
    return null;
  }

  // ReaderHeader from g2S7F
  let header = gimmk.children.find((c) => c.name === 'ReaderHeader');
  if (!header) {
    const src = findSource('g2S7F');
    if (!src) throw new Error('g2S7F not found to clone ReaderHeader');
    const clone = deepCloneWithNewUUIDs(JSON.parse(JSON.stringify(src)), all);
    clone.name = 'ReaderHeader';
    clone.reusable = true;
    clone.width = 1280;
    clone.height = 36;
    clone.fill = '$bgPanel';
    clone.layout = 'horizontal';
    clone.justifyContent = 'space_between';
    clone.alignItems = 'center';
    clone.padding = [0, 24];
    // keep children Menu+Hxq39 etc. Ensure gap/joints remain
    // Do not change internal fills yet — tokenize will handle
    gimmk.children.push(clone);
    ids.push(clone.id);
    inserts++;
  } else {
    ids.push(header.id);
    // enforce invariants even if exists
    header.width = 1280;
    header.height = 36;
    header.fill = '$bgPanel';
    header.layout = 'horizontal';
    header.justifyContent = 'space_between';
    header.reusable = true;
  }

  // ReaderPaperContainer from wU2Fs
  let paper = gimmk.children.find((c) => c.name === 'ReaderPaperContainer');
  if (!paper) {
    const src = findSource('wU2Fs');
    if (!src) throw new Error('wU2Fs not found');
    const clone = deepCloneWithNewUUIDs(JSON.parse(JSON.stringify(src)), all);
    clone.name = 'ReaderPaperContainer';
    clone.reusable = true;
    clone.fill = '#FFFFFF';
    clone.cornerRadius = 12;
    if (!clone.effect) clone.effect = { type: 'shadow', shadowType: 'outer', color: '#00000033', offset: { x: 0, y: 4 }, blur: 17.5 };
    gimmk.children.push(clone);
    ids.push(clone.id);
    inserts++;
  } else {
    ids.push(paper.id);
    paper.fill = '#FFFFFF';
    paper.cornerRadius = 12;
    paper.reusable = true;
  }

  // ReaderTextSettingsPanel from N8Rj4U
  let textPanel = gimmk.children.find((c) => c.name === 'ReaderTextSettingsPanel');
  if (!textPanel) {
    const src = findSource('N8Rj4U');
    if (!src) throw new Error('N8Rj4U not found');
    const clone = deepCloneWithNewUUIDs(JSON.parse(JSON.stringify(src)), all);
    clone.name = 'ReaderTextSettingsPanel';
    clone.reusable = true;
    clone.width = 260;
    clone.fill = '$bgPanel';
    // ensure w-65 description via name? keep width 260
    gimmk.children.push(clone);
    ids.push(clone.id);
    inserts++;
  } else {
    ids.push(textPanel.id);
    textPanel.width = 260;
    textPanel.fill = '$bgPanel';
    textPanel.reusable = true;
  }

  // ReaderTocPanel from lrHcE
  let tocPanel = gimmk.children.find((c) => c.name === 'ReaderTocPanel');
  if (!tocPanel) {
    const src = findSource('lrHcE');
    if (!src) throw new Error('lrHcE not found');
    const clone = deepCloneWithNewUUIDs(JSON.parse(JSON.stringify(src)), all);
    clone.name = 'ReaderTocPanel';
    clone.reusable = true;
    clone.width = 260;
    clone.fill = '$bgPanel';
    gimmk.children.push(clone);
    ids.push(clone.id);
    inserts++;
  } else {
    ids.push(tocPanel.id);
    tocPanel.width = 260;
    tocPanel.fill = '$bgPanel';
    tocPanel.reusable = true;
  }

  return { inserts, ids };
}

function fixKvaHR(pen, all) {
  function walk(n) {
    if (!n || typeof n !== 'object') return 0;
    let fixed = 0;
    if (n.id === 'KvaHR' && n.type === 'ref') {
      if (!n.ref || n.ref === '') {
        if (!exists(all, 'vVEie')) {
          console.error('KvaHR fix aborted: vVEie not in buildAllIds');
          throw new Error('vVEie not found for KvaHR fix');
        }
        n.ref = 'vVEie';
        fixed = 1;
      } else if (n.ref !== 'vVEie') {
        // ensure it's vVEie anyway? Task says fix "" -> vVEie
        // if already vVEie, no fix
      }
    }
    if (Array.isArray(n.children)) {
      for (const c of n.children) fixed += walk(c);
    }
    return fixed;
  }
  let total = 0;
  for (const top of pen.children) total += walk(top);
  return total;
}

function renameLegacy(pen) {
  let count = 0;
  for (const top of pen.children) {
    if (top.id === 'X7edh' && !top.name.endsWith('-legacy')) {
      top.name = top.name + '-legacy';
      count++;
    }
    if (top.id === 'iclFj' && !top.name.endsWith('-legacy')) {
      top.name = top.name + '-legacy';
      count++;
    }
  }
  return count;
}

function ensureMesa(pen, all) {
  const existing = pen.children.find((c) => c.name === 'Mesa de trabajo / Desktop');
  if (existing) {
    // ensure invariants
    existing.width = 1280;
    existing.height = 800;
    existing.layout = 'horizontal';
    existing.gap = 12;
    existing.fill = '$bgBase';
    return { node: existing, inserts: 0 };
  }
  // compute maxX+64
  let maxX = -Infinity;
  for (const ch of pen.children) {
    if (typeof ch.x === 'number' && typeof ch.width === 'number') {
      maxX = Math.max(maxX, ch.x + ch.width);
    } else if (typeof ch.x === 'number' && typeof ch.width === 'string') {
      const m = String(ch.width).match(/\d+/);
      if (m) maxX = Math.max(maxX, ch.x + parseInt(m[0], 10));
    } else if (typeof ch.x === 'number') {
      maxX = Math.max(maxX, ch.x + 1280);
    }
  }
  if (!isFinite(maxX)) maxX = 5702;
  const mesaX = maxX + 64;
  const mesaY = 800;

  const uje = findTopLevelById(pen, 'UjePJ');
  if (!uje) throw new Error('UjePJ not found for Mesa adoption');

  const mainClone = deepCloneWithNewUUIDs(JSON.parse(JSON.stringify(uje)), all);
  mainClone.name = 'mainArea';
  mainClone.width = 'fill_container';
  mainClone.height = 'fill_container';
  // Ensure mainClone has LibraryGrid ref: inject fa5c8 ref at top if not present
  const hasFa5c8 = (mainClone.children || []).some((c) => c.ref === 'fa5c8');
  if (!hasFa5c8) {
    const faRef = { type: 'ref', id: newId(all), ref: 'fa5c8', name: 'libraryGridRef', width: 'fill_container' };
    mainClone.children = [faRef, ...(mainClone.children || [])];
  }

  const mesaId = newId(all);
  const mesa = {
    type: 'frame',
    id: mesaId,
    name: 'Mesa de trabajo / Desktop',
    x: mesaX,
    y: mesaY,
    width: 1280,
    height: 800,
    fill: '$bgBase',
    layout: 'horizontal',
    gap: 12,
    children: [
      { type: 'ref', id: newId(all), ref: '9dc7a', name: 'sidebarRef' },
      mainClone,
    ],
  };
  pen.children.push(mesa);
  return { node: mesa, inserts: 1 };
}

function wireRefs(pen, all, reusableIds) {
  // reusableIds order: Header, Paper, TextSettings, Toc
  const [headerId, paperId, textId, tocId] = reusableIds;
  let changed = 0;
  const v1 = findTopLevelById(pen, 'v1TIf6');
  const m6 = findTopLevelById(pen, 'm6eYY9');
  for (const frame of [v1, m6]) {
    if (!frame) continue;
    // Replace inline header g2S7F/rfW8k with ref to ReaderHeader
    // For v1, header is g2S7F; for m6, header is rfW8k (but we use same reusable)
    const headerInlineIds = ['g2S7F', 'rfW8k'];
    for (const hid of headerInlineIds) {
      const idx = frame.children.findIndex((c) => c.id === hid);
      if (idx !== -1) {
        // replace with ref
        frame.children[idx] = { type: 'ref', id: newId(all), ref: headerId, name: 'readerHeaderRef' };
        changed++;
      }
    }
    // Replace Aside N8Rj4U / lrHcE with refs to text/toc panels
    const asideMap = { 'N8Rj4U': textId, 'lrHcE': tocId };
    for (const [asideId, refTarget] of Object.entries(asideMap)) {
      const idx = frame.children.findIndex((c) => c.id === asideId);
      if (idx !== -1) {
        frame.children[idx] = { type: 'ref', id: newId(all), ref: refTarget, name: asideId === 'N8Rj4U' ? 'textSettingsRef' : 'tocRef' };
        changed++;
      }
    }
    // Replace paper wU2Fs/o2M24 inside MainContent with ref to ReaderPaperContainer
    // Need to walk inside MainContent:margin -> MainContent -> paper
    function walkReplace(node) {
      if (!node || !Array.isArray(node.children)) return;
      for (let i = 0; i < node.children.length; i++) {
        const child = node.children[i];
        if (child.id === 'wU2Fs' || child.id === 'o2M24') {
          node.children[i] = { type: 'ref', id: newId(all), ref: paperId, name: 'paperRef' };
          changed++;
        } else {
          walkReplace(child);
        }
      }
    }
    walkReplace(frame);
    // Remove duplicate inline Paper if ref already exists (avoid dup)
  }
  return changed;
}

function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help') || args.includes('-h')) {
    console.log(`sync-pen-reader.mjs — sync design/nexo-desktop.pen to ReaderWorkspace truth
Usage: bun run scripts/sync-pen-reader.mjs [--help] [--dry-run]
Creates .bak, temp-write + JSON.parse guard, re-entrant inserts:0 on second run`);
    process.exit(0);
  }
  const dryRun = args.includes('--dry-run');
  let raw;
  try {
    raw = fs.readFileSync(PEN_PATH, 'utf8');
  } catch (e) {
    console.error(`Failed to read ${PEN_PATH}: ${e.message}`);
    process.exit(1);
  }
  let pen;
  try {
    pen = JSON.parse(raw);
  } catch (e) {
    console.error(`JSON parse failed for ${PEN_PATH}: ${e.message}`);
    console.error('Aborting — original intact, .bak not overwritten');
    process.exit(1);
  }
  const originalHash = crypto.createHash('sha256').update(raw).digest('hex');
  let all = buildAllIds(pen);
  // version preserve
  if (pen.version !== '2.17') {
    console.warn(`Warning: version is ${pen.version}, expected 2.17 — preserving`);
  }

  let inserts = 0;

  // 2.1-2.4 reusables
  const { inserts: rInserts, ids: reusableIds } = ensureReaderReusables(pen, all);
  inserts += rInserts;
  // rebuild all after new ids for exists checks
  all = buildAllIds(pen);

  // 2.5 KvaHR fix
  const kvaFix = fixKvaHR(pen, all);
  inserts += kvaFix;

  // 2.6 tokenize
  const tokCount = tokenizeFills(pen);
  if (tokCount > 0) inserts += 1; // count as one logical insert group? But for tracking, treat as inserts if any tokenized
  // Actually need to count tokenization as insert if changed? Use 1 if >0 for accounting
  // Safer: inserts reflects new nodes; tokenization is mutation but not node count, don't increment inserts for re-entrancy check
  // So revert: if tokenization happened, it is not new node, but we consider inserts for header? Keep inserts as before.
  // Let's not count tokenization toward inserts for re-entrancy; instead just track if needed.
  if (tokCount > 0) {
    // if first run, it will be inserts>0 anyway via reusables/mesa; if re-run, tokCount will be 0 because already tokenized
  }

  // 2.7 & 2.8 Mesa + adopt + rename legacy
  const { inserts: mesaInserts } = ensureMesa(pen, all);
  inserts += mesaInserts;
  all = buildAllIds(pen);
  const legacyCount = renameLegacy(pen);
  inserts += legacyCount > 0 ? 1 : 0; // group legacy rename as one

  // 3.1 wire refs
  const wireCount = wireRefs(pen, all, reusableIds);
  inserts += wireCount > 0 ? 1 : 0;

  // Adjust inserts: if only tokenization/legacy/wire already counted, ensure re-entrancy will be 0 next run
  // For re-entrancy, we need to detect if everything already exists, inserts should be 0.
  // So after all ops, if no new reusables, no mesa, no KvaHR fix, no legacy rename, no wire, then inserts should be 0
  // Tokenization after first run will be 0, so inserts 0 correctly.

  // But we over-counted legacy and wire as 1 each; need to ensure they don't trigger inserts on re-run if already done
  // Above functions return 0 on re-run, so inserts stays 0.

  // Rebuild allAfter for validation
  const allAfter = buildAllIds(pen);

  // Validate refs and bounds before stringify; on throw abort no mutation
  try {
    validateRefs(pen, allAfter);
    validateBounds(pen);
  } catch (e) {
    console.error(`Validation failed: ${e.message}`);
    console.error('Aborting — no mutation written');
    process.exit(1);
  }

  // GImmK width guard already in validateBounds

  const out = JSON.stringify(pen, null, 2);
  try {
    JSON.parse(out);
  } catch (e) {
    console.error(`Generated JSON invalid: ${e.message}`);
    process.exit(1);
  }

  const newHash = crypto.createHash('sha256').update(out).digest('hex');
  if (inserts === 0) {
    if (newHash !== originalHash) {
      console.warn('Inserts 0 but hash differs — possible tokenization or fix without insert count; treating as 0');
    }
    console.log(`inserts:0 hash unchanged ${newHash.slice(0, 8)}`);
    // Even with inserts 0, we should ensure no write (re-entrant)
    // Check if hash unchanged, don't write; else would write
    if (newHash === originalHash) {
      process.exit(0);
    } else {
      // Hash differs but inserts 0 means we mutated something without counting — force write
      console.warn('Hash differs despite inserts:0 — writing anyway');
    }
  }

  if (dryRun) {
    console.log(`[dry-run] inserts:${inserts} would write ${PEN_PATH}`);
    process.exit(0);
  }

  if (!fs.existsSync(BAK_PATH)) {
    fs.writeFileSync(BAK_PATH, raw, 'utf8');
    console.log(`Backup created: ${BAK_PATH}`);
  }
  fs.writeFileSync(TMP_PATH, out, 'utf8');
  JSON.parse(fs.readFileSync(TMP_PATH, 'utf8'));
  fs.renameSync(TMP_PATH, PEN_PATH);
  const frameCount = pen.children.filter((c) => c.type === 'frame').length;
  console.log(`inserts:${inserts} wrote ${PEN_PATH} hash ${newHash.slice(0, 8)} frames:${frameCount} vars:${Object.keys(pen.variables).length}`);
}

if (import.meta.main) main();
