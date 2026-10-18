/** Subset of the login response (API_CONVENTIONS.md, section 5) that the panel needs. */
export type Session = {
  accountId: string;
  email: string;
  displayName: string;
  roles: string[];
};

export type LoginResponse = {
  accessToken: string;
  refreshToken: string;
  account: Session;
};
