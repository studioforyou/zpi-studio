import type { ProblemDetails } from './problem';

// Client-side codes, not part of the server's error catalog.
/** The request never got a response. */
export const NETWORK_ERROR = 'NETWORK_ERROR';
/** A successful response whose body could not be read in the expected format. */
export const INVALID_RESPONSE = 'INVALID_RESPONSE';

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
