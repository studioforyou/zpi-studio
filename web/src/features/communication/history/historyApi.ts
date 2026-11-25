import { apiFetch } from '@/shared/api/apiClient';
import type { Page } from '@/features/passes/customerPassesApi';

export const sendStatuses = ['READ', 'DELIVERED', 'PUSH_FAILED', 'APP_ONLY'] as const;
export type SendStatus = (typeof sendStatuses)[number];

export type HistoryItem = {
  id: string;
  /** Notification type code, e.g. `CLASS_REMINDER` or `ANNOUNCEMENT`. */
  type: string;
  recipientId: string;
  recipientName: string;
  recipientRole: 'CLIENT' | 'TRAINER' | 'ADMIN' | null;
  /** The participant it concerns when the recipient gets it for them, e.g. a child. */
  about: string | null;
  title: string;
  /** `null` when hidden: chat message contents are never shown. */
  body: string | null;
  bodyHidden: boolean;
  marketing: boolean;
  announcementId: string | null;
  /** Who sent it, for announcements. */
  author: string | null;
  sentAt: string;
  readAt: string | null;
  status: SendStatus;
};

export type DeliveryAttempt = {
  attemptedAt: string;
  channel: string;
  status: 'SENT' | 'FAILED' | 'TOKEN_INVALID' | 'NO_DEVICE' | 'SKIPPED';
  error: string | null;
};

export type HistoryDetails = { notification: HistoryItem; deliveries: DeliveryAttempt[] };

export type HistoryFilter = {
  type: string | null;
  accountId: string | null;
  from: string | null;
  to: string | null;
  status: SendStatus | null;
};

export const HISTORY_PAGE_SIZE = 20;

export const historyKeys = {
  all: ['notification-history'] as const,
  list: (filter: HistoryFilter, size: number) =>
    [...historyKeys.all, 'list', filter, size] as const,
  details: (id: string) => [...historyKeys.all, 'details', id] as const,
};

export function listHistory(filter: HistoryFilter, size: number): Promise<Page<HistoryItem>> {
  const params = new URLSearchParams({ page: '0', size: String(size) });
  for (const [key, value] of Object.entries(filter)) {
    if (value) params.set(key, value);
  }
  return apiFetch<Page<HistoryItem>>(`/api/v1/notification-history?${params.toString()}`);
}

export function getHistoryItem(id: string): Promise<HistoryDetails> {
  return apiFetch<HistoryDetails>(`/api/v1/notification-history/${id}`);
}
