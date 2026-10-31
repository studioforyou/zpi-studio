import { apiFetch } from '@/shared/api/apiClient';
import type { Page } from '@/features/passes/customerPassesApi';

export const accountStatuses = [
  'ACTIVE',
  'INVITED',
  'PENDING_APPROVAL',
  'SUSPENDED',
  'REJECTED',
  'CLOSED',
] as const;
export type AccountStatus = (typeof accountStatuses)[number];

export const accountRoles = ['CLIENT', 'TRAINER', 'ADMIN'] as const;
export type AccountRole = (typeof accountRoles)[number];

/** An account on the list; name and phone come from its customer or staff profile. */
export type AccountSummary = {
  id: string;
  email: string;
  status: AccountStatus;
  roles: string[];
  /** First and last name, or the e-mail of an account without a profile. */
  displayName: string;
  firstName: string | null;
  lastName: string | null;
  phone: string | null;
  createdAt: string;
};

export type AccountDetails = AccountSummary & {
  activatedAt: string | null;
  customerProfileId: string | null;
  staffProfileId: string | null;
  /** When the pending invitation expires; `null` without one. */
  invitationExpiresAt: string | null;
  /** Whether the person has a login (password) yet. */
  hasLogin: boolean;
};

export type AccountSearch = {
  query: string;
  role: AccountRole | null;
  /** `null` = every status but `CLOSED`. */
  status: AccountStatus | null;
  page: number;
};

export type UpdateAccount = {
  email: string;
  firstName: string;
  lastName: string;
  /** `null` clears the phone number. */
  phone: string | null;
};

export const ACCOUNTS_PAGE_SIZE = 20;

export const accountKeys = {
  all: ['accounts'] as const,
  list: (search: AccountSearch) => [...accountKeys.all, 'list', search] as const,
  details: (accountId: string) => [...accountKeys.all, accountId] as const,
};

export function searchAccounts(search: AccountSearch): Promise<Page<AccountSummary>> {
  const params = new URLSearchParams({
    page: String(search.page),
    size: String(ACCOUNTS_PAGE_SIZE),
  });
  if (search.query.trim() !== '') {
    params.set('query', search.query.trim());
  }
  if (search.role) {
    params.set('role', search.role);
  }
  if (search.status) {
    params.set('status', search.status);
  }
  return apiFetch<Page<AccountSummary>>(`/api/v1/accounts?${params.toString()}`);
}

export function getAccount(accountId: string): Promise<AccountDetails> {
  return apiFetch<AccountDetails>(`/api/v1/accounts/${accountId}`);
}

export function updateAccount(accountId: string, update: UpdateAccount): Promise<AccountDetails> {
  return apiFetch<AccountDetails>(`/api/v1/accounts/${accountId}`, { method: 'PUT', body: update });
}

export function suspendAccount(accountId: string, reason: string | null): Promise<AccountDetails> {
  return apiFetch<AccountDetails>(`/api/v1/accounts/${accountId}/suspend`, {
    method: 'POST',
    body: { reason },
  });
}

export function reactivateAccount(
  accountId: string,
  reason: string | null,
): Promise<AccountDetails> {
  return apiFetch<AccountDetails>(`/api/v1/accounts/${accountId}/reactivate`, {
    method: 'POST',
    body: { reason },
  });
}

export function closeAccount(accountId: string, reason: string | null): Promise<void> {
  const query = reason ? `?${new URLSearchParams({ reason }).toString()}` : '';
  return apiFetch<void>(`/api/v1/accounts/${accountId}${query}`, { method: 'DELETE' });
}

/** The calendar day of an API timestamp, e.g. `31.10.2026`. */
export function formatTimestampDate(timestamp: string): string {
  return new Date(timestamp).toLocaleDateString('pl-PL', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  });
}
