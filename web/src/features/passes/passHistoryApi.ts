import { apiFetch } from '@/shared/api/apiClient';
import type { Page } from './customerPassesApi';

export const passHistoryCategories = [
  'ENTRIES',
  'CORRECTIONS',
  'VALIDITY',
  'MAKEUP',
  'PASS',
] as const;
/** Which kinds of changes the history shows. */
export type PassHistoryCategory = (typeof passHistoryCategories)[number];

export type PassHistoryKind =
  | 'ACTIVATED'
  | 'ENTRY_RESERVED'
  | 'ENTRY_USED'
  | 'ENTRY_RETURNED'
  | 'CORRECTED'
  | 'DISPUTE_REFUND'
  | 'EXTENDED'
  | 'SUSPENDED'
  | 'SUSPENSION_CANCELLED'
  | 'EXPIRED'
  | 'MAKEUP_GRANTED'
  | 'RENEWAL_DECLINED';

/** Who the author of a change is to the participant. */
export type PassHistoryActorRole = 'CLIENT' | 'GUARDIAN' | 'STAFF';

export type PassHistoryActor = {
  /** `null` when the account has no name. */
  name: string | null;
  role: PassHistoryActorRole;
};

/** A reservation of the pass's holder, with its event. */
export type PassHistorySession = {
  reservationId: string;
  eventId: string | null;
  title: string | null;
  serviceTypeName: string | null;
  startsAt: string | null;
  endsAt: string | null;
  /** The studio cancelled the event. */
  eventCancelled: boolean;
  source: 'SELF' | 'STAFF' | 'WAITLIST';
  bookedBy: PassHistoryActor | null;
  status: 'ACTIVE' | 'CANCELLED';
  cancelKind: 'IN_TIME' | 'LATE' | 'STUDIO' | null;
  cancelledAt: string | null;
  cancelledBy: PassHistoryActor | null;
  attendance: 'UNMARKED' | 'PRESENT' | 'ABSENT';
};

export type PassHistoryEntry = {
  id: string;
  kind: PassHistoryKind;
  category: PassHistoryCategory;
  occurredAt: string;
  /** `null` for the system, and for a declined renewal (always the participant or a guardian). */
  actor: PassHistoryActor | null;
  /** The note given with the change; internal. */
  reason: string | null;
  /** The signed change of the balance; `null` when the change does not touch it. */
  entriesChange: number | null;
  balanceAfter: number | null;
  /** `yyyy-MM-dd`. */
  validUntilBefore: string | null;
  validUntilAfter: string | null;
  /** The suspended days or, for a cancelled suspension, the days given back (`yyyy-MM-dd`, inclusive). */
  suspension: { startsOn: string; endsOn: string } | null;
  session: PassHistorySession | null;
  makeupCredit: {
    id: string;
    expiresAt: string;
    state: 'AVAILABLE' | 'USED' | 'EXPIRED';
    usedAt: string | null;
    usedFor: PassHistorySession | null;
  } | null;
};

export type PassHistory = {
  pass: {
    id: string;
    customerId: string;
    productName: string;
    entriesTotal: number | null;
    entriesRemaining: number | null;
    /** What the history adds up to; differs from `entriesRemaining` when it misses changes. */
    ledgerBalance: number | null;
    purchased: boolean;
  };
  /** Changes of each category in the whole history. */
  counts: Record<PassHistoryCategory, number>;
  entries: Page<PassHistoryEntry>;
};

export type PassHistorySearch = {
  customerId: string;
  passId: string;
  category: PassHistoryCategory | null;
  /** 0-based. */
  page: number;
  size: number;
};

export const passHistoryKeys = {
  // Under 'customer-passes', so every change of a pass refreshes its history too.
  search: (search: PassHistorySearch) => ['customer-passes', 'history', search] as const,
};

/** One page of a participant's pass changes, newest first. */
export function getPassHistory(search: PassHistorySearch): Promise<PassHistory> {
  const params = new URLSearchParams({ size: String(search.size) });
  if (search.page > 0) {
    params.set('page', String(search.page));
  }
  if (search.category) {
    params.set('category', search.category);
  }
  return apiFetch<PassHistory>(
    `/api/v1/customers/${search.customerId}/passes/${search.passId}/history?${params.toString()}`,
  );
}
