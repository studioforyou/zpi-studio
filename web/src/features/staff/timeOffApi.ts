import { apiFetch } from '@/shared/api/apiClient';

export const timeOffKinds = ['VACATION', 'SICK_LEAVE', 'UNAVAILABLE'] as const;
export type TimeOffKind = (typeof timeOffKinds)[number];

export const timeOffStatuses = ['REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED'] as const;
export type TimeOffStatus = (typeof timeOffStatuses)[number];

/** Leave or unavailability of a staff member; `endsAt` is exclusive. */
export type TimeOff = {
  id: string;
  staffId: string;
  staffDisplayName: string;
  kind: TimeOffKind;
  status: TimeOffStatus;
  startsAt: string;
  endsAt: string;
  reason: string | null;
  requestedAt: string;
  /** When it was approved or rejected; `null` while it waits for a decision. */
  decidedAt: string | null;
  decisionNote: string | null;
  /** Who approved or rejected it; `null` while it waits for a decision. */
  decidedByName: string | null;
  /** Recorded by the studio and approved at once, not requested by the trainer. */
  recordedByStudio: boolean;
};

export type TimeOffQuery = {
  statuses?: TimeOffStatus[];
  staffId?: string | null;
  /** Only time off that ends after this instant. */
  from?: string;
  /** Only time off that starts before this instant. */
  to?: string;
};

export type RecordTimeOffInput = {
  staffId: string;
  kind: TimeOffKind;
  startsAt: string;
  endsAt: string;
  reason: string | null;
};

type ListResponse<T> = { items: T[] };

export const timeOffKeys = {
  all: ['time-off'] as const,
  list: (query: TimeOffQuery) => [...timeOffKeys.all, 'list', query] as const,
  pendingCount: () => [...timeOffKeys.all, 'pending-count'] as const,
};

export async function listTimeOff(query: TimeOffQuery): Promise<TimeOff[]> {
  const params = new URLSearchParams();
  for (const status of query.statuses ?? []) {
    params.append('status', status);
  }
  if (query.staffId) {
    params.set('staffId', query.staffId);
  }
  if (query.from) {
    params.set('from', query.from);
  }
  if (query.to) {
    params.set('to', query.to);
  }
  const search = params.toString();
  const response = await apiFetch<ListResponse<TimeOff>>(
    `/api/v1/staff/time-off${search ? `?${search}` : ''}`,
  );
  return response.items;
}

/** Requests that wait for a decision and have not ended yet. */
export async function countPendingTimeOff(): Promise<number> {
  const now = new Date(Math.floor(Date.now() / 60000) * 60000).toISOString();
  return (await listTimeOff({ statuses: ['REQUESTED'], from: now })).length;
}

export function recordTimeOff(input: RecordTimeOffInput): Promise<TimeOff> {
  return apiFetch<TimeOff>('/api/v1/staff/time-off', { method: 'POST', body: input });
}

export function approveTimeOff(id: string, note: string | null): Promise<TimeOff> {
  return apiFetch<TimeOff>(`/api/v1/staff/time-off/${id}/approve`, {
    method: 'POST',
    body: { note },
  });
}

export function rejectTimeOff(id: string, note: string | null): Promise<TimeOff> {
  return apiFetch<TimeOff>(`/api/v1/staff/time-off/${id}/reject`, {
    method: 'POST',
    body: { note },
  });
}

export function cancelTimeOff(id: string): Promise<TimeOff> {
  return apiFetch<TimeOff>(`/api/v1/staff/time-off/${id}/cancel`, { method: 'POST' });
}
