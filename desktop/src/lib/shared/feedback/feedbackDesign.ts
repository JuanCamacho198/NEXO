/**
 * Crash Feedback Dialog — desktop copy + tokens (sdd/sentry-observability-v2/feedback-design, HYNft).
 *
 * Source of truth: engram observation #2460. Strings are reproduced VERBATIM
 * from the Pencil HYNft design (desktop, 560x600 modal over dimmed reader .65
 * black). Do NOT retype — if you need to tweak wording, update engram #2460
 * and propagate. Mobile (njdtk) is PR4 (Android) — separate tokens there.
 *
 * Reference: sdd/sentry-observability-v2/spec requirement D1 (Pixel-faithful
 * dialogs, ES copy verbatim).
 */

/** Modal headline. */
export const FEEDBACK_TITLE = 'Nexo se cerró de repente';

/** Subhead (subtitle) below the title. */
export const FEEDBACK_SUBTITLE =
  'Ya guardamos tu última página y tus notas. Si nos cuentas qué estabas haciendo, vamos a poder reproducir el problema.';

/** Context card section header. */
export const FEEDBACK_CONTEXT_HEADER = 'LO QUE ESTABAS HACIENDO';

/** Pill labels (desktop-only, per design). Order matters — pills are rendered in array order. */
export const FEEDBACK_PILLS: ReadonlyArray<{
  label: string;
  icon: 'clock-3' | 'highlighter' | 'zap';
}> = [
  { label: '42 min hoy', icon: 'clock-3' },
  { label: '3 notas', icon: 'highlighter' },
  { label: '26% leído', icon: 'zap' },
];

/** Textarea label. */
export const FEEDBACK_INPUT_LABEL = '¿Qué estabas haciendo?';

/** Helper text under the textarea. */
export const FEEDBACK_INPUT_HINT = 'Opcional · max 500 caracteres';

/** Footer privacy shield. */
export const FEEDBACK_PRIVACY = 'Sólo llega al equipo, anónimo';

/** Ghost button — restarts the app shell (NOT a full reload, by design). */
export const FEEDBACK_RESTART_LABEL = 'Reiniciar app';

/** Primary button label. */
export const FEEDBACK_SEND_LABEL = 'Enviar reporte';

/** Hard character cap (desktop). Mobile is 240. */
export const FEEDBACK_MAX_CHARS = 500;

/** Sample context values (HYNft placeholder book). Real values come from active reader state. */
export const FEEDBACK_SAMPLE_BOOK = {
  title: 'La Odisea',
  author: 'Homero',
  chapter: 'Canto III',
  page: 32,
  totalPages: 412,
};
