import type { CollectionDto, HighlightDto, LibraryBookDto } from '$lib/shared/types';

/**
 * The single schema version for every export envelope. It is the importer's
 * contract: a file written today must stay readable after the format evolves.
 * Bump it only when an existing module's shape changes; adding a new module
 * (for example `collections`) does not bump it.
 */
export const EXPORT_SCHEMA_VERSION = 1;

/**
 * The book projection confirmed by decision 3: every `LibraryBookDto` field
 * except `coverPath` (a local absolute path, meaningless on another machine)
 * and `coverUserDeleted` (presentation state). Nothing else is invented.
 */
export type ExportBook = Omit<LibraryBookDto, 'coverPath' | 'coverUserDeleted'>;

/**
 * The module registry. A new domain is added here and nowhere else; the
 * envelope and `manifest.schema` stay put. Adding a module never bumps the
 * schema.
 */
export type ExportModulePayloads = {
  books: ExportBook[];
  annotations: HighlightDto[];
  collections: CollectionDto[];
};

export type ExportModuleName = keyof ExportModulePayloads;

export type ExportManifest = {
  schema: number;
  exportedAt: string;
  app: string;
  modules: ExportModuleName[];
};

export type ExportEnvelope = {
  manifest: ExportManifest;
} & Partial<ExportModulePayloads>;

/** Canonical module order, so `manifest.modules` is deterministic. */
const MODULE_ORDER: readonly ExportModuleName[] = ['books', 'annotations', 'collections'];

/**
 * Explicitly copies the portable `LibraryBookDto` fields. An explicit copy
 * (rather than a rest/spread) is what keeps `coverPath` and
 * `coverUserDeleted` out and makes the omission visible in review.
 */
export function projectBookForExport(book: LibraryBookDto): ExportBook {
  return {
    id: book.id,
    title: book.title,
    author: book.author,
    format: book.format,
    currentPage: book.currentPage,
    totalPages: book.totalPages,
    progressPercentage: book.progressPercentage,
    minutesRead: book.minutesRead,
    updatedAt: book.updatedAt,
    createdAt: book.createdAt,
    collectionIds: book.collectionIds,
    genre: book.genre,
    publicationDate: book.publicationDate,
    language: book.language,
    readingStatus: book.readingStatus,
    startedAt: book.startedAt,
    completedAt: book.completedAt,
    progressUpdatedAt: book.progressUpdatedAt,
    stateVersion: book.stateVersion,
  };
}

/**
 * The app version recorded in every manifest. `__APP_VERSION__` is a
 * build-time define (see vite.config.ts); `typeof` keeps this safe in the
 * test runner, where the define is not applied.
 */
export function resolveExportAppVersion(): string {
  return typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : '0.0.0';
}

/**
 * Builds the one envelope every export shares. Populate any subset of the
 * registry; `manifest.modules` lists exactly the keys that carry data, in
 * canonical order. A granular export and a combined export are the same shape.
 */
export function buildExportEnvelope(
  modules: Partial<ExportModulePayloads>,
  options: { exportedAt?: string; app?: string } = {},
): ExportEnvelope {
  const populated = MODULE_ORDER.filter((name) => Array.isArray(modules[name]));
  const envelope = {
    manifest: {
      schema: EXPORT_SCHEMA_VERSION,
      exportedAt: options.exportedAt ?? new Date().toISOString(),
      app: options.app ?? resolveExportAppVersion(),
      modules: populated,
    },
  } as ExportEnvelope;
  for (const name of populated) {
    Object.assign(envelope, { [name]: modules[name] });
  }
  return envelope;
}

/** The single parser for every envelope, granular or combined. */
export function parseExportEnvelope(text: string): ExportEnvelope {
  const parsed = JSON.parse(text) as ExportEnvelope;
  if (!parsed || typeof parsed !== 'object') {
    throw new Error('settings.data.export.envelopeInvalid');
  }
  const manifest = parsed.manifest;
  if (!manifest || typeof manifest.schema !== 'number' || !Array.isArray(manifest.modules)) {
    throw new Error('settings.data.export.envelopeInvalidManifest');
  }
  return parsed;
}
