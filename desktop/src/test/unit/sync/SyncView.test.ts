/**
 * SyncView simplification.
 *
 * The screen used to expose raw engineering state (Sync Health grid, outbox
 * depth, raw realtime/scope codes, a duplicated Devices section and a
 * dead Conflicts section) with hardcoded English. These tests pin the
 * replacement: one human status line, a secondary manual-sync action, and an
 * Advanced disclosure that keeps the scope toggles and raw support values.
 */
import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { tick } from 'svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';

type Health = {
  lastSyncAt: string | null;
  pendingCount: number;
  lastError: string | null;
  realtimeStatus: string;
} | null;

const { syncHealthMock, authMock, driveMock, syncMetadataMock } = vi.hoisted(() => ({
  syncHealthMock: {
    health: null as Health,
    scopes: {} as Record<string, boolean>,
    conflicts: [] as unknown[],
    refresh: vi.fn(),
    startPoll: vi.fn(),
    stopPoll: vi.fn(),
    setScopeEnabled: vi.fn(),
    isScopeEnabled: vi.fn(() => true),
    resolveConflict: vi.fn(),
  },
  authMock: { isAuthenticated: true, userId: 'user-1234' },
  driveMock: {
    isAuthorized: false,
    isConnecting: false,
    lastError: null as string | null,
  },
  syncMetadataMock: vi.fn(),
}));

vi.mock('$lib/shared/stores/SyncHealthState.svelte', () => ({ syncHealthState: syncHealthMock }));
vi.mock('$lib/shared/services/SyncService', () => ({
  SyncService: { syncMetadata: syncMetadataMock },
}));
vi.mock('$lib/shared/stores/AuthState.svelte', () => ({ authState: authMock }));
vi.mock('$lib/shared/stores/driveState.svelte', () => ({ driveState: driveMock }));
vi.mock('$lib/shared/services/DriveConnectService', () => ({
  beginDriveConnect: vi.fn(),
  disconnectDrive: vi.fn(),
  isDriveAuthorized: vi.fn().mockResolvedValue(false),
}));
vi.mock('$lib/shared/stores/ToastQueue.svelte', () => ({ pushToast: vi.fn() }));

import SyncView from '$lib/features/sync/components/SyncView.svelte';

const dictionary: Record<string, string> = {
  'sync.title': 'Sincronización',
  'sync.subtitle': 'Estado de sincronización y respaldo en la nube.',
  'sync.status.syncing': 'Sincronizando…',
  'sync.status.failed': 'No se pudo sincronizar',
  'sync.status.pending': 'Falta subir algo',
  'sync.status.upToDate': 'Todo al día',
  'sync.status.upToDateAt': 'Todo al día · {{when}}',
  'sync.relative.now': 'ahora mismo',
  'sync.relative.minutes': 'hace {{count}} min',
  'sync.relative.hours': 'hace {{count}} h',
  'sync.relative.days': 'hace {{count}} d',
  'settings.sync.signedOut': 'Sin sesión',
  'settings.sync.syncNow': 'Sincronizar ahora',
  'settings.notifications.syncingNow': 'Sincronizando...',
  'settings.authDescription': 'Inicia sesión para sincronizar tus datos',
  'error.retry': 'Reintentar',
  'sync.advanced.title': 'Avanzado',
  'sync.scope.title': 'Qué se sincroniza',
  'sync.scope.hint': 'Los ámbitos desactivados quedan en cola hasta que vuelvas a activarlos.',
  'sync.scope.group.annotations': 'Resaltados y notas',
  'sync.scope.progress': 'Progreso de lectura',
  'sync.scope.bookmarks': 'Marcadores',
  'sync.scope.highlights': 'Resaltados',
  'sync.scope.sessions': 'Sesiones de lectura',
  'sync.scope.catalog': 'Catálogo',
  'sync.scope.dictionary': 'Diccionario',
  'sync.raw.title': 'Detalles técnicos',
  'sync.raw.lastSync': 'Última sincronización',
  'sync.raw.pending': 'Elementos pendientes',
  'sync.raw.realtime': 'Conexión en tiempo real',
  'sync.raw.lastError': 'Último error',
  'sync.raw.never': 'Nunca',
  'sync.raw.none': 'Ninguno',
  'settings.sync.drive.title': 'Google Drive',
  'settings.sync.drive.description': 'Copia de seguridad en la nube.',
  'settings.sync.drive.notConnected': 'No conectado',
  'settings.sync.drive.connect': 'Conectar Drive',
  'settings.sync.drive.connected': 'Conectado',
  'settings.sync.drive.disconnect': 'Desconectar',
  'settings.sync.drive.connecting': 'Conectando...',
  'errors.commandFailure': 'Fallo desconocido del comando',
};

const t = (key: string, params?: Record<string, string | number>): string => {
  let result = dictionary[key] ?? key;
  for (const [k, v] of Object.entries(params ?? {})) {
    result = result.replace(`{{${k}}}`, String(v));
  }
  return result;
};

function renderView(overrides: Partial<typeof syncHealthMock> = {}): ReturnType<typeof render> {
  Object.assign(syncHealthMock, overrides);
  return render(SyncView, { props: { t } });
}

describe('SyncView simplification', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    authMock.isAuthenticated = true;
    driveMock.isAuthorized = false;
    driveMock.isConnecting = false;
    driveMock.lastError = null;
    syncHealthMock.health = {
      lastSyncAt: new Date().toISOString(),
      pendingCount: 0,
      lastError: null,
      realtimeStatus: 'connected',
    };
    syncHealthMock.scopes = {
      progress: true,
      bookmarks: true,
      highlights: true,
      sessions: true,
      catalog: true,
      dictionary: false,
    };
    syncMetadataMock.mockResolvedValue(undefined);
  });

  it('shows one up-to-date status line with the relative time', () => {
    renderView();

    const status = screen.getByTestId('sync-status');
    expect(status).toHaveTextContent('Todo al día · ahora mismo');
    expect(status).toHaveAttribute('aria-live', 'polite');
    expect(status).toHaveAttribute('role', 'status');
  });

  it('shows the pending status line when items are waiting', () => {
    renderView({
      health: {
        lastSyncAt: null,
        pendingCount: 3,
        lastError: null,
        realtimeStatus: 'connected',
      },
    });

    expect(screen.getByTestId('sync-status')).toHaveTextContent('Falta subir algo');
  });

  it('shows the failure status line with a translated retry action', () => {
    renderView({
      health: {
        lastSyncAt: null,
        pendingCount: 0,
        lastError: 'BOOM_RAW_ERROR',
        realtimeStatus: 'error',
      },
    });

    const status = screen.getByTestId('sync-status');
    expect(status).toHaveTextContent('No se pudo sincronizar');
    // The raw backend message never becomes the headline.
    expect(status).not.toHaveTextContent('BOOM_RAW_ERROR');
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument();
  });

  it('shows the syncing status line while a manual run is in flight', async () => {
    let resolveSync: () => void = () => {};
    syncMetadataMock.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          resolveSync = resolve;
        }),
    );

    renderView();
    await fireEvent.click(screen.getByRole('button', { name: 'Sincronizar ahora' }));
    await tick();

    expect(screen.getByTestId('sync-status')).toHaveTextContent('Sincronizando…');

    resolveSync();
    await waitFor(() => expect(screen.getByTestId('sync-status')).toHaveTextContent('Todo al día'));
  });

  it('shows the signed-out line and disables manual sync when not authenticated', () => {
    authMock.isAuthenticated = false;
    renderView();

    expect(screen.getByTestId('sync-status')).toHaveTextContent('Sin sesión');
    expect(screen.getByRole('button', { name: 'Sincronizar ahora' })).toBeDisabled();
  });

  it('keeps scope toggles behind a collapsed Advanced disclosure and preserves their behaviour', async () => {
    const { container } = renderView();

    const details = container.querySelector('details');
    expect(details).not.toBeNull();
    expect(details?.open).toBe(false);
    expect(screen.getByText('Avanzado')).toBeInTheDocument();

    // All six scopes remain, grouped under human headings.
    expect(screen.getAllByRole('checkbox')).toHaveLength(6);
    expect(screen.getByText('Progreso de lectura')).toBeInTheDocument();
    expect(screen.getByText('Resaltados y notas')).toBeInTheDocument();

    await fireEvent.click(screen.getByLabelText('Marcadores'));
    expect(syncHealthMock.setScopeEnabled).toHaveBeenCalledWith('bookmarks', false);
  });

  it('removes the old English sections and keeps no hardcoded English copy', () => {
    const { container } = renderView();

    expect(screen.queryByText('Sync Health')).not.toBeInTheDocument();
    expect(screen.queryByText('Devices')).not.toBeInTheDocument();
    expect(screen.queryByText('Conflicts')).not.toBeInTheDocument();
    expect(screen.queryByText(/outbox depth/i)).not.toBeInTheDocument();
    expect(screen.queryByText('Keep local')).not.toBeInTheDocument();
    expect(screen.queryByText('Keep remote')).not.toBeInTheDocument();
    expect(screen.queryByText('Sync scopes')).not.toBeInTheDocument();
    expect(screen.queryByText('Last error')).not.toBeInTheDocument();

    const text = container.textContent ?? '';
    for (const banned of [
      'Sync Health',
      'outbox depth',
      'Keep local',
      'Keep remote',
      'Sync scopes',
      'Disabled scopes',
      'No devices',
      'Remove stale',
      'Remove this device?',
      'just now',
      'm ago',
      'LWW',
    ]) {
      expect(text).not.toContain(banned);
    }
  });
});
