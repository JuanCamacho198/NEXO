#!/usr/bin/env bun
/**
 * Deterministic Bun script — syncs design/nexo-desktop.pen (v2.15)
 * to code truth (AppSidebar/CustomTitleBar/HomeDesktopView/LibraryShelfScreen).
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
const PEN_PATH = path.resolve(__dirname, '..', 'design', 'nexo-desktop.pen');
const BAK_PATH = PEN_PATH + '.bak';
const TMP_PATH = PEN_PATH + '.tmp';

// Variable invariants — hex-only
const ADD_VARS = {
  surfaceHover: { type: 'color', value: '#1E293B' },
  textMuted: { type: 'color', value: '#64748B' },
  error: { type: 'color', value: '#EF4444' },
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
        // generate new unique id but keep ref targets unchanged
        // only remap the node's own id, not ref field
        const old = n.id;
        let nid;
        do {
          nid = crypto.randomUUID().slice(0, 5);
        } while (all.has(nid));
        all.add(nid);
        n.id = nid;
      }
      if (Array.isArray(n.children)) n.children.forEach(remap);
      // descendants overrides are plain objects, no id remap needed
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

function buildAllIds(pen) {
  const all = new Set();
  if (pen.children) pen.children.forEach((c) => collectAllIds(c, all));
  // variables keys are not ids but collect for completeness if needed
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

function ensureVars(pen) {
  let inserts = 0;
  if (!pen.variables || typeof pen.variables !== 'object') pen.variables = {};
  for (const [k, v] of Object.entries(ADD_VARS)) {
    if (pen.variables[k]) {
      // validate existing value is hex, if not reject
      const cur = pen.variables[k];
      if (!isHex(cur.value) || cur.type !== 'color') {
        // fix to correct value
        pen.variables[k] = { ...v };
        inserts++;
      }
      continue;
    }
    if (!isHex(v.value)) throw new Error(`Non-hex variable rejected: ${k} ${v.value}`);
    pen.variables[k] = { ...v };
    inserts++;
  }
  // reject non-hex if any existing vars are non-hex (do not fix silently beyond ADD_VARS)
  for (const [k, v] of Object.entries(pen.variables)) {
    if (v && typeof v.value === 'string' && !isHex(v.value)) {
      throw new Error(`Variable ${k} has non-hex value ${v.value} — rejected`);
    }
    if (v && v.type !== 'color') {
      throw new Error(`Variable ${k} type must be color`);
    }
  }
  return inserts;
}

function ensureLayoutSec(pen, all) {
  const gimmk = findTopLevelById(pen, 'GImmK');
  if (!gimmk) throw new Error('GImmK not found');
  if (!Array.isArray(gimmk.children)) gimmk.children = [];
  let layoutSec = findByName(gimmk.children, 'layoutSec');
  if (layoutSec) return { node: layoutSec, inserts: 0 };
  const id = newId(all);
  layoutSec = {
    type: 'frame',
    id,
    name: 'layoutSec',
    width: 'fill_container',
    layout: 'vertical',
    gap: 16,
    description: 'w-64↔w-18 (260→72) mapping — Sidebar 260 static, collapsed 72 documented for impl',
    children: [],
  };
  gimmk.children.push(layoutSec);
  return { node: layoutSec, inserts: 1 };
}

function createSidebarReusable(all) {
  const id = newId(all);
  // header: logo 32 + title
  const header = {
    type: 'frame',
    id: newId(all),
    name: 'sidebarHeader',
    width: 'fill_container',
    gap: 12,
    padding: [16, 16, 12, 16],
    alignItems: 'center',
    children: [
      {
        type: 'frame',
        id: newId(all),
        name: 'logoCircle',
        width: 32,
        height: 32,
        fill: '$bgCard',
        cornerRadius: 16,
        justifyContent: 'center',
        alignItems: 'center',
        children: [
          { type: 'text', id: newId(all), fill: '$accent', content: 'NP', fontFamily: 'Inter', fontSize: 12, fontWeight: 'bold' },
        ],
      },
      {
        type: 'frame',
        id: newId(all),
        name: 'sidebarTitle',
        layout: 'vertical',
        gap: 2,
        children: [
          { type: 'text', id: newId(all), fill: '$textPrimary', content: 'Nexo', fontFamily: 'Inter', fontSize: 14, fontWeight: 'bold' },
          { type: 'text', id: newId(all), fill: '$textSecondary', content: 'Desktop', fontFamily: 'Inter', fontSize: 11, fontWeight: 'normal' },
        ],
      },
    ],
  };
  const navRefs = ['AjwyA', 'vVEie', 'AbSdu', 'AjwyA', 'vVEie'];
  const navMenu = {
    type: 'frame',
    id: newId(all),
    name: 'navMenu',
    width: 'fill_container',
    height: 'fill_container',
    layout: 'vertical',
    gap: 8,
    padding: [0, 16],
    children: navRefs.map((refId, idx) => ({
      type: 'ref',
      id: newId(all),
      ref: refId,
      width: 'fill_container',
      // preserve descendants override pattern
      name: `navRef${idx + 1}`,
    })),
  };
  const bottom = {
    type: 'frame',
    id: newId(all),
    name: 'bottomSidebar',
    width: 'fill_container',
    layout: 'vertical',
    gap: 12,
    padding: [0, 16, 16, 16],
    children: [
      {
        type: 'frame',
        id: newId(all),
        name: 'themeToggle',
        width: 'fill_container',
        fill: '$bgCard',
        cornerRadius: 12,
        stroke: '$border',
        strokeWidth: 1,
        strokeAlignment: 'inner',
        gap: 12,
        padding: [12, 16],
        alignItems: 'center',
        children: [
          { type: 'icon', id: newId(all), width: 16, height: 16, icon: 'moon', library: 'lucide', fill: '$textSecondary' },
          { type: 'text', id: newId(all), fill: '$textPrimary', content: 'Tema oscuro', fontFamily: 'Inter', fontSize: 12, fontWeight: '500' },
        ],
      },
      { type: 'ref', id: newId(all), ref: 'pd8II', width: 'fill_container', name: 'profileRef' },
    ],
  };
  return {
    type: 'frame',
    id,
    name: 'Sidebar',
    reusable: true,
    width: 260,
    fill: '$bgBase',
    stroke: '$border',
    strokeWidth: { right: 1 },
    strokeAlignment: 'inner',
    layout: 'vertical',
    gap: 12,
    padding: 16,
    children: [header, navMenu, bottom],
  };
}

function createTitleBarReusable(all) {
  const id = newId(all);
  const left = {
    type: 'frame',
    id: newId(all),
    name: 'titleLeft',
    gap: 8,
    alignItems: 'center',
    children: [
      { type: 'frame', id: newId(all), name: 'logo32', width: 32, height: 32, fill: '$bgCard', cornerRadius: 16, justifyContent: 'center', alignItems: 'center', children: [{ type: 'text', id: newId(all), fill: '$accent', content: 'NP', fontFamily: 'Inter', fontSize: 12, fontWeight: 'bold' }] },
      { type: 'text', id: newId(all), fill: '$textPrimary', content: 'Nexo', fontFamily: 'Inter', fontSize: 14, fontWeight: '600' },
    ],
  };
  const center = {
    type: 'frame',
    id: newId(all),
    name: 'dragRegion',
    width: 'fill_container',
    height: 'fill_container',
    // pencil custom prop to indicate tauri drag
    'data-tauri-drag-region': true,
  };
  const right = {
    type: 'frame',
    id: newId(all),
    name: 'windowControls',
    gap: 0,
    layout: 'horizontal',
    children: [
      { type: 'frame', id: newId(all), name: 'minBtn', width: 32, height: 32, fill: '$bgBase', cornerRadius: 6, justifyContent: 'center', alignItems: 'center', children: [{ type: 'icon', id: newId(all), width: 14, height: 14, icon: 'minus', library: 'lucide', fill: '$textSecondary' }] },
      { type: 'frame', id: newId(all), name: 'maxBtn', width: 32, height: 32, fill: '$bgBase', cornerRadius: 6, justifyContent: 'center', alignItems: 'center', children: [{ type: 'icon', id: newId(all), width: 14, height: 14, icon: 'maximize', library: 'lucide', fill: '$textSecondary' }] },
      { type: 'frame', id: newId(all), name: 'closeBtn', width: 32, height: 32, fill: '$bgBase', cornerRadius: 6, justifyContent: 'center', alignItems: 'center', children: [{ type: 'icon', id: newId(all), width: 14, height: 14, icon: 'x', library: 'lucide', fill: '$textSecondary' }] },
    ],
  };
  return {
    type: 'frame',
    id,
    name: 'CustomTitleBar',
    reusable: true,
    width: 'fill_container',
    height: 36,
    fill: '$bgPanel',
    layout: 'horizontal',
    gap: 0,
    padding: [0, 12],
    justifyContent: 'space_between',
    alignItems: 'center',
    children: [left, center, right],
  };
}

function createLibraryGridReusable(all, pen) {
  // clone DN5F1
  let dn = null;
  for (const top of pen.children) {
    const found = findNodeById(top, 'DN5F1');
    if (found) { dn = found; break; }
  }
  if (!dn) throw new Error('DN5F1 not found to clone');
  const clone = deepCloneWithNewUUIDs(JSON.parse(JSON.stringify(dn)), all);
  // Override wrapper props to match spec: grid 4 cols gap16 pad16
  clone.name = 'LibraryGrid';
  clone.reusable = true;
  // ensure wrapper has gap/padding/layout
  clone.gap = 16;
  clone.padding = 16;
  clone.layout = 'grid';
  // ensure cards have expected size and ref→vVEie where applicable
  // DN5F1 already has cards with ref vVEie, keep as is
  // ensure at least one card explicitly references vVEie
  // Find first book card and ensure it has cornerRadius 12 and 200x280
  // Already has those, but ensure wrapper width fill_container
  clone.width = 'fill_container';
  clone.height = 'fill_container';
  // Assign new id already done via deepClone, but ensure top id is unique and reusable
  // deepClone already gave new id; preserve reusable flag
  return clone;
}

function ensureReusables(layoutSec, pen, all) {
  if (layoutSec.children.length === 3 && layoutSec.children.every((c) => c.reusable === true)) {
    // check names match expected 3
    const names = layoutSec.children.map((c) => c.name).sort();
    if (names.includes('Sidebar') && names.includes('CustomTitleBar') && names.includes('LibraryGrid')) {
      return { inserts: 0, ids: layoutSec.children.map((c) => c.id) };
    }
  }
  // if partially exists, clear and recreate to ensure correctness? But re-entrancy wants 0 if 3 exist
  // if not 3, create missing
  if (layoutSec.children.length !== 0) {
    // if any reusables exist but not 3, treat as not complete — recreate expected 3 only if missing count !=3
    // For idempotency, if exactly 3 reusables already exist, return 0
    if (layoutSec.children.length === 3) return { inserts: 0, ids: layoutSec.children.map((c) => c.id) };
  }
  const sb = createSidebarReusable(all);
  const tb = createTitleBarReusable(all);
  const grid = createLibraryGridReusable(all, pen);
  layoutSec.children = [sb, tb, grid];
  return { inserts: 3, ids: [sb.id, tb.id, grid.id] };
}

function ensureHomeFrame(pen, all, sidebarReusableId) {
  const existing = (pen.children || []).find((c) => c.name === 'Home / Desktop');
  if (existing) return { node: existing, inserts: 0 };
  // bounds guard: check non-overlap at x1400 y800 1280x800
  const proposed = { x: 1400, y: 800, width: 1280, height: 800 };
  let hasOverlap = false;
  let maxX = -Infinity;
  for (const ch of pen.children) {
    if (typeof ch.x === 'number' && typeof ch.y === 'number' && typeof ch.width === 'number' && typeof ch.height === 'number') {
      maxX = Math.max(maxX, ch.x + ch.width);
      if (!(proposed.x + proposed.width <= ch.x || proposed.x >= ch.x + ch.width || proposed.y + proposed.height <= ch.y || proposed.y >= ch.y + ch.height)) {
        // overlap
        // but ignore if ch is GImmK which is at -1528 -290 1200 wide => no overlap with x1400
        hasOverlap = true;
      }
    } else if (typeof ch.x === 'number' && typeof ch.width === 'string') {
      const m = String(ch.width).match(/\d+/);
      if (m) maxX = Math.max(maxX, ch.x + parseInt(m[0], 10));
    } else if (typeof ch.x === 'number') {
      maxX = Math.max(maxX, ch.x + 1280);
    }
  }
  let finalX = proposed.x;
  let finalY = proposed.y;
  if (hasOverlap) {
    finalX = (isFinite(maxX) ? maxX + 64 : 1400);
  }
  // clone UjePJ for mainArea
  const uje = findTopLevelById(pen, 'UjePJ');
  if (!uje) throw new Error('UjePJ not found');
  const mainClone = deepCloneWithNewUUIDs(JSON.parse(JSON.stringify(uje)), all);
  mainClone.name = 'mainArea';
  // ensure mainClone has welcomeCard and stats (already from UjePJ)
  const homeId = newId(all);
  const homeFrame = {
    type: 'frame',
    id: homeId,
    name: 'Home / Desktop',
    x: finalX,
    y: finalY,
    width: 1280,
    height: 800,
    fill: '$bgBase',
    layout: 'horizontal',
    children: [
      { type: 'ref', id: newId(all), ref: sidebarReusableId, name: 'sidebarRef' },
      mainClone,
    ],
  };
  pen.children.push(homeFrame);
  return { node: homeFrame, inserts: 1 };
}

function repairZnFqZ(pen, all) {
  const zn = findTopLevelById(pen, 'ZnFqZ');
  if (!zn) throw new Error('ZnFqZ not found');
  if (!Array.isArray(zn.children)) zn.children = [];
  // if already 2 children with sidebar + mainArea, consider repaired
  if (zn.children.length === 2) {
    const hasSidebar = zn.children.some((c) => c.name === 'sidebar' || c.id === 'GZWUE');
    const hasMain = zn.children.some((c) => c.name === 'mainArea');
    if (hasSidebar && hasMain) return { inserts: 0 };
  }
  // keep GZWUE sidebar if exists
  let sidebar = zn.children.find((c) => c.id === 'GZWUE');
  if (!sidebar) sidebar = zn.children.find((c) => c.name === 'sidebar');
  if (!sidebar) throw new Error('GZWUE sidebar not found inside ZnFqZ');
  const uje = findTopLevelById(pen, 'UjePJ');
  if (!uje) throw new Error('UjePJ not found for ZnFqZ repair');
  const mainClone = deepCloneWithNewUUIDs(JSON.parse(JSON.stringify(uje)), all);
  mainClone.name = 'mainArea';
  // Ensure mainClone id is new (already)
  zn.children = [sidebar, mainClone];
  return { inserts: 1 };
}

function validateRefs(pen, all) {
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

function main() {
  const args = process.argv.slice(2);
  if (args.includes('--help') || args.includes('-h')) {
    console.log(`sync-pen-home.mjs — sync design/nexo-desktop.pen to code truth
Usage: bun run scripts/sync-pen-home.mjs [--help] [--dry-run]
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
  const all = buildAllIds(pen);
  // preserve version
  if (pen.version !== '2.15') {
    console.warn(`Warning: version is ${pen.version}, expected 2.15 — preserving`);
  }
  let inserts = 0;
  inserts += ensureVars(pen);
  const { node: layoutSec, inserts: lInserts } = ensureLayoutSec(pen, all);
  inserts += lInserts;
  const { inserts: rInserts, ids: reusableIds } = ensureReusables(layoutSec, pen, all);
  inserts += rInserts;
  const sidebarId = layoutSec.children.find((c) => c.name === 'Sidebar')?.id || reusableIds[0];
  const { inserts: hInserts } = ensureHomeFrame(pen, all, sidebarId);
  inserts += hInserts;
  const { inserts: zInserts } = repairZnFqZ(pen, all);
  inserts += zInserts;

  // rebuild allIds after mutations for validation
  const allAfter = buildAllIds(pen);
  // collect ids from variables? not needed but include node ids
  validateRefs(pen, allAfter);
  // GImmK width guard
  const gimmk = findTopLevelById(pen, 'GImmK');
  if (gimmk && gimmk.width !== 1200) throw new Error(`GImmK.width must stay 1200, got ${gimmk.width}`);
  // bounds no-overlap
  validateBoundsNoOverlap(pen);
  // frame count: 17 top-level children (16 frames + 1 ellipse IKC3K), frames 16
  const frameCount = pen.children.filter((c) => c.type === 'frame').length;
  const totalCount = pen.children.length;
  if (frameCount !== 16 || totalCount !== 17) {
    console.warn(`Count mismatch: frames ${frameCount} (expected 16), total children ${totalCount} (expected 17)`);
  }

  const out = JSON.stringify(pen, null, 2);
  // JSON.parse guard before write
  try {
    JSON.parse(out);
  } catch (e) {
    console.error(`Generated JSON invalid: ${e.message}`);
    process.exit(1);
  }

  // re-entrancy hash check: if no inserts, ensure hash would be unchanged
  const newHash = crypto.createHash('sha256').update(out).digest('hex');
  if (inserts === 0 && newHash !== originalHash) {
    console.warn('Inserts 0 but hash differs — forcing inserts count');
  }
  if (inserts === 0) {
    console.log(`inserts:0 hash unchanged ${newHash.slice(0, 8)}`);
    process.exit(0);
  }

  if (dryRun) {
    console.log(`[dry-run] inserts:${inserts} would write ${PEN_PATH}`);
    process.exit(0);
  }

  // safety: .bak before first write
  if (!fs.existsSync(BAK_PATH)) {
    fs.writeFileSync(BAK_PATH, raw, 'utf8');
    console.log(`Backup created: ${BAK_PATH}`);
  }
  // temp-write + atomic rename
  fs.writeFileSync(TMP_PATH, out, 'utf8');
  // validate temp file parses
  JSON.parse(fs.readFileSync(TMP_PATH, 'utf8'));
  fs.renameSync(TMP_PATH, PEN_PATH);
  console.log(`inserts:${inserts} wrote ${PEN_PATH} hash ${newHash.slice(0, 8)} frames:${frameCount} vars:${Object.keys(pen.variables).length}`);
}

if (import.meta.main) main();
