import { apiFetch } from '@/shared/api/apiClient';

export type DisputeStatus = 'OPEN' | 'RESOLVED';

/** UPHELD gives back what paid for the session, UPHELD_MAKEUP grants a new make-up instead. */
export const disputeResolutions = ['UPHELD', 'UPHELD_MAKEUP', 'REJECTED'] as const;
export type DisputeResolution = (typeof disputeResolutions)[number];

export type DisputeEffect = 'ENTRY_RETURNED' | 'MAKEUP_RETURNED' | 'MAKEUP_GRANTED' | 'NO_CHANGE';

/** How the session was paid for in the application. */
export type DisputeFunding = 'PASS' | 'UNLIMITED_PASS' | 'MAKEUP' | 'NONE';

export type DisputeNotice =
  | 'PASS_EXPIRED'
  | 'PASS_CANCELLED'
  | 'NO_ALLOCATED_ENTRY'
  | 'UNLIMITED_PASS'
  | 'NOT_FUNDED'
  | 'MAKEUP_EXPIRED'
  | 'MAKEUP_NOT_USABLE';

export type DisputeOption = {
  allowed: boolean;
  effect: DisputeEffect;
  /** Why it is not available, or what to know before choosing it. */
  notice: DisputeNotice | null;
};

export type DisputeOptions = {
  funding: DisputeFunding;
  passName: string | null;
  entriesRemaining: number | null;
  passValidUntil: string | null;
  makeupExpiresAt: string | null;
  upheld: DisputeOption;
  upheldMakeup: DisputeOption;
  rejected: DisputeOption;
  /** Suggested validity of a granted make-up credit; `null` when none can be granted. */
  makeupValidityDays: number | null;
};

export type DisputeStaff = { staffId: string; displayName: string; role: string };

/** A client's dispute about a held personal session. */
export type Dispute = {
  id: string;
  status: DisputeStatus;
  reason: string;
  raisedAt: string;
  raisedByName: string | null;
  reservationId: string;
  /** Sent back with the decision; a stale one is refused. */
  reservationVersion: number;
  customerId: string;
  customerKind: 'ADULT' | 'CHILD';
  customerName: string;
  /** Only on a single dispute: the participant's own account or their guardian's. */
  customerAccountId: string | null;
  eventId: string;
  title: string;
  serviceTypeName: string;
  startsAt: string;
  endsAt: string;
  eventStatus: string;
  staff: DisputeStaff[];
  attendance: 'UNMARKED' | 'PRESENT' | 'ABSENT';
  trainerConfirmedAt: string | null;
  resolution: DisputeResolution | null;
  resolutionEffect: DisputeEffect | null;
  resolutionNote: string | null;
  resolvedAt: string | null;
  resolvedByName: string | null;
  /** Only on a single open dispute. */
  options: DisputeOptions | null;
};

export type DisputePage = {
  items: Dispute[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
};

export type ResolveDisputeInput = {
  resolution: DisputeResolution;
  note: string;
  makeupValidityDays: number | null;
  version: number;
};

export const DISPUTES_PAGE_SIZE = 20;

export const disputeKeys = {
  all: ['personal-training-disputes'] as const,
  list: (status: DisputeStatus, page: number) =>
    [...disputeKeys.all, 'list', status, page] as const,
  detail: (id: string) => [...disputeKeys.all, 'detail', id] as const,
  openCount: () => [...disputeKeys.all, 'open-count'] as const,
};

const BASE = '/api/v1/personal-training-disputes';

/** `page` is 0-based, as in the API. */
export function listDisputes(
  status: DisputeStatus,
  page: number,
  size = DISPUTES_PAGE_SIZE,
): Promise<DisputePage> {
  const params = new URLSearchParams({ status, page: String(page), size: String(size) });
  return apiFetch<DisputePage>(`${BASE}?${params}`);
}

/** How many disputes wait for a decision. */
export async function countOpenDisputes(): Promise<number> {
  return (await listDisputes('OPEN', 0, 1)).totalItems;
}

export function getDispute(id: string): Promise<Dispute> {
  return apiFetch<Dispute>(`${BASE}/${id}`);
}

export function resolveDispute(id: string, input: ResolveDisputeInput): Promise<Dispute> {
  return apiFetch<Dispute>(`${BASE}/${id}/resolve`, { method: 'POST', body: input });
}
