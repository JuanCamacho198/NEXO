/** Copy for the addon collection confirm dialog (task 15). Plain-string
 * constants follow the addonInstallDialog / FeedbackDialog precedent. */

export const ADDON_COLLECTION_CONFIRM_TITLE = 'Install addon collection';
export const ADDON_COLLECTION_CONFIRM_DESCRIPTION =
  'One link installs a whole set. Each addon is listed below; you can remove any of them later from Addons.';
export const ADDON_COLLECTION_CONFIRM_INSTALL_LABEL = 'Install all';
export const ADDON_COLLECTION_CONFIRM_INSTALLING_LABEL = 'Installing…';
export const ADDON_COLLECTION_CONFIRM_CANCEL_LABEL = 'Cancel';
export const ADDON_COLLECTION_STREAM_LABEL = 'asks for stream access';
export const ADDON_COLLECTION_STREAM_NOTE =
  'Addons that ask for stream access need your permission one by one. Installing this collection does not allow them to use it.';
export const ADDON_COLLECTION_ALREADY_INSTALLED = 'already installed — this updates it';

export function addonCollectionSkippedText(skipped: number): string {
  return skipped === 1
    ? '1 entry in this collection cannot be imported.'
    : `${skipped} entries in this collection cannot be imported.`;
}
