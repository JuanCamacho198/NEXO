import { fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import EditMetadataModal from '$lib/features/library/components/EditMetadataModal.svelte';
import type { LibraryBookDto } from '$lib/shared/types';

const mockPickImage = vi.hoisted(() => vi.fn());
const mockReadFile = vi.hoisted(() => vi.fn());
const mockPushToast = vi.hoisted(() => vi.fn());

vi.mock('$lib/shared/services/FilePicker', () => ({ pickImage: mockPickImage }));
vi.mock('@tauri-apps/plugin-fs', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@tauri-apps/plugin-fs')>()),
  readFile: mockReadFile,
}));
vi.mock('$lib/shared/stores/ToastQueue.svelte', () => ({ pushToast: mockPushToast }));

const t = (key: string): string => key;

const BOOK: LibraryBookDto = {
  id: 'b1',
  title: 'Cien años de soledad',
  author: 'Gabriel García Márquez',
  format: 'epub',
  currentPage: 0,
  totalPages: 0,
  progressPercentage: 0,
  coverPath: null,
  minutesRead: 0,
  updatedAt: '2026-01-01T00:00:00Z',
  createdAt: '2026-01-01T00:00:00Z',
  genre: 'Novela',
  language: 'es',
  publicationDate: '1967-05-30T00:00:00Z',
};

function renderModal(props: Record<string, unknown> = {}) {
  const handlers = {
    onClose: vi.fn(),
    onSave: vi.fn().mockResolvedValue(undefined),
    onReplaceCover: vi.fn().mockResolvedValue(undefined),
    onRemoveCover: vi.fn().mockResolvedValue(undefined),
  };
  render(EditMetadataModal, { props: { open: true, book: BOOK, t, ...handlers, ...props } });
  return handlers;
}

const saveButton = (): HTMLButtonElement =>
  screen.getByRole('button', { name: 'library.editMetadata.save' }) as HTMLButtonElement;
const titleInput = (): HTMLInputElement =>
  screen.getByLabelText('library.editMetadata.titleLabel') as HTMLInputElement;

describe('EditMetadataModal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockReadFile.mockResolvedValue(new Uint8Array([1, 2, 3]));
  });

  it('opens with the stored fields and Save disabled until something changes', async () => {
    renderModal();
    await waitFor(() => expect(titleInput().value).toBe(BOOK.title));
    expect(
      (screen.getByLabelText('library.editMetadata.publicationDateLabel') as HTMLInputElement)
        .value,
    ).toBe('1967-05-30');
    expect(saveButton().disabled).toBe(true);
    expect(screen.getByText('library.editMetadata.noChanges')).toBeTruthy();

    await fireEvent.input(titleInput(), { target: { value: 'Cien años' } });
    expect(saveButton().disabled).toBe(false);
    expect(screen.queryByText('library.editMetadata.noChanges')).toBeNull();
  });

  it('blocks saving an empty title and says why', async () => {
    renderModal();
    await waitFor(() => titleInput());
    await fireEvent.input(titleInput(), { target: { value: '   ' } });
    expect(saveButton().disabled).toBe(true);
    expect(screen.getByText('library.editMetadata.titleRequired')).toBeTruthy();
    expect(screen.getByText('library.editMetadata.fixErrors')).toBeTruthy();
  });

  it('rejects a malformed publication date', async () => {
    renderModal();
    const date = (await waitFor(() =>
      screen.getByLabelText('library.editMetadata.publicationDateLabel'),
    )) as HTMLInputElement;
    await fireEvent.input(date, { target: { value: '30/05/1967' } });
    expect(screen.getByText('library.editMetadata.publicationDateInvalid')).toBeTruthy();
    expect(saveButton().disabled).toBe(true);
    await fireEvent.input(date, { target: { value: '1967' } });
    expect(saveButton().disabled).toBe(false);
  });

  it('saves on Enter, keeps unedited language and date as stored, toasts and closes', async () => {
    const { onSave, onClose } = renderModal();
    await waitFor(() => titleInput());
    await fireEvent.input(titleInput(), { target: { value: '  Cien años  ' } });
    await fireEvent.keyDown(titleInput(), { key: 'Enter' });

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    expect(onSave.mock.calls[0]![0]).toMatchObject({
      id: 'b1',
      title: 'Cien años',
      genre: 'Novela',
      language: 'es',
      publicationDate: '1967-05-30T00:00:00Z',
    });
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(mockPushToast).toHaveBeenCalledWith('success', 'library.editMetadata.saved');
  });

  it('sends an edited publication date', async () => {
    const { onSave } = renderModal();
    const date = await waitFor(() =>
      screen.getByLabelText('library.editMetadata.publicationDateLabel'),
    );
    await fireEvent.input(date, { target: { value: '1970-01' } });
    await fireEvent.click(saveButton());
    await waitFor(() => expect(onSave).toHaveBeenCalled());
    expect(onSave.mock.calls[0]![0].publicationDate).toBe('1970-01');
  });

  it('shows a failed save inline and stays open', async () => {
    const { onClose } = renderModal({ onSave: vi.fn().mockRejectedValue(new Error('disk full')) });
    await waitFor(() => titleInput());
    await fireEvent.input(titleInput(), { target: { value: 'Otro título' } });
    await fireEvent.click(saveButton());
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('disk full'));
    expect(onClose).not.toHaveBeenCalled();
    expect(mockPushToast).not.toHaveBeenCalled();
  });

  it('stages a picked cover and uploads it through onReplaceCover on save', async () => {
    mockPickImage.mockResolvedValue({ path: 'C:/pics/cover.JPG', name: 'cover.JPG' });
    const { onSave, onReplaceCover, onClose } = renderModal();
    await fireEvent.click(
      await waitFor(() => screen.getByRole('button', { name: 'library.editMetadata.changeCover' })),
    );
    await waitFor(() => expect(screen.getByText('library.editMetadata.coverPending')).toBeTruthy());
    expect(onReplaceCover).not.toHaveBeenCalled();
    expect(saveButton().disabled).toBe(false);

    await fireEvent.click(saveButton());
    await waitFor(() =>
      expect(onReplaceCover).toHaveBeenCalledWith('b1', {
        data: [1, 2, 3],
        mimeType: 'image/jpeg',
        previewPath: 'C:/pics/cover.JPG',
      }),
    );
    expect(mockReadFile).toHaveBeenCalledWith('C:/pics/cover.JPG');
    // Only the cover changed: the book row is not rewritten.
    expect(onSave).not.toHaveBeenCalled();
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('removes an existing cover through onRemoveCover', async () => {
    const book = { ...BOOK, coverPath: 'C:/covers/b1.png' };
    const { onRemoveCover } = renderModal({ book });
    await fireEvent.click(
      await waitFor(() => screen.getByRole('button', { name: 'library.editMetadata.removeCover' })),
    );
    await fireEvent.click(saveButton());
    await waitFor(() => expect(onRemoveCover).toHaveBeenCalledWith(book));
  });

  it('undoes a staged cover change without saving anything', async () => {
    mockPickImage.mockResolvedValue({ path: 'C:/pics/new.png', name: 'new.png' });
    renderModal();
    await fireEvent.click(
      await waitFor(() => screen.getByRole('button', { name: 'library.editMetadata.changeCover' })),
    );
    await fireEvent.click(
      await waitFor(() => screen.getByRole('button', { name: 'library.editMetadata.revertCover' })),
    );
    expect(saveButton().disabled).toBe(true);
  });
});
