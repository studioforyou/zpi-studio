/** RFC 9457 Problem Details with the API's stable `code` (API_CONVENTIONS.md, section 6). */
export type ProblemDetails = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  errors?: { field: string; code: string; message: string }[];
};
