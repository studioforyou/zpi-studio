/** The signed-in administrator, built from `GET /api/v1/me`. */
export type Session = {
  accountId: string;
  email: string;
  displayName: string;
  roles: string[];
};

/** Subset of the `GET /api/v1/me` response that the panel needs. */
export type MeResponse = {
  accountId: string;
  email: string;
  status: string;
  displayName: string;
  roles: string[];
};
