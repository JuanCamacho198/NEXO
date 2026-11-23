/**
 * drive-token — mint a Google Drive access token for the calling user.
 *
 * Why this exists: the desktop app cannot complete Google's token exchange or
 * refresh on its own. The configured Google OAuth client requires a
 * client_secret (measured: a secret-less exchange answers
 * `400 client_secret is missing`, and a wrong secret answers
 * `401 invalid_client`), and a distributed desktop bundle cannot hold one —
 * a VITE_ value is extractable from the shipped binary.
 *
 * The provider refresh token is also short-lived from the client's point of
 * view: GoTrue emits `provider_token` only once, immediately after sign-in, and
 * never again on a session refresh.
 *
 * So the secret and the Google refresh call live here, server-side. The client
 * authenticates with its Supabase JWT and gets back a fresh access token.
 *
 * Deploy with verify_jwt: true — the Supabase JWT is the only caller identity.
 * Secrets: GOOGLE_OAUTH_CLIENT_ID, GOOGLE_OAUTH_CLIENT_SECRET.
 *
 * Expected secret limits: this returns an access token only, never the refresh
 * token. The refresh token is supplied by the caller and is never persisted
 * here, so the function cannot outlive its caller's consent.
 */

interface RefreshRequest {
  refresh_token?: unknown;
}

const TOKEN_ENDPOINT = 'https://oauth2.googleapis.com/token';

/**
 * The desktop webview calls this from its own origin (`http://127.0.0.1:1420` in
 * dev, `tauri://localhost` in the packaged app), so the browser sends a
 * preflight and refuses the response unless every reply carries
 * `Access-Control-Allow-Origin`. Without it the request never reaches the
 * handler: the console shows a CORS failure, not a 4xx from here.
 *
 * The function is not a public data endpoint — `verify_jwt` rejects calls
 * without a Supabase session before this code runs — so allowing any origin is
 * safe and keeps the packaged app (whose origin is not a fixed host) working.
 */
const CORS_HEADERS: Record<string, string> = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Max-Age': '86400',
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...CORS_HEADERS },
  });
}

function fail(code: string, message: string, status = 400): Response {
  // The client maps `code` onto its own Drive error vocabulary; the message is
  // for the log line, never rendered verbatim to a user.
  return json({ error: code, message }, status);
}

Deno.serve(async (req: Request): Promise<Response> => {
  // Preflight: answer before any auth or body work.
  if (req.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: CORS_HEADERS });
  }
  if (req.method !== 'POST') {
    return fail('METHOD_NOT_ALLOWED', 'POST a JSON body with refresh_token.', 405);
  }

  const clientId = Deno.env.get('GOOGLE_OAUTH_CLIENT_ID');
  const clientSecret = Deno.env.get('GOOGLE_OAUTH_CLIENT_SECRET');
  if (!clientId || !clientSecret) {
    return fail(
      'DRIVE_SERVER_MISCONFIGURED',
      'GOOGLE_OAUTH_CLIENT_ID / GOOGLE_OAUTH_CLIENT_SECRET are not set on this function.',
      500,
    );
  }

  let payload: RefreshRequest;
  try {
    payload = (await req.json()) as RefreshRequest;
  } catch {
    return fail('INVALID_BODY', 'Body must be JSON.', 400);
  }

  const refreshToken = typeof payload.refresh_token === 'string' ? payload.refresh_token.trim() : '';
  if (!refreshToken) {
    return fail('MISSING_REFRESH_TOKEN', 'refresh_token is required.', 400);
  }

  let response: Response;
  try {
    response = await fetch(TOKEN_ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        client_id: clientId,
        client_secret: clientSecret,
        refresh_token: refreshToken,
        grant_type: 'refresh_token',
      }).toString(),
    });
  } catch (error) {
    // Network failure is transient: the caller keeps its grant and retries.
    return fail(
      'DRIVE_UPSTREAM_UNAVAILABLE',
      error instanceof Error ? error.message : String(error),
      503,
    );
  }

  const text = await response.text();
  if (!response.ok) {
    // `invalid_grant` means the user revoked or the grant expired: the caller
    // must clear its stored token and ask the user to reconnect. Any other
    // failure (5xx, quota) is transient and the grant stays.
    const revoked = response.status === 400 || response.status === 401;
    return fail(
      revoked ? 'DRIVE_GRANT_REVOKED' : 'DRIVE_UPSTREAM_FAILED',
      text.slice(0, 300),
      revoked ? 401 : 502,
    );
  }

  let data: { access_token?: unknown; expires_in?: unknown; scope?: unknown };
  try {
    data = JSON.parse(text);
  } catch {
    return fail('DRIVE_UPSTREAM_FAILED', 'Token endpoint returned non-JSON.', 502);
  }

  const accessToken = typeof data.access_token === 'string' ? data.access_token : '';
  if (!accessToken) {
    return fail('DRIVE_NO_ACCESS_TOKEN', 'Token endpoint returned no access_token.', 502);
  }

  return json({
    access_token: accessToken,
    expires_in: typeof data.expires_in === 'number' ? data.expires_in : null,
    scope: typeof data.scope === 'string' ? data.scope : null,
  });
});
