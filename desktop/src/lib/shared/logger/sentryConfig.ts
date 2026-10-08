export interface SentrySettings {
  dsn: string;
  enabled: boolean;
  tracesSampleRate: number;
  release: string;
  environment: 'development' | 'production' | 'test';
  sendDefaultPii: boolean;
  replaysSessionSampleRate: number;
  replaysOnErrorSampleRate: number;
  maskAllText: boolean;
  maskAllInputs: boolean;
}

const ENV_DSN_KEY = 'SENTRY_DSN';
const ENV_RELEASE_KEY = 'SENTRY_RELEASE';
const SETTINGS_DSN_KEY = 'sentry.dsn';

const DEFAULT_TRACES_SAMPLE_RATE = 0.1;
const DEFAULT_REPLAYS_ON_ERROR_SAMPLE_RATE = 0.1;
const DEFAULT_RELEASE = '0.0.0+unknown';
const DEFAULT_ENVIRONMENT: SentrySettings['environment'] = 'development';

const SENSITIVE_STORAGE_KEYS: readonly string[] = [
  'dsn',
  'enabled',
  'tracesSampleRate',
  'release',
  'environment',
  'sendDefaultPii',
  'replaysSessionSampleRate',
  'replaysOnErrorSampleRate',
  'maskAllText',
  'maskAllInputs',
];

type StoredSettings = Partial<Record<(typeof SENSITIVE_STORAGE_KEYS)[number], unknown>>;

export const getSentryDsn = (): string | undefined => {
  const envDsn = import.meta.env[ENV_DSN_KEY];
  if (envDsn && envDsn.length > 0) {
    return envDsn;
  }
  return undefined;
};

const getSentryRelease = (): string => {
  // The `__SENTRY_RELEASE__` define is set in `vite.config.ts` from
  // `package.json@<git-sha>`. Falls back to `SENTRY_RELEASE` env var so
  // tests / non-Vite contexts (e.g. Node scripts) still work.
  const fromDefine = (import.meta.env as Record<string, string | undefined>)['__SENTRY_RELEASE__'];
  if (fromDefine && fromDefine.length > 0) {
    return fromDefine;
  }
  const envRelease = import.meta.env[ENV_RELEASE_KEY];
  if (envRelease && envRelease.length > 0) {
    return envRelease;
  }
  return DEFAULT_RELEASE;
};

const getSentryEnvironment = (): SentrySettings['environment'] => {
  const raw = (import.meta.env as Record<string, string | undefined>).MODE;
  if (raw === 'production' || raw === 'test') {
    return raw;
  }
  return DEFAULT_ENVIRONMENT;
};

const DEFAULTS: Omit<SentrySettings, 'dsn' | 'enabled'> = {
  tracesSampleRate: DEFAULT_TRACES_SAMPLE_RATE,
  release: DEFAULT_RELEASE,
  environment: DEFAULT_ENVIRONMENT,
  sendDefaultPii: false,
  replaysSessionSampleRate: 0,
  replaysOnErrorSampleRate: DEFAULT_REPLAYS_ON_ERROR_SAMPLE_RATE,
  maskAllText: true,
  maskAllInputs: true,
};

export const getSentrySettings = async (): Promise<SentrySettings> => {
  const envDsn = getSentryDsn();

  const stored: StoredSettings = {};
  try {
    const raw = localStorage.getItem(SETTINGS_DSN_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as StoredSettings;
      for (const key of SENSITIVE_STORAGE_KEYS) {
        stored[key] = parsed[key];
      }
    }
  } catch {
    // ignore parse errors
  }

  const dsn = (stored.dsn as string | undefined) ?? envDsn ?? '';
  const enabled = typeof stored.enabled === 'boolean' ? stored.enabled : Boolean(envDsn);

  return {
    dsn,
    enabled,
    tracesSampleRate:
      typeof stored.tracesSampleRate === 'number'
        ? stored.tracesSampleRate
        : DEFAULTS.tracesSampleRate,
    release: getSentryRelease(),
    environment: getSentryEnvironment(),
    sendDefaultPii:
      typeof stored.sendDefaultPii === 'boolean' ? stored.sendDefaultPii : DEFAULTS.sendDefaultPii,
    replaysSessionSampleRate:
      typeof stored.replaysSessionSampleRate === 'number'
        ? stored.replaysSessionSampleRate
        : DEFAULTS.replaysSessionSampleRate,
    replaysOnErrorSampleRate:
      typeof stored.replaysOnErrorSampleRate === 'number'
        ? stored.replaysOnErrorSampleRate
        : DEFAULTS.replaysOnErrorSampleRate,
    maskAllText:
      typeof stored.maskAllText === 'boolean' ? stored.maskAllText : DEFAULTS.maskAllText,
    maskAllInputs:
      typeof stored.maskAllInputs === 'boolean' ? stored.maskAllInputs : DEFAULTS.maskAllInputs,
  };
};

export const createSentrySettings = (overrides?: Partial<SentrySettings>): SentrySettings => {
  return {
    dsn: overrides?.dsn ?? getSentryDsn() ?? '',
    enabled: typeof overrides?.enabled === 'boolean' ? overrides.enabled : Boolean(overrides?.dsn),
    tracesSampleRate: overrides?.tracesSampleRate ?? DEFAULTS.tracesSampleRate,
    release: overrides?.release ?? getSentryRelease(),
    environment: overrides?.environment ?? getSentryEnvironment(),
    sendDefaultPii: overrides?.sendDefaultPii ?? DEFAULTS.sendDefaultPii,
    replaysSessionSampleRate:
      overrides?.replaysSessionSampleRate ?? DEFAULTS.replaysSessionSampleRate,
    replaysOnErrorSampleRate:
      overrides?.replaysOnErrorSampleRate ?? DEFAULTS.replaysOnErrorSampleRate,
    maskAllText: overrides?.maskAllText ?? DEFAULTS.maskAllText,
    maskAllInputs: overrides?.maskAllInputs ?? DEFAULTS.maskAllInputs,
  };
};
