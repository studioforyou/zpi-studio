import { env } from '@/shared/config/env';
import { ApiError, NETWORK_ERROR } from './ApiError';
import type { ProblemDetails } from './problem';

type ApiClientConfig = {
  getAccessToken: () => string | null;
  onUnauthorized: () => void;
};

type ApiFetchInit = {
  method?: string;
  body?: unknown;
  signal?: AbortSignal;
};

let config: ApiClientConfig = {
  getAccessToken: () => null,
  onUnauthorized: () => {},
};

export function configureApiClient(next: ApiClientConfig): void {
  config = next;
}

export async function apiFetch<T>(path: string, init: ApiFetchInit = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (init.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const token = config.getAccessToken();
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  let response: Response;
  try {
    response = await fetch(env.apiBaseUrl + path, {
      method: init.method ?? 'GET',
      headers,
      body: init.body === undefined ? undefined : JSON.stringify(init.body),
      signal: init.signal,
    });
  } catch (error) {
    // A cancelled request (e.g. by React Query) is not a network failure.
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    throw new ApiError(null, NETWORK_ERROR, null);
  }

  if (response.ok) {
    if (response.status === 204) {
      return undefined as T;
    }
    return (isJson(response) ? await response.json() : await response.text()) as T;
  }

  if (response.status === 401) {
    // TODO(auth): refresh the token once, then log out (API_CONVENTIONS.md, section 5).
    config.onUnauthorized();
  }
  const problem = await readProblem(response);
  throw new ApiError(response.status, problem?.code ?? null, problem);
}

function isJson(response: Response): boolean {
  return /application\/(problem\+)?json/.test(response.headers.get('Content-Type') ?? '');
}

async function readProblem(response: Response): Promise<ProblemDetails | null> {
  if (!isJson(response)) {
    return null;
  }
  try {
    const body: unknown = await response.json();
    return typeof body === 'object' && body !== null ? (body as ProblemDetails) : null;
  } catch {
    return null;
  }
}
