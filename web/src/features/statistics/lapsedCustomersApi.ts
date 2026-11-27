import { apiFetch } from '@/shared/api/apiClient';

/** Thresholds the panel offers, in days since the last visit. */
export const inactiveDayOptions = [14, 30, 60, 90] as const;
export type InactiveDays = (typeof inactiveDayOptions)[number];
export const defaultInactiveDays: InactiveDays = 30;

/** VALID: usable now or later; SUSPENDED: usable but suspended; ENDED: expired or out of entries. */
export type LapsedPassState = 'VALID' | 'SUSPENDED' | 'ENDED';

export type LapsedCustomerPass = {
  productName: string;
  state: LapsedPassState;
  /** Last valid day; null when the pass has no end. */
  validUntil: string | null;
  /** Entries left; null for a pass without an entry limit. */
  entriesRemaining: number | null;
};

/** A participant who stopped coming, with the contact the studio reaches them by. */
export type LapsedCustomer = {
  profileId: string;
  kind: 'ADULT' | 'CHILD';
  firstName: string;
  lastName: string;
  /** The participant's own account or, for a child, its guardian's. */
  contactAccountId: string | null;
  /** Only for a child: the guardian contacted. */
  guardianName: string | null;
  email: string | null;
  phone: string | null;
  /** Studio date of the last visit. */
  lastVisit: string;
  daysSinceLastVisit: number;
  /** Visits in the 90 days up to and including the last one. */
  recentVisits: number;
  pass: LapsedCustomerPass | null;
  /** Whether the person contacted accepted marketing notifications in the app. */
  marketingConsent: boolean;
  /** Studio date of the last announcement addressed to the person contacted alone. */
  lastAnnouncement: string | null;
};

/** Response of `GET /api/v1/reports/lapsed-customers`, sorted from the longest absent. */
export type LapsedCustomersReport = {
  asOf: string;
  inactiveDays: number;
  /** Customers counted the same way who visited the studio at least once. */
  everVisited: number;
  customers: LapsedCustomer[];
};

export const lapsedCustomersKeys = {
  all: ['lapsed-customers'] as const,
  threshold: (inactiveDays: number) => [...lapsedCustomersKeys.all, inactiveDays] as const,
};

export function getLapsedCustomers(inactiveDays: number): Promise<LapsedCustomersReport> {
  const query = new URLSearchParams({ inactiveDays: String(inactiveDays) });
  return apiFetch<LapsedCustomersReport>(`/api/v1/reports/lapsed-customers?${query}`);
}
