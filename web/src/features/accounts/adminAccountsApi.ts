import { apiFetch } from '@/shared/api/apiClient';

export type CreateAdminRequest = {
  email: string;
  firstName: string;
  lastName: string;
};

/** Response of `POST /api/v1/admin-accounts`. */
export type NewAdminAccount = {
  accountId: string;
  staffProfileId: string;
  email: string;
  /** The generated password; the backend returns it only once and does not keep it. */
  password: string;
};

export function createAdminAccount(request: CreateAdminRequest): Promise<NewAdminAccount> {
  return apiFetch<NewAdminAccount>('/api/v1/admin-accounts', { method: 'POST', body: request });
}
