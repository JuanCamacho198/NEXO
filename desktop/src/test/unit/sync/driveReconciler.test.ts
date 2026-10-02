/**
 * FR-07 reconciliation scenarios: adopt, copy-to-canonical, twins never
 * deleted, checksum mismatch flagged, and verification failure leaves the
 * source in place.
 */
import { describe, expect, it } from 'vitest';
import {
  planReconciliation,
  reconcileDriveBookNames,
  type DriveReconcilePort,
  type ReconcileAction,
} from '$lib/shared/sync/driveReconciler';

const BOOK = { bookId: 'gutendex:2701', extension: 'epub' };
const CANONICAL = 'gutendex2701.epub';

class FakeDrive implements DriveReconcilePort {
  readonly objects = new Map<string, Uint8Array>();
  readonly deleted: string[] = [];
  failUpload = false;
  corruptTarget = false;

  constructor(entries: Array<[string, Uint8Array]>) {
    for (const [name, bytes] of entries) this.objects.set(name, bytes);
  }

  async list(): Promise<string[]> {
    return [...this.objects.keys()];
  }

  async download(name: string): Promise<Uint8Array> {
    const bytes = this.objects.get(name);
    if (!bytes) throw new Error(`missing ${name}`);
    return bytes;
  }

  async upload(name: string, bytes: Uint8Array): Promise<unknown> {
    if (this.failUpload) throw new Error('upload refused');
    this.objects.set(name, this.corruptTarget ? new Uint8Array([9, 9, 9]) : bytes);
    return name;
  }

  async getFileSize(name: string): Promise<number | null> {
    return this.objects.get(name)?.length ?? null;
  }
}

const bytes = (...values: number[]): Uint8Array => new Uint8Array(values);

describe('DriveReconciler — plan', () => {
  it('adopts a canonical object with no legacy twin', () => {
    const actions = planReconciliation([BOOK], [{ name: CANONICAL }]);
    expect(actions).toEqual([{ kind: 'adopt', bookId: 'gutendex:2701', canonicalName: CANONICAL }]);
  });

  it('plans copy-to-canonical for a legacy-only Android dash name', () => {
    const actions = planReconciliation([BOOK], [{ name: 'gutendex-2701.epub' }]);
    expect(actions).toEqual([
      {
        kind: 'copy-to-canonical',
        bookId: 'gutendex:2701',
        sourceName: 'gutendex-2701.epub',
        canonicalName: CANONICAL,
      },
    ]);
  });

  it('plans copy-to-canonical for a legacy underscore name', () => {
    const actions = planReconciliation([BOOK], [{ name: 'gutendex_2701.epub' }]);
    expect(actions[0]).toMatchObject({ kind: 'copy-to-canonical', sourceName: 'gutendex_2701.epub' });
  });

  it('recognizes a raw colon-bearing desktop name', () => {
    const actions = planReconciliation([BOOK], [{ name: 'gutendex:2701.epub' }]);
    expect(actions[0]).toMatchObject({ kind: 'copy-to-canonical', sourceName: 'gutendex:2701.epub' });
  });

  it('keeps canonical live and never deletes a matching twin', () => {
    const actions = planReconciliation(
      [BOOK],
      [
        { name: CANONICAL, checksum: 'aa' },
        { name: 'gutendex-2701.epub', checksum: 'aa' },
      ],
    );
    expect(actions).toEqual([
      {
        kind: 'keep-canonical',
        bookId: 'gutendex:2701',
        canonicalName: CANONICAL,
        legacyTwin: 'gutendex-2701.epub',
      },
    ]);
  });

  it('flags a checksum mismatch while keeping canonical live', () => {
    const actions = planReconciliation(
      [BOOK],
      [
        { name: CANONICAL, checksum: 'aa' },
        { name: 'gutendex-2701.epub', checksum: 'bb' },
      ],
    );
    expect(actions[0].kind).toBe('flag-mismatch');
  });

  it('never emits a delete or a second canonical target', () => {
    const actions = planReconciliation(
      [BOOK],
      [{ name: 'gutendex-2701.epub' }, { name: 'gutendex_2701.epub' }],
    );
    expect(actions).toHaveLength(1);
    expect(JSON.stringify(actions)).not.toContain('delete');
  });
});

describe('DriveReconciler — executor', () => {
  it('copies a legacy object to canonical, verifies, and keeps the source', async () => {
    const drive = new FakeDrive([['gutendex-2701.epub', bytes(1, 2, 3)]]);

    const outcome = await reconcileDriveBookNames(drive, [BOOK]);

    expect(outcome.copied).toEqual([CANONICAL]);
    expect(outcome.failed).toEqual([]);
    expect(drive.objects.has('gutendex-2701.epub')).toBe(true);
    expect([...drive.objects.get(CANONICAL)!]).toEqual([1, 2, 3]);
    expect(drive.deleted).toEqual([]);
  });

  it('does not copy or delete when canonical and legacy twins already exist', async () => {
    const drive = new FakeDrive([
      [CANONICAL, bytes(1, 2, 3)],
      ['gutendex-2701.epub', bytes(1, 2, 3)],
    ]);

    const outcome = await reconcileDriveBookNames(drive, [BOOK]);

    expect(outcome.copied).toEqual([]);
    expect(drive.objects.has('gutendex-2701.epub')).toBe(true);
    const kinds = outcome.actions.map((action: ReconcileAction) => action.kind);
    expect(kinds).toEqual(['keep-canonical']);
  });

  it('reports a verification failure and preserves the source', async () => {
    const drive = new FakeDrive([['gutendex-2701.epub', bytes(1, 2, 3)]]);
    drive.corruptTarget = true;

    const outcome = await reconcileDriveBookNames(drive, [BOOK]);

    expect(outcome.copied).toEqual([]);
    expect(outcome.failed[0]?.source).toBe('gutendex-2701.epub');
    expect(drive.objects.has('gutendex-2701.epub')).toBe(true);
    expect(drive.deleted).toEqual([]);
  });
});
