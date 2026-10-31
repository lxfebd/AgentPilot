/**
 * Single-flight access-token refresh shared by every ApiClient instance.
 *
 * The auth layer registers the handler; until then a 401 is surfaced as-is.
 * The registered handler is responsible for rotating the stored session.
 */
export type AccessTokenRefreshHandler = (failedToken: string | null) => Promise<string | null>;

let handler: AccessTokenRefreshHandler | null = null;
let inFlight: Promise<string | null> | null = null;

export function registerAccessTokenRefresh(next: AccessTokenRefreshHandler | null): void {
  handler = next;
  inFlight = null;
}

/** Test/teardown hook: drops the handler and any pending refresh. */
export function resetAccessTokenRefresh(): void {
  registerAccessTokenRefresh(null);
}

/**
 * Refreshes the session at most once at a time: concurrent 401s all await the
 * same rotation instead of racing each other into a refresh storm.
 */
export function requestAccessTokenRefresh(failedToken: string | null = null): Promise<string | null> {
  const current = handler;
  if (!current) return Promise.resolve(null);
  if (!inFlight) {
    inFlight = Promise.resolve()
      .then(() => current.call(null, failedToken))
      .finally(() => {
        inFlight = null;
      });
  }
  return inFlight;
}
