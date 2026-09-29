export { BulkImportService, type BulkImportProgress } from './BulkImportService';
export { SyncService } from './SyncService';
export {
  extractPdfMetadata,
  generatePdfFirstPageThumbnail,
  type PdfMetadata,
} from './pdfThumbnail';
export { pickFile, pickFolder, type FilePickerResult, type FolderPickerResult } from './FilePicker';
export {
  importBook,
  getFileBytes,
  type BookImportInput,
  type BookDto,
  type ImportProgress,
} from './BookImportService';
export type { StorageProvider } from './storage/StorageProvider';
export { GDriveProvider } from './storage/GDriveProvider';
export * from './catalog';
export { DriveColdBackupService } from './DriveColdBackupService';
export { GoogleDriveStateSync } from './GoogleDriveStateSync';
export {
  extractEpubImportMetadata,
  extractEpubMetadataFromBytes,
  parseOpfDirectly,
  type ImportEpubMetadata,
} from './epubImportMetadata';
export {
  inferGenreFromText,
  KEYWORD_TABLE,
  UNCLASSIFIED_GENRE,
  type CanonicalGenre,
  type ConcreteCanonicalGenre,
} from './genreHeuristic';
export { fromCfi, derivePage, parseSpineIndex } from './LocatorCodec';
export type { CanonicalLocator, LocatorLocations } from './LocatorCodec';
export {
  registerSupabaseCallbackHandler,
  restoreSession,
  signInAnonymously,
  signInWithGoogle,
  signOut,
} from './SupabaseAuthService';
export { isAnonymousSession } from './anonymousSession';
export type { SupabaseSessionData } from '$lib/shared/stores/AuthState.svelte';
export {
  beginDriveConnect,
  buildDriveAuthorizeUrl,
  buildDriveState,
  consumeDriveAuthResult,
  disconnectDrive,
  DriveConfigError,
  DriveOAuthError,
  generateDriveCodeChallenge,
  generateDriveCodeVerifier,
  getDriveAccessToken,
  getDriveOAuthConfig,
  isDriveAuthorized,
  parseDriveCallbackUrl,
  refreshDriveAccessToken,
  type DriveAuthResult,
  type DriveCallbackErrorCode,
  type DriveConfigErrorCode,
  type DriveOAuthConfig,
} from './DriveConnectService';
export {
  markDriveDeclined,
  shouldShowDrivePrompt,
  type DrivePromptInput,
} from './driveConnectPromptGate';
