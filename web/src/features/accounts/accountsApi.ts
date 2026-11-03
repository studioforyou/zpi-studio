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

/** Role filters of the list; `CHILD` finds the children of accounts. */
export const accountRoles = ['CLIENT', 'TRAINER', 'ADMIN', 'CHILD'] as const;
export type AccountRole = (typeof accountRoles)[number];

/**
 * A person on the list: the owner of an account (its client and staff profile are one person) or a
 * child of the account. E-mail, status and creation date are the account's.
 */
export type AccountSummary = {
  /** The account. */
  id: string;
  /** The person's profile; `null` for an account without a profile. */
  profileId: string | null;
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
  /** The own client profile, the staff profile, then the children, each if there is one. */
  profiles: AccountProfile[];
};

export type ProfileKind = 'CLIENT' | 'STAFF' | 'CHILD';

/** One profile of an account; fields its kind does not have are `null`. */
export type AccountProfile = {
  id: string;
  kind: ProfileKind;
  firstName: string;
  lastName: string;
  phone: string | null;
  bio: string | null;
  /** `YYYY-MM-DD`. */
  birthDate: string | null;
  emergencyContactName: string | null;
  emergencyPhone: string | null;
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

export type UpdateStaffProfile = {
  firstName: string;
  lastName: string;
  phone: string | null;
  bio: string | null;
};

export type UpdateChild = {
  firstName: string;
  lastName: string;
  birthDate: string | null;
  emergencyContactName: string | null;
  emergencyPhone: string | null;
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

/** Also saves the names in the account's own client profile. */
export function updateStaffProfile(
  accountId: string,
  update: UpdateStaffProfile,
): Promise<AccountDetails> {
  return apiFetch<AccountDetails>(`/api/v1/accounts/${accountId}/staff-profile`, {
    method: 'PUT',
    body: update,
  });
}

export function updateChild(
  accountId: string,
  childId: string,
  update: UpdateChild,
): Promise<AccountDetails> {
  return apiFetch<AccountDetails>(`/api/v1/accounts/${accountId}/children/${childId}`, {
    method: 'PUT',
    body: update,
  });
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
