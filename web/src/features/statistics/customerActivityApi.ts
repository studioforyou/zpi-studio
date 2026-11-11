import { apiFetch } from '@/shared/api/apiClient';

/** Customer counts in a period of studio dates (`from` and `to` inclusive). */
export type CustomerActivity = {
  from: string;
  to: string;
  /** Participants with a valid pass or a reservation for an event in the period. */
  activeCustomers: number;
  /** Client accounts activated and profiles without an account added in the period. */
  newCustomers: number;
};

/** Response of `GET /api/v1/reports/customer-activity`. */
export type CustomerActivityReport = {
  current: CustomerActivity;
  /** The period of the same length right before `current`. */
  previous: CustomerActivity;
};

export const customerActivityKeys = {
  all: ['customer-activity'] as const,
  period: (from: string, to: string) => [...customerActivityKeys.all, from, to] as const,
};

export function getCustomerActivity(from: string, to: string): Promise<CustomerActivityReport> {
  const query = new URLSearchParams({ from, to });
  return apiFetch<CustomerActivityReport>(`/api/v1/reports/customer-activity?${query}`);
}
