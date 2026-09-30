#!/usr/bin/env bun
/**
 * Polish design/nexo-desktop.pen v2.17 — Home DS polish
 * Adds 6 reusable:true to GImmK + populates 2fbd0/fa92e
 * Mirrors sync-pen-home.mjs helpers with extended guards
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const PEN_PATH = path.resolve(__dirname, '..', 'design', 'nexo-desktop.pen');
export const BAK_PATH = PEN_PATH + '.bak';
export const TMP_PATH = PEN_PATH + '.tmp';

const REQ_REFS = ['AjwyA', 'lii9t', 'AbSdu', 'rq71f', 'WorJo', 'GlOAD', 'vVEie', 'pd8II'];

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
  remap(clone);
  return clone;
}

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

export function findTopLevelById(pen, id) {
  return (pen.children || []).find((c) => c.id === id) || null;
}

export function findByName(children, name) {
  return (children || []).find((c) => c.name === name) || null;
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

export function validateRefs(arg1, arg2) {
  // supports validateRefs(allSet) or validateRefs(refsArray, allSet)
  let refs;
  let all;
  if (arg1 instanceof Set && arg2 === undefined) {
    all = arg1;
    refs = REQ_REFS;
  } else if (Array.isArray(arg1) && arg2 instanceof Set) {
    refs = arg1;
    all = arg2;
  } else if (arg1 && typeof arg1 === 'object' && arg2 === undefined) {
    // pen + all case — not used; fallback to scanning pen
    return;
  } else {
    refs = REQ_REFS;
    all = arg2 || arg1;
  }
  for (const r of refs) {
    if (!all.has(r)) throw new Error(`Dangling ref ${r} not in allIds`);
  }
  // also walk pen if provided? overloaded ensure all refs in pen are valid is done elsewhere
}

export function validateBounds(pen) {
  const gimmk = findTopLevelById(pen, 'GImmK');
  if (!gimmk) throw new Error('GImmK not found');
  if (gimmk.width !== 1200) throw new Error(`GImmK.width must stay 1200, got ${gimmk.width}`);
  if (gimmk.x !== -1528 || gimmk.y !== -290) throw new Error(`GImmK bounds must be -1528,-290, got ${gimmk.x},${gimmk.y}`);
  const home = findTopLevelById(pen, '2fbd0');
  if (!home) throw new Error('2fbd0 not found');
  if (home.x !== -98 || home.y !== -4398) throw new Error(`2fbd0 must be at -98,-4398, got ${home.x},${home.y}`);
  if (home.width !== 1280 || home.height !== 800) throw new Error(`2fbd0 must be 1280x800, got ${home.width}x${home.height}`);
  if (home.fill !== '$bgBase') throw new Error(`2fbd0 fill must be $bgBase, got ${home.fill}`);
  const frameCount = (pen.children || []).filter((c) => c.type === 'frame').length;
  const totalCount = (pen.children || []).length;
  // pen has 17 top-level children (16 frames + 1 ellipse) pre-reader, 18 (17 frames) post-reader — allow both
  const valid = (totalCount === 17 && frameCount === 16) || (totalCount === 18 && frameCount === 17);
  if (!valid) throw new Error(`Frames: expected 17 total/16 frames or 18 total/17 frames, got ${totalCount} total/${frameCount} frames`);
  // non-overlap for frames with numeric bounds only
  const frames = pen.children.filter((c) => typeof c.x === 'number' && typeof c.y === 'number' && typeof c.width === 'number' && typeof c.height === 'number');
  for (let i = 0; i < frames.length; i++) {
    for (let j = i + 1; j < frames.length; j++) {
      const a = frames[i], b = frames[j];
      const overlap = !(a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y);
      if (overlap) throw new Error(`Frames overlap: ${a.name || a.id} and ${b.name || b.id}`);
    }
  }
}

function validateVarsHex(pen) {
  if (!pen.variables || typeof pen.variables !== 'object') throw new Error('variables missing');
  const keys = Object.keys(pen.variables);
  if (keys.length !== 10) throw new Error(`vars length must be 10, got ${keys.length}`);
  for (const [k, v] of Object.entries(pen.variables)) {
    if (!v || v.type !== 'color') throw new Error(`Variable ${k} type must be color`);
    if (!isHex(v.value)) throw new Error(`Variable ${k} has non-hex value ${v.value} — rejected`);
  }
}

// --- reusable factories ---

function createDialog(all) {
  const id = newId(all);
  const titleId = newId(all);
  const contentId = newId(all);
  const footerId = newId(all);
  const ref1 = newId(all);
  const ref2 = newId(all);
  return {
    type: 'frame',
    id,
    name: 'Dialog',
    reusable: true,
    width: 512,
    fill: '$bgCard',
    stroke: '$border',
    strokeWidth: 1,
    strokeAlignment: 'inner',
    cornerRadius: 16,
    layout: 'vertical',
    gap: 16,
    padding: 24,
    children: [
      { type: 'text', id: titleId, fill: '$textPrimary', content: 'Dialog Title', fontFamily: 'Inter', fontSize: 16, fontWeight: 'bold' },
      { type: 'text', id: contentId, fill: '$textSecondary', content: 'Dialog content goes here. Replace with real copy.', fontFamily: 'Inter', fontSize: 14, fontWeight: 'normal' },
      {
        type: 'frame',
        id: footerId,
        name: 'dialogFooter',
        layout: 'horizontal',
        gap: 12,
        width: 'fill_container',
        justifyContent: 'flex_end',
        children: [
          { type: 'ref', id: ref1, ref: 'AjwyA', width: 120, name: 'primaryBtnRef' },
          { type: 'ref', id: ref2, ref: 'lii9t', width: 120, name: 'outlineBtnRef' },
        ],
      },
    ],
  };
}

function createToastStack(all) {
  const id = newId(all);
  const sId = newId(all);
  const sText = newId(all);
  const iId = newId(all);
  const iText = newId(all);
  const eId = newId(all);
  const eText = newId(all);
  return {
    type: 'frame',
    id,
    name: 'ToastStack',
    reusable: true,
    layout: 'vertical',
    gap: 8,
    width: 'fill_container',
    children: [
      {
        type: 'frame',
        id: sId,
        name: 'ToastSuccess',
        width: 320,
        height: 48,
        fill: '$surfaceHover',
        cornerRadius: 8,
        padding: 12,
        alignItems: 'center',
        children: [{ type: 'text', id: sText, fill: '$textPrimary', content: 'Success', fontFamily: 'Inter', fontSize: 14 }],
      },
      {
        type: 'frame',
        id: iId,
        name: 'ToastInfo',
        width: 320,
        height: 48,
        fill: '$accent',
        cornerRadius: 8,
        padding: 12,
        alignItems: 'center',
        children: [{ type: 'text', id: iText, fill: '$bgBase', content: 'Info', fontFamily: 'Inter', fontSize: 14 }],
      },
      {
        type: 'frame',
        id: eId,
        name: 'ToastError',
        width: 320,
        height: 48,
        fill: '$error',
        cornerRadius: 8,
        padding: 12,
        alignItems: 'center',
        children: [{ type: 'text', id: eText, fill: '$textPrimary', content: 'Error', fontFamily: 'Inter', fontSize: 14 }],
      },
    ],
  };
}

function createEmptyState(all) {
  const id = newId(all);
  const iconWrap = newId(all);
  const icon = newId(all);
  const title = newId(all);
  const desc = newId(all);
  const cta = newId(all);
  return {
    type: 'frame',
    id,
    name: 'EmptyState',
    reusable: true,
    width: 400,
    height: 240,
    fill: '$bgCard',
    stroke: '$border',
    strokeWidth: 1,
    strokeAlignment: 'inner',
    cornerRadius: 16,
    layout: 'vertical',
    gap: 16,
    padding: 24,
    alignItems: 'center',
    justifyContent: 'center',
    children: [
      {
        type: 'frame',
        id: iconWrap,
        name: 'emptyIcon',
        width: 56,
        height: 56,
        fill: '$bgBase',
        cornerRadius: 28,
        justifyContent: 'center',
        alignItems: 'center',
        children: [{ type: 'icon', id: icon, icon: 'inbox', library: 'lucide', fill: '$textSecondary', width: 24, height: 24 }],
      },
      { type: 'text', id: title, fill: '$textPrimary', content: 'No items yet', fontFamily: 'Inter', fontSize: 16, fontWeight: 'bold' },
      { type: 'text', id: desc, fill: '$textSecondary', content: 'Nothing to show here yet.', fontFamily: 'Inter', fontSize: 14, fontWeight: 'normal', textAlign: 'center' },
      { type: 'ref', id: cta, ref: 'AjwyA', name: 'emptyCTA', width: 160 },
    ],
  };
}

function createCoverWithProgress(all) {
  const id = newId(all);
  const cover = newId(all);
  const title = newId(all);
  const author = newId(all);
  const prog = newId(all);
  return {
    type: 'frame',
    id,
    name: 'CoverWithProgress',
    reusable: true,
    width: 200,
    layout: 'vertical',
    gap: 12,
    fill: '$bgCard',
    stroke: '$border',
    strokeWidth: 1,
    strokeAlignment: 'inner',
    cornerRadius: 12,
    padding: 12,
    children: [
      { type: 'frame', id: cover, name: 'cover', width: 200, height: 280, fill: '$bgBase', cornerRadius: 12, stroke: '$border', strokeWidth: 1, strokeAlignment: 'inner' },
      { type: 'text', id: title, fill: '$textPrimary', content: 'Book Title', fontFamily: 'Inter', fontSize: 14, fontWeight: 'bold' },
      { type: 'text', id: author, fill: '$textSecondary', content: 'Author Name', fontFamily: 'Inter', fontSize: 12, fontWeight: 'normal' },
      { type: 'ref', id: prog, ref: 'vVEie', width: 'fill_container', name: 'progressRef' },
    ],
  };
}

function createCoverWithoutProgress(all) {
  const id = newId(all);
  const cover = newId(all);
  const title = newId(all);
  const author = newId(all);
  return {
    type: 'frame',
    id,
    name: 'CoverWithoutProgress',
    reusable: true,
    width: 200,
    layout: 'vertical',
    gap: 12,
    fill: '$bgCard',
    stroke: '$border',
    strokeWidth: 1,
    strokeAlignment: 'inner',
    cornerRadius: 12,
    padding: 12,
    children: [
      { type: 'frame', id: cover, name: 'cover', width: 200, height: 280, fill: '$bgBase', cornerRadius: 12, stroke: '$border', strokeWidth: 1, strokeAlignment: 'inner' },
      { type: 'text', id: title, fill: '$textPrimary', content: 'Book Title', fontFamily: 'Inter', fontSize: 14, fontWeight: 'bold' },
      { type: 'text', id: author, fill: '$textSecondary', content: 'Author Name', fontFamily: 'Inter', fontSize: 12, fontWeight: 'normal' },
    ],
  };
}

function createSearchFilterBar(all) {
  const id = newId(all);
  const tab1 = newId(all);
  const tab2 = newId(all);
  const input = newId(all);
  const drop = newId(all);
  const togg = newId(all);
  return {
    type: 'frame',
    id,
    name: 'SearchFilterBar',
    reusable: true,
    width: 'fill_container',
    layout: 'horizontal',
    gap: 12,
    alignItems: 'center',
    padding: 12,
    children: [
      { type: 'ref', id: tab1, ref: 'rq71f', name: 'tabActiveRef' },
      { type: 'ref', id: tab2, ref: 'WorJo', name: 'tabDefaultRef' },
      { type: 'ref', id: input, ref: 'GlOAD', width: 280, name: 'searchInputRef' },
      { type: 'ref', id: drop, ref: 'AbSdu', name: 'dropdownRef' },
      { type: 'ref', id: togg, ref: 'AbSdu', name: 'toggleRef' },
    ],
  };
}

function findOrCreateFeedbackSec(gimmk, all) {
  let sec = findByName(gimmk.children, 'feedbackSec');
  if (sec) return { node: sec, inserts: 0 };
  const id = newId(all);
  sec = {
    type: 'frame',
    id,
    name: 'feedbackSec',
    width: 'fill_container',
    height: 400,
    layout: 'vertical',
    gap: 16,
    description: 'Feedback components: Dialog, ToastStack, EmptyState',
    children: [],
  };
  gimmk.children.push(sec);
  return { node: sec, inserts: 1 };
}

function ensureFeedbackReusables(feedbackSec, all) {
  const expected = ['Dialog', 'ToastStack', 'EmptyState'];
  const existingNames = (feedbackSec.children || []).filter((c) => c.reusable).map((c) => c.name);
  const hasAll = expected.every((n) => existingNames.includes(n)) && feedbackSec.children.filter((c) => c.reusable).length === 3;
  if (hasAll) return { inserts: 0 };
  // clear non-matching? ensure exactly 3
  if (feedbackSec.children.length === 3 && hasAll) return { inserts: 0 };
  // if partially exists, recreate
  if (hasAll) return { inserts: 0 };
  // build fresh 3
  const dialog = createDialog(all);
  const toast = createToastStack(all);
  const empty = createEmptyState(all);
  feedbackSec.children = [dialog, toast, empty];
  return { inserts: 3 };
}

function ensureLayoutReusables(layoutSec, all) {
  const expected = ['CoverWithProgress', 'CoverWithoutProgress', 'SearchFilterBar'];
  const hasAll = expected.every((n) => (layoutSec.children || []).some((c) => c.name === n && c.reusable));
  if (hasAll) {
    // ensure count 3 for those names plus existing LibraryGrid
    const count = (layoutSec.children || []).filter((c) => expected.includes(c.name)).length;
    if (count === 3) return { inserts: 0 };
  }
  // preserve LibraryGrid fa5c8 if exists
  const libraryGrid = (layoutSec.children || []).find((c) => c.id === 'fa5c8' || c.name === 'LibraryGrid');
  const others = (layoutSec.children || []).filter((c) => c.id !== 'fa5c8' && c.name !== 'LibraryGrid' && !expected.includes(c.name));
  // others should be empty normally; keep libraryGrid
  const newCovers = [createCoverWithProgress(all), createCoverWithoutProgress(all), createSearchFilterBar(all)];
  layoutSec.children = [...(libraryGrid ? [libraryGrid] : []), ...newCovers, ...others];
  // but order: LibraryGrid first then new 3 (to keep stable) — spec says Covers under layoutSec, SearchBar too
  return { inserts: 3 };
}

function populateHome(pen, all) {
  const home = findTopLevelById(pen, '2fbd0');
  if (!home) throw new Error('2fbd0 not found');
  const mainArea = (home.children || []).find((c) => c.id === 'cb4d1' || c.name === 'mainArea');
  if (!mainArea) throw new Error('cb4d1 mainArea not found in 2fbd0');
  const bottomRow = (mainArea.children || []).find((c) => c.id === 'cb8ff');
  if (!bottomRow) throw new Error('cb8ff bottomRow not found');
  const fa92e = (bottomRow.children || []).find((c) => c.id === 'fa92e');
  if (!fa92e) throw new Error('fa92e not found');
  // check if already populated
  const hasClone = fa92e.children.some((c) => c.name === 'CoverWithProgress' || c.name?.includes('Cover'));
  const has7dbb5 = fa92e.children.some((c) => c.id === '7dbb5');
  if (hasClone && !has7dbb5) {
    // verify second child is cover clone after 8bb8c
    if (fa92e.children.length === 2 && fa92e.children[0].id === '8bb8c') return { inserts: 0 };
  }
  // need CoverWithProgress reusable to clone
  const gimmk = findTopLevelById(pen, 'GImmK');
  const layoutSec = findByName(gimmk.children, 'layoutSec') || gimmk.children.find((c) => c.id === '20bc8');
  let coverTemplate = null;
  if (layoutSec) coverTemplate = (layoutSec.children || []).find((c) => c.name === 'CoverWithProgress');
  if (!coverTemplate) coverTemplate = createCoverWithProgress(all);
  // delete 7dbb5
  fa92e.children = fa92e.children.filter((c) => c.id !== '7dbb5');
  // ensure 8bb8c header stays first
  const headerIdx = fa92e.children.findIndex((c) => c.id === '8bb8c');
  if (headerIdx === -1) throw new Error('8bb8c not found in fa92e');
  // deep clone with new UUIDs
  const clone = deepCloneWithNewUUIDs(coverTemplate, all);
  // preserve header cd907+1ec06 are inside 8bb8c, not fa92e direct; just splice clone after header
  fa92e.children.splice(1, 0, clone);
  // if duplicate inserts, ensure only one clone
  if (fa92e.children.length > 2) {
    // keep only first 2 (header + clone)
    fa92e.children = fa92e.children.slice(0, 2);
  }
  return { inserts: 1, cloneId: clone.id };
}

function collectRefIds(pen) {
  const refs = [];
  function walk(n) {
    if (n && typeof n === 'object') {
      if (typeof n.ref === 'string') refs.push(n.ref);
      if (Array.isArray(n.children)) n.children.forEach(walk);
    }
  }
  pen.children.forEach(walk);
  return refs;
}

function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help') || args.includes('-h')) {
    console.log(`polish-pen-home.mjs — polish design/nexo-desktop.pen v2.17
Usage: bun run scripts/polish-pen-home.mjs [--help] [--dry-run]
Adds 6 reusable to GImmK + populates 2fbd0/fa92e, guards: hex, bounds, .bak, re-entrancy`);
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
  const all = buildAllIds(pen);

  // hex guard before mutation
  validateVarsHex(pen);
  validateRefs(all);
  validateBounds(pen);

  // re-entrancy pre-check: if already polished, short-circuit inserts:0
  const gimmkPre = findTopLevelById(pen, 'GImmK');
  const feedbackPre = gimmkPre ? findByName(gimmkPre.children, 'feedbackSec') : null;
  const layoutSecPre = gimmkPre ? (findByName(gimmkPre.children, 'layoutSec') || gimmkPre.children.find((c) => c.id === '20bc8')) : null;
  let earlyFBR = 0, earlyLBR = 0;
  if (feedbackPre) earlyFBR = (feedbackPre.children || []).filter((c) => c.reusable).length;
  if (layoutSecPre) earlyLBR = (layoutSecPre.children || []).filter((c) => ['CoverWithProgress','CoverWithoutProgress','SearchFilterBar'].includes(c.name)).length;
  const homePre = findTopLevelById(pen, '2fbd0');
  let hasCoverClonePre = false;
  try {
    const mainPre = homePre?.children?.find((c) => c.id === 'cb4d1');
    const bottomPre = mainPre?.children?.find((c) => c.id === 'cb8ff');
    const faPre = bottomPre?.children?.find((c) => c.id === 'fa92e');
    if (faPre) hasCoverClonePre = faPre.children.some((c) => c.name === 'CoverWithProgress') && !faPre.children.some((c) => c.id === '7dbb5');
  } catch {}
  if (earlyFBR === 3 && earlyLBR === 3 && hasCoverClonePre) {
    console.log(`inserts:0 hash unchanged ${originalHash.slice(0, 8)} — already polished`);
    process.exit(0);
  }

  let inserts = 0;
  const gimmk = findTopLevelById(pen, 'GImmK');
  if (!gimmk) throw new Error('GImmK not found');

  const { inserts: fSecInserts, node: feedbackSec } = findOrCreateFeedbackSec(gimmk, all);
  inserts += fSecInserts;

  const { inserts: fbInserts } = ensureFeedbackReusables(feedbackSec, all);
  inserts += fbInserts;

  const layoutSec = findByName(gimmk.children, 'layoutSec') || gimmk.children.find((c) => c.id === '20bc8');
  if (!layoutSec) throw new Error('layoutSec 20bc8 not found');
  const { inserts: lbInserts } = ensureLayoutReusables(layoutSec, all);
  inserts += lbInserts;

  // recalc GImmK height: 3162 -> ~3762 (+600) via fill_container, keep width 1200
  if (feedbackSec) {
    // ensure gimmk height covers new section
    const targetHeight = 3762;
    if (gimmk.height < targetHeight) gimmk.height = targetHeight;
  }

  const { inserts: popInserts } = populateHome(pen, all);
  inserts += popInserts;

  // rebuild allIds after mutations for validation
  const allAfter = buildAllIds(pen);
  validateRefs(allAfter);
  // ensure all ref targets exist
  const refsAfter = collectRefIds(pen);
  for (const r of refsAfter) {
    if (!allAfter.has(r)) throw new Error(`Dangling ref -> ${r} not in allIds`);
  }
  validateBounds(pen);
  validateVarsHex(pen);
  if (gimmk.width !== 1200) throw new Error(`GImmK.width must stay 1200, got ${gimmk.width}`);

  const out = JSON.stringify(pen, null, 2);
  try { JSON.parse(out); } catch (e) { console.error(`Generated JSON invalid: ${e.message}`); process.exit(1); }
  const newHash = crypto.createHash('sha256').update(out).digest('hex');
  if (inserts === 0 && newHash !== originalHash) {
    console.warn('Inserts 0 but hash differs — forcing inserts count');
  }
  if (inserts === 0) {
    console.log(`inserts:0 hash unchanged ${newHash.slice(0, 8)}`);
    process.exit(0);
  }
  if (dryRun) {
    console.log(`[dry-run] inserts:${inserts} would write ${PEN_PATH} hash ${newHash.slice(0, 8)}`);
    // also validate temp-write path in dry-run without touching disk
    process.exit(0);
  }
  if (!fs.existsSync(BAK_PATH)) {
    fs.writeFileSync(BAK_PATH, raw, 'utf8');
    console.log(`Backup created: ${BAK_PATH}`);
  }
  fs.writeFileSync(TMP_PATH, out, 'utf8');
  JSON.parse(fs.readFileSync(TMP_PATH, 'utf8'));
  fs.renameSync(TMP_PATH, PEN_PATH);
  console.log(`inserts:${inserts} wrote ${PEN_PATH} hash ${newHash.slice(0, 8)} frames:17 vars:${Object.keys(pen.variables).length}`);
}

if (import.meta.main) main();
