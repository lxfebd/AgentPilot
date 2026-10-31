import {
  ApiError,
  apiErrorKind,
  extractErrorPayload
} from "@/lib/api/errors";
import { requestAccessTokenRefresh } from "@/lib/api/refresh";

export type ApiTokenProvider = () => string | null | undefined;

/**
 * Exchanges the refresh token for a fresh access token.
 *
 * Receives the access token that was rejected so a handler can skip a second
 * refresh when another request already rotated the session. Resolves to null
 * when the session cannot be refreshed.
 */
export type ApiTokenRefresh = (failedToken: string | null) => Promise<string | null>;

export type ApiClientOptions = {
  baseUrl?: string;
  getToken?: ApiTokenProvider;
  /**
   * Refresh hook used when an authenticated request comes back 401. Defaults to
   * the app-wide single-flight handler registered by the auth layer, so every
   * ApiClient instance shares one refresh per session rotation.
   */
  refreshAccessToken?: ApiTokenRefresh;
  fetcher?: typeof fetch;
};

export type ApiRequestOptions = Omit<RequestInit, "body"> & {
  body?: BodyInit | Record<string, unknown> | unknown[] | null;
  token?: string | null;
  auth?: boolean;
  query?: Record<string, string | number | boolean | null | undefined>;
};

export const API_NAMESPACE = normalizeApiNamespace(process.env.NEXT_PUBLIC_AGENT_PILOT_API_NAMESPACE ?? "/api/v2");

export function apiPath(path: string): string {
  if (path.startsWith("http")) return path;
  if (!API_NAMESPACE) return path.startsWith("/") ? path : `/${path}`;
  if (path === API_NAMESPACE || path.startsWith(`${API_NAMESPACE}/`)) return path;
  const normalized = path.startsWith("/") ? path : `/${path}`;
  return `${API_NAMESPACE}${normalized}`;
}

function normalizeApiNamespace(value: string): string {
  const trimmed = value.trim();
  if (!trimmed || trimmed === "/") return "";
  return `/${trimmed.replace(/^\/+|\/+$/g, "")}`;
}

/** Paths that rotate tokens themselves; never auto-refresh a 401 from them. */
function isTokenRefreshPath(path: string): boolean {
  return path.includes("/auth/refresh");
}

export class ApiClient {
  private readonly baseUrl: string;
  private readonly getToken?: ApiTokenProvider;
  private readonly refreshAccessToken: ApiTokenRefresh;
  private readonly fetcher: typeof fetch;

  constructor(options: ApiClientOptions = {}) {
    this.baseUrl = options.baseUrl ?? "";
    this.getToken = options.getToken;
    this.refreshAccessToken = options.refreshAccessToken ?? requestAccessTokenRefresh;
    this.fetcher = options.fetcher ?? ((input, init) => fetch(input, init));
  }

  get<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    return this.request<T>(path, { ...options, method: options.method ?? "GET" });
  }

  post<T>(
    path: string,
    body?: ApiRequestOptions["body"],
    options: ApiRequestOptions = {},
  ): Promise<T> {
    return this.request<T>(path, { ...options, method: "POST", body });
  }

  put<T>(
    path: string,
    body?: ApiRequestOptions["body"],
    options: ApiRequestOptions = {},
  ): Promise<T> {
    return this.request<T>(path, { ...options, method: "PUT", body });
  }

  patch<T>(
    path: string,
    body?: ApiRequestOptions["body"],
    options: ApiRequestOptions = {},
  ): Promise<T> {
    return this.request<T>(path, { ...options, method: "PATCH", body });
  }

  delete<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    return this.request<T>(path, { ...options, method: "DELETE" });
  }

  async request<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
    const url = this.buildUrl(path, options.query);
    const headers = new Headers(options.headers);
    const body = normalizeBody(options.body);
    if (body != null && !headers.has("content-type") && isJsonBody(options.body)) {
      headers.set("content-type", "application/json");
    }
    headers.set("accept", headers.get("accept") ?? "application/json");

    const authenticated = options.auth !== false;
    let token = options.token ?? (authenticated ? this.getToken?.() : null);
    const mayRefresh = authenticated && !isTokenRefreshPath(path);

    // One replay: a 401 rotates the session once and the request is retried with
    // the new token. A second 401 is surfaced, so a broken session cannot loop.
    for (let attempt = 0; ; attempt += 1) {
      const requestHeaders = new Headers(headers);
      if (token) requestHeaders.set("authorization", `Bearer ${token}`);
      else requestHeaders.delete("authorization");

      let response: Response;
      try {
        response = await this.fetcher(url, {
          ...options,
          body,
          headers: requestHeaders
        });
      } catch (error) {
        throw new ApiError({
          status: 0,
          kind: "network",
          detail: error instanceof Error ? error.message : "network error",
          url
        });
      }

      if (response.status === 204) return undefined as T;

      const payload = await readPayload(response);
      if (response.ok) return payload as T;

      const normalized = extractErrorPayload(payload);
      const error = new ApiError({
        status: response.status,
        kind: apiErrorKind(response.status),
        detail: normalized.detail || `HTTP ${response.status}`,
        code: normalized.code,
        payload,
        url
      });

      if (response.status === 401 && attempt === 0 && mayRefresh && token) {
        const nextToken = await this.tryRefreshAccessToken(token);
        if (nextToken) {
          token = nextToken;
          continue;
        }
      }
      throw error;
    }
  }

  private async tryRefreshAccessToken(failedToken: string): Promise<string | null> {
    try {
      return await this.refreshAccessToken(failedToken);
    } catch {
      // A failed refresh keeps the original 401: the auth layer owns sign-out.
      return null;
    }
  }

  private buildUrl(
    path: string,
    query?: ApiRequestOptions["query"],
  ): string {
    const base = this.baseUrl;
    const namespacedPath = apiPath(path);
    const raw = namespacedPath.startsWith("http") ? namespacedPath : `${base}${namespacedPath}`;
    if (!query) return raw;
    const url = new URL(raw, typeof window === "undefined" ? "http://localhost" : window.location.origin);
    for (const [key, value] of Object.entries(query)) {
      if (value == null) continue;
      url.searchParams.set(key, String(value));
    }
    if (namespacedPath.startsWith("http") || base) return url.toString();
    return `${url.pathname}${url.search}${url.hash}`;
  }
}

export const apiClient = new ApiClient();

async function readPayload(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return null;
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    try {
      return JSON.parse(text);
    } catch {
      return text;
    }
  }
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function normalizeBody(body: ApiRequestOptions["body"]): BodyInit | null | undefined {
  if (body == null) return body;
  if (typeof body === "string") return body;
  if (body instanceof FormData) return body;
  if (body instanceof Blob) return body;
  if (body instanceof ArrayBuffer) return body;
  if (ArrayBuffer.isView(body)) return body;
  if (body instanceof URLSearchParams) return body;
  return JSON.stringify(body);
}

function isJsonBody(body: ApiRequestOptions["body"]): boolean {
  if (body == null) return false;
  if (typeof body === "string") return false;
  if (body instanceof FormData) return false;
  if (body instanceof Blob) return false;
  if (body instanceof ArrayBuffer) return false;
  if (ArrayBuffer.isView(body)) return false;
  if (body instanceof URLSearchParams) return false;
  return true;
}
