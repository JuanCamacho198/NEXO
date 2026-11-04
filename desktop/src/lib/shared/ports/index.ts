export type { LibraryPort, UpsertBookInput, PerBookSize } from './LibraryPort';
export type { SettingsPort } from './SettingsPort';
export type { ViewerPort, UpdateHighlightInput } from './ViewerPort';
export type { NotificationPort } from './NotificationPort';
export type { TranslationPort } from './TranslationPort';
export type { ActiveUserPort } from './ActiveUserPort';
export type {
  DictionaryLookupPort,
  DefinitionItem,
  DefinitionResult,
  DefinitionSource,
} from './DictionaryLookupPort';

export { TauriLibraryAdapter } from './adapters/tauri/TauriLibraryAdapter';
export { TauriSettingsAdapter } from './adapters/tauri/TauriSettingsAdapter';
export { TauriViewerAdapter } from './adapters/tauri/TauriViewerAdapter';
export { TauriNotificationAdapter } from './adapters/tauri/TauriNotificationAdapter';
export { TauriTranslationAdapter } from './adapters/tauri/TauriTranslationAdapter';
export { TauriActiveUserAdapter } from './adapters/tauri/TauriActiveUserAdapter';
export { TauriDictionaryLookupAdapter } from './adapters/tauri/TauriDictionaryLookupAdapter';

export { MockLibraryAdapter } from './adapters/mock/MockLibraryAdapter';
export { MockSettingsAdapter } from './adapters/mock/MockSettingsAdapter';
export { MockViewerAdapter } from './adapters/mock/MockViewerAdapter';
export { MockNotificationAdapter } from './adapters/mock/MockNotificationAdapter';
export { MockTranslationAdapter } from './adapters/mock/MockTranslationAdapter';
export { MockActiveUserAdapter } from './adapters/mock/MockActiveUserAdapter';
