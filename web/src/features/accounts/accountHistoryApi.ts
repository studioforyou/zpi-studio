import { apiFetch } from '@/shared/api/apiClient';
import type { Page } from '@/features/passes/customerPassesApi';
import type { OverrideEffect } from './accessApi';
import { accountKeys, type AccountStatus } from './accountsApi';

export const accountHistoryTypes = [
  'STATUS_CHANGED',
  'ROLE_GRANTED',
  'ROLE_REVOKED',
  'PERMISSION_OVERRIDE_ADDED',
  'PERMISSION_OVERRIDE_REMOVED',
] as const;
export type AccountHistoryType = (typeof accountHistoryTypes)[number];

/** The kinds of change the history can be narrowed to, each a group of entry types. */
export const historyCategories = {
  STATUS: ['STATUS_CHANGED'],
  ROLES: ['ROLE_GRANTED', 'ROLE_REVOKED'],
  PERMISSIONS: ['PERMISSION_OVERRIDE_ADDED', 'PERMISSION_OVERRIDE_REMOVED'],
} as const satisfies Record<string, readonly AccountHistoryType[]>;
export type HistoryCategory = keyof typeof historyCategories;

/** An account with the name of the person it belongs to (or its e-mail without a profile). */
export type HistoryPerson = {
  accountId: string;
  displayName: string;
};

/** One change of an account's status, roles or permissions; other types' fields are `null`. */
export type AccountHistoryEntry = {
  id: string;
  occurredAt: string;
  type: AccountHistoryType;
  account: HistoryPerson;
  /** Who made the change; `null` for the system. */
  actor: HistoryPerson | null;
  /** `null` when the account was created. */
  fromStatus: AccountStatus | null;
  toStatus: AccountStatus | null;
  role: string | null;
  permission: string | null;
  /** The added override's effect, or the removed one's. */
  effect: OverrideEffect | null;
  /** Only what the person who made the change wrote. */
  reason: string | null;
};

export type AccountHistorySearch = {
  accountId?: string | null;
  actorId?: string | null;
  category?: HistoryCategory | null;
  /** Part of the changed account's name or e-mail. */
  query?: string;
  /** Studio dates `YYYY-MM-DD`; empty for no limit. */
  from?: string;
  to?: string;
  page: number;
};

export const ACCOUNT_HISTORY_PAGE_SIZE = 20;

export const accountHistoryKeys = {
  // Under the accounts, so every change of an account also refreshes the history.
  all: [...accountKeys.all, 'history'] as const,
  search: (search: AccountHistorySearch) => [...accountHistoryKeys.all, search] as const,
  actors: () => [...accountHistoryKeys.all, 'actors'] as const,
};

/** Newest first. */
export function searchAccountHistory(
  search: AccountHistorySearch,
): Promise<Page<AccountHistoryEntry>> {
  const params = new URLSearchParams({
    page: String(search.page),
    size: String(ACCOUNT_HISTORY_PAGE_SIZE),
  });
  if (search.accountId) {
    params.set('accountId', search.accountId);
  }
  if (search.actorId) {
    params.set('actorId', search.actorId);
  }
  for (const type of search.category ? historyCategories[search.category] : []) {
    params.append('type', type);
  }
  if (search.query && search.query.trim() !== '') {
    params.set('query', search.query.trim());
  }
  if (search.from) {
    params.set('from', search.from);
  }
  if (search.to) {
    params.set('to', search.to);
  }
  return apiFetch<Page<AccountHistoryEntry>>(`/api/v1/account-history?${params.toString()}`);
}

/** Everyone who changed an account, sorted by name. */
export async function listHistoryActors(): Promise<HistoryPerson[]> {
  return (await apiFetch<{ items: HistoryPerson[] }>('/api/v1/account-history/actors')).items;
}
