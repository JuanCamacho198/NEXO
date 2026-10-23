/**
 * Shared conformance corpus.
 *
 * The JSON under `test/fixtures/conformance/` is the cross-platform contract:
 * the Android port reads these exact files through a Gradle copy task, so any
 * divergence between the two implementations fails here or there. Nothing in
 * this file may hard-code an expectation that belongs in the corpus.
 */

import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { AddonFetchError } from '@nexo/manifest-validator';
import { loadManifest } from '../src/legacy';
import { parseManifestV3, type AddonManifestV3, type AddonShape } from '../src/manifest';
import {
  assertDeliverableStream,
  parseCatalogPayload,
  parseMetaPayload,
  parseStreamPayload,
} from '../src/payload';
import { type AddonDescriptor, selectAddons } from '../src/router';
import { buildRequestUrl } from '../src/templates';
import type { ResourceRequest } from '../src/resourceUrl';

const CORPUS_DIR = fileURLToPath(new URL('./fixtures/conformance/', import.meta.url));
const EXPECTED_FILE = 'expected.json';

/** Only these keys may survive a manifest parse; everything else is ignored. */
const KNOWN_MANIFEST_KEYS = new Set([
  'id',
  'version',
  'name',
  'description',
  'types',
  'catalogs',
  'resources',
  'idPrefixes',
  'logo',
  'background',
  'behaviorHints',
  'nexo',
]);

const KNOWN_NEXO_KEYS = new Set(['homepage', 'license', 'templates', 'shape']);

interface ManifestVerdictOk {
  ok: true;
  shape: string | null;
  types: string[];
  catalogIds: string[];
  catalogExtras: Record<string, string[]>;
  resourceNames: string[];
  resourceTypes: Record<string, string[]>;
  resourceIdPrefixes: Record<string, string[]>;
  idPrefixes: string[] | null;
  templates: Record<string, string> | null;
}

interface ManifestVerdictError {
  ok: false;
  error: string;
}

type ManifestVerdict = ManifestVerdictOk | ManifestVerdictError;

type PayloadVerdict =
  | {
      kind: 'catalog';
      shape: AddonShape;
      metaCount: number;
      firstMetaId: string | null;
      lastMetaId: string | null;
      authorsById: Record<string, string[]>;
      skipped: number;
    }
  | {
      kind: 'meta';
      shape: AddonShape;
      metaId: string | null;
      isbn13: string | null;
    }
  | {
      kind: 'streams';
      streams: { url: string; source: string; format: string; accessType: string }[];
      skipped: number;
    };

interface StreamVerdictAccepted {
  accepted: true;
  source: string;
  url: string;
  format: string;
  accessType: string;
}

interface StreamVerdictRejected {
  accepted: false;
}

type StreamVerdict = StreamVerdictAccepted | StreamVerdictRejected;

interface UrlCase {
  name: string;
  installUrl: string;
  manifest: string;
  request: ResourceRequest;
  expectedUrl?: string;
  expectedError?: string;
}

function readCorpusJson(relativePath: string): unknown {
  try {
    return JSON.parse(readFileSync(join(CORPUS_DIR, relativePath), 'utf8'));
  } catch (error) {
    throw new Error(`conformance corpus is unreadable: ${relativePath} (${String(error)})`);
  }
}

function readCorpusBytes(relativePath: string): Uint8Array {
  try {
    return new Uint8Array(readFileSync(join(CORPUS_DIR, relativePath)));
  } catch (error) {
    throw new Error(`conformance corpus is unreadable: ${relativePath} (${String(error)})`);
  }
}

function listJsonFiles(directory: string): string[] {
  try {
    return readdirSync(join(CORPUS_DIR, directory))
      .filter((file) => file.endsWith('.json') && file !== EXPECTED_FILE)
      .sort();
  } catch (error) {
    throw new Error(`conformance corpus is missing directory: ${directory} (${String(error)})`);
  }
}

/** Key-order independent serialization, so verdict comparison is stable. */
function canonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
      .map(([key, entry]) => `${JSON.stringify(key)}:${canonical(entry)}`)
      .sort();
    return `{${entries.join(',')}}`;
  }
  return JSON.stringify(value) ?? 'undefined';
}

/** Top-level key diff, so a divergence names the field instead of dumping JSON. */
function diffVerdict(actual: Record<string, unknown>, expected: Record<string, unknown>): string[] {
  const diffs: string[] = [];
  const keys = new Set([...Object.keys(actual), ...Object.keys(expected)]);
  for (const key of [...keys].sort()) {
    if (canonical(actual[key]) !== canonical(expected[key])) {
      diffs.push(`${key}: expected ${canonical(expected[key])}, got ${canonical(actual[key])}`);
    }
  }
  return diffs;
}

function describeManifest(manifest: AddonManifestV3): ManifestVerdictOk {
  const resourceTypes: Record<string, string[]> = {};
  const resourceIdPrefixes: Record<string, string[]> = {};
  for (const resource of manifest.resources) {
    if (resource.types !== undefined) resourceTypes[resource.name] = resource.types;
    if (resource.idPrefixes !== undefined) resourceIdPrefixes[resource.name] = resource.idPrefixes;
  }

  const catalogExtras: Record<string, string[]> = {};
  for (const catalog of manifest.catalogs) {
    catalogExtras[catalog.id] = (catalog.extra ?? []).map((extra) => extra.name);
  }

  return {
    ok: true,
    shape: manifest.nexo?.shape ?? null,
    types: manifest.types,
    catalogIds: manifest.catalogs.map((catalog) => catalog.id),
    catalogExtras,
    resourceNames: manifest.resources.map((resource) => resource.name),
    resourceTypes,
    resourceIdPrefixes,
    idPrefixes: manifest.idPrefixes ?? null,
    templates: manifest.nexo?.templates ?? null,
  };
}

function describePayload(body: unknown, verdict: PayloadVerdict): Record<string, unknown> {
  if (verdict.kind === 'catalog') {
    const parsed = parseCatalogPayload(body, verdict.shape);
    const authorsById: Record<string, string[]> = {};
    for (const meta of parsed.metas) {
      if (verdict.authorsById[meta.id] !== undefined) authorsById[meta.id] = meta.authors;
    }
    return {
      kind: 'catalog',
      shape: verdict.shape,
      metaCount: parsed.metas.length,
      firstMetaId: parsed.metas[0]?.id ?? null,
      lastMetaId: parsed.metas[parsed.metas.length - 1]?.id ?? null,
      authorsById,
      skipped: parsed.skipped,
    };
  }

  if (verdict.kind === 'meta') {
    const parsed = parseMetaPayload(body, verdict.shape);
    return {
      kind: 'meta',
      shape: verdict.shape,
      metaId: parsed?.id ?? null,
      isbn13: parsed?.isbn13 ?? null,
    };
  }

  const parsed = parseStreamPayload(body);
  return {
    kind: 'streams',
    streams: parsed.streams.map((stream) => ({
      url: stream.url,
      source: stream.source,
      format: stream.format,
      accessType: stream.accessType,
    })),
    skipped: parsed.skipped,
  };
}

describe('conformance corpus', () => {
  it('manifests: every fixture parses to its expected verdict', () => {
    const expectation = readCorpusJson(`manifests/${EXPECTED_FILE}`) as {
      contentType: string;
      cases: Record<string, ManifestVerdict>;
    };
    const diffs: string[] = [];

    for (const file of listJsonFiles('manifests')) {
      const verdict = expectation.cases[file];
      if (verdict === undefined) {
        diffs.push(`${file}: no expected verdict in manifests/${EXPECTED_FILE}`);
        continue;
      }

      const bytes = readCorpusBytes(`manifests/${file}`);
      let actual: ManifestVerdict;
      try {
        const manifest = loadManifest(bytes, expectation.contentType);

        const unknownTopLevel = Object.keys(manifest).filter((key) => !KNOWN_MANIFEST_KEYS.has(key));
        if (unknownTopLevel.length > 0) {
          diffs.push(`${file}: unknown top-level keys survived parsing: ${unknownTopLevel.join(', ')}`);
        }
        const unknownNexo = Object.keys(manifest.nexo ?? {}).filter(
          (key) => !KNOWN_NEXO_KEYS.has(key),
        );
        if (unknownNexo.length > 0) {
          diffs.push(`${file}: unknown nexo keys survived parsing: ${unknownNexo.join(', ')}`);
        }

        actual = describeManifest(manifest);
      } catch (error) {
        if (!(error instanceof AddonFetchError)) {
          diffs.push(`${file}: threw a non-AddonFetchError: ${String(error)}`);
          continue;
        }
        actual = { ok: false, error: error.code };
      }

      for (const line of diffVerdict(actual, verdict as unknown as Record<string, unknown>)) {
        diffs.push(`${file}: ${line}`);
      }
    }

    expect(diffs).toEqual([]);
  });

  it('payloads: every fixture parses to its expected verdict', () => {
    const expectation = readCorpusJson(`payloads/${EXPECTED_FILE}`) as {
      cases: Record<string, PayloadVerdict>;
    };
    const diffs: string[] = [];

    for (const file of listJsonFiles('payloads')) {
      const verdict = expectation.cases[file];
      if (verdict === undefined) {
        diffs.push(`${file}: no expected verdict in payloads/${EXPECTED_FILE}`);
        continue;
      }

      const body = readCorpusJson(`payloads/${file}`);
      let actual: Record<string, unknown>;
      try {
        actual = describePayload(body, verdict);
      } catch (error) {
        diffs.push(`${file}: parser threw instead of returning an empty result: ${String(error)}`);
        continue;
      }

      for (const line of diffVerdict(actual, verdict as unknown as Record<string, unknown>)) {
        diffs.push(`${file}: ${line}`);
      }
    }

    expect(diffs).toEqual([]);
  });

  it('request URLs: every case builds its expected URL', () => {
    const corpus = readCorpusJson('urls.json') as {
      contentType: string;
      manifests: Record<string, unknown>;
      cases: UrlCase[];
    };
    const diffs: string[] = [];

    for (const testCase of corpus.cases) {
      const caseName = testCase.name ?? '(unnamed case)';
      const source = corpus.manifests[testCase.manifest];
      if (source === undefined) {
        diffs.push(`${caseName}: unknown manifest "${testCase.manifest}"`);
        continue;
      }

      const manifest = parseManifestV3(
        new TextEncoder().encode(JSON.stringify(source)),
        corpus.contentType,
      );
      const addon = { installUrl: testCase.installUrl, manifest };

      if (testCase.expectedError !== undefined) {
        try {
          const url = buildRequestUrl(addon, testCase.request);
          diffs.push(`${caseName}: expected ${testCase.expectedError}, built ${url}`);
        } catch (error) {
          if (!(error instanceof AddonFetchError)) {
            diffs.push(`${caseName}: threw a non-AddonFetchError: ${String(error)}`);
          } else if (error.code !== testCase.expectedError) {
            diffs.push(`${caseName}: expected ${testCase.expectedError}, got ${error.code}`);
          }
        }
        continue;
      }

      try {
        const url = buildRequestUrl(addon, testCase.request);
        if (url !== testCase.expectedUrl) {
          diffs.push(`${caseName}: expected ${testCase.expectedUrl}, got ${url}`);
        }
      } catch (error) {
        diffs.push(`${caseName}: threw ${String(error)}`);
      }
    }

    expect(diffs).toEqual([]);
  });

  it('streams: every case matches the single torrent guard', () => {
    const corpus = readCorpusJson('streams.json') as {
      cases: { name: string; stream: unknown; expected: StreamVerdict }[];
    };
    const diffs: string[] = [];

    for (const testCase of corpus.cases) {
      const caseName = testCase.name ?? '(unnamed case)';
      const actual = assertDeliverableStream(testCase.stream);

      if (!testCase.expected.accepted) {
        if (actual !== null) {
          diffs.push(`${caseName}: expected rejection, accepted ${canonical(actual)}`);
        }
        continue;
      }

      if (actual === null) {
        diffs.push(`${caseName}: expected acceptance, guard returned null`);
        continue;
      }

      const summary = {
        accepted: true,
        source: actual.source,
        url: actual.url,
        format: actual.format,
        accessType: actual.accessType,
      };
      for (const line of diffVerdict(
        summary,
        testCase.expected as unknown as Record<string, unknown>,
      )) {
        diffs.push(`${caseName}: ${line}`);
      }
    }

    expect(diffs).toEqual([]);
  });

  it('stremio-stream-only: parses, keeps zero book catalogs and contributes nothing', () => {
    const manifest = loadManifest(
      readCorpusBytes('manifests/stremio-stream-only.json'),
      'application/json',
    );

    expect(manifest.catalogs).toEqual([]);

    const descriptor: AddonDescriptor = {
      id: manifest.id,
      installUrl: 'https://thepiratebay-plus.strem.fun/manifest.json',
      addedAt: 0,
      enabled: true,
      manifest,
    };
    expect(selectAddons([descriptor], { resource: 'catalog', type: 'book' })).toEqual([]);

    // A torrent-only stream answer is dropped by the guard, not surfaced as an error.
    const payload = parseStreamPayload({
      streams: [
        {
          name: 'ThePirateBay+',
          title: '2160p',
          infoHash: 'DEADBEEFDEADBEEFDEADBEEFDEADBEEF',
          url: 'https://thepiratebay-plus.strem.fun/play/DEADBEEFDEADBEEFDEADBEEFDEADBEEF:0:0',
        },
      ],
    });
    expect(payload).toEqual({ streams: [], skipped: 1 });
  });

  it('corpus: every fixture has exactly one verdict and no verdict is orphaned', () => {
    const diffs: string[] = [];

    for (const directory of ['manifests', 'payloads'] as const) {
      const files = listJsonFiles(directory);
      const expectation = readCorpusJson(`${directory}/${EXPECTED_FILE}`) as {
        cases: Record<string, unknown>;
      };
      if (files.length === 0) diffs.push(`${directory}: corpus is empty`);
      for (const file of files) {
        if (expectation.cases[file] === undefined) {
          diffs.push(`${directory}/${file}: fixture without a verdict`);
        }
      }
      for (const key of Object.keys(expectation.cases)) {
        if (!files.includes(key)) diffs.push(`${directory}/${key}: verdict without a fixture`);
      }
    }

    for (const file of ['urls.json', 'streams.json']) {
      const corpus = readCorpusJson(file) as { cases: { name?: string }[] };
      const names = corpus.cases.map((testCase) => testCase.name ?? '');
      if (names.length === 0) diffs.push(`${file}: no cases`);
      const seen = new Set<string>();
      for (const name of names) {
        if (name.length === 0) diffs.push(`${file}: a case has no name`);
        if (seen.has(name)) diffs.push(`${file}: duplicate case name "${name}"`);
        seen.add(name);
      }
    }

    const manifestVerdicts = (
      readCorpusJson(`manifests/${EXPECTED_FILE}`) as { cases: Record<string, ManifestVerdict> }
    ).cases;
    const okKeys = [
      'catalogExtras',
      'catalogIds',
      'idPrefixes',
      'ok',
      'resourceIdPrefixes',
      'resourceNames',
      'resourceTypes',
      'shape',
      'templates',
      'types',
    ];
    for (const [file, verdict] of Object.entries(manifestVerdicts)) {
      const expectedKeys = verdict.ok ? okKeys : ['error', 'ok'];
      const actualKeys = Object.keys(verdict).sort();
      if (canonical(actualKeys) !== canonical([...expectedKeys].sort())) {
        diffs.push(`${file}: verdict schema is ${canonical(actualKeys)}`);
      }
    }

    expect(diffs).toEqual([]);
  });
});
