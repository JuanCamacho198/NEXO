/** Copy + error mapping for the addon install confirm dialog
 * (sdd/addon-deeplink-v1 Work Unit A). Plain-string constants follow the
 * FeedbackDialog / feedbackDesign precedent (desktop-only dialog copy). */

export const ADDON_INSTALL_CONFIRM_TITLE = 'Install addon';
export const ADDON_INSTALL_CONFIRM_DESCRIPTION =
  'Installing adds this addon to NEXO. You can remove it later from Addons.';
export const ADDON_INSTALL_CONFIRM_INSTALL_LABEL = 'Install';
export const ADDON_INSTALL_CONFIRM_INSTALLING_LABEL = 'Installing…';
export const ADDON_INSTALL_CONFIRM_CANCEL_LABEL = 'Cancel';
export const ADDON_INSTALL_CONFIRM_VERSION_LABEL = 'Version';
export const ADDON_INSTALL_CONFIRM_PUBLISHER_LABEL = 'ID';
export const ADDON_INSTALL_CONFIRM_ALREADY_INSTALLED =
  'Already installed — installing again updates it';

export function addonInstallConfirmErrorText(code: string): string {
  return `Could not install addon (${code})`;
}
