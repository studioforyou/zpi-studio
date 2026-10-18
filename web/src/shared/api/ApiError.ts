import type { ProblemDetails } from './problem';

/** Client-side code for requests that never got a response; not part of the server's error catalog. */
export const NETWORK_ERROR = 'NETWORK_ERROR';

export class ApiError extends Error {
  readonly status: number | null;
  readonly code: string | null;
  readonly problem: ProblemDetails | null;

  constructor(status: number | null, code: string | null, problem: ProblemDetails | null) {
    super(problem?.detail ?? (status === null ? 'Network error' : `HTTP ${status}`));
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.problem = problem;
  }
}
