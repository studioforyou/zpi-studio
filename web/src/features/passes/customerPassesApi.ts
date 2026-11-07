import { apiFetch } from '@/shared/api/apiClient';
import type { Money } from './passesApi';

export type CustomerKind = 'ADULT' | 'CHILD';

export type Customer = {
  id: string;
  kind: CustomerKind;
  firstName: string;
  lastName: string;
  displayName: string;
  /** `yyyy-MM-dd`, or `null` if unknown. */
  birthDate: string | null;
  /**
   * For administrators: the address of the participant's account or, for a child, of its
   * guardian's account; `null` when there is none (and always for trainers).
   */
  email: string | null;
  /** Whether `email` belongs to the child's guardian. */
  emailOfGuardian: boolean;
};

/** The participant holding a pass. */
export type PassHolder = {
  id: string;
  kind: CustomerKind;
  firstName: string;
  lastName: string;
  displayName: string;
  /** The participant's address or, for a child, its guardian's; `null` when there is none. */
  email: string | null;
  emailOfGuardian: boolean;
};

/** A pass found by a pass search, with its holder. */
export type HeldPass = {
  holder: PassHolder;
  pass: CustomerPass;
};

export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
};

export const customerPassStatuses = [
  'PENDING_PAYMENT',
  'SCHEDULED',
  'ACTIVE',
  'SUSPENDED',
  'EXPIRED',
  'CANCELLED',
] as const;
export type CustomerPassStatus = (typeof customerPassStatuses)[number];

/** A period in which a pass cannot be used; it moved the pass's last day. */
export type PassSuspension = {
  id: string;
  /** `yyyy-MM-dd`. */
  startsOn: string;
  /** The last suspended day, inclusive (`yyyy-MM-dd`). */
  endsOn: string;
  reason: string | null;
  createdBy: string;
  createdAt: string;
};

export type CustomerPass = {
  id: string;
  customerId: string;
  variantId: string;
  productName: string;
  /** `null` = unlimited entries. */
  entriesTotal: number | null;
  entriesRemaining: number | null;
  pricePaid: Money;
  status: CustomerPassStatus;
  /** `yyyy-MM-dd`. */
  validFrom: string | null;
  /** The last valid day, inclusive (`yyyy-MM-dd`). */
  validUntil: string | null;
  orderItemId: string | null;
  createdBy: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  /** Suspensions that were not cancelled, oldest first. */
  suspensions: PassSuspension[];
};

export type AssignPass = {
  variantId: string;
  /** `yyyy-MM-dd`; a later date than today schedules the pass. */
  validFrom: string;
  /** `null` = the variant's price. */
  pricePaid: Money | null;
  reason: string | null;
};

/**
 * Active participants (adults and children) matching part of their name, phone number or, for
 * administrators, e-mail address.
 */
export function searchCustomers(query: string, size = 20, page = 0): Promise<Page<Customer>> {
  const params = new URLSearchParams({ size: String(size) });
  if (page > 0) {
    params.set('page', String(page));
  }
  if (query.trim() !== '') {
    params.set('query', query.trim());
  }
  return apiFetch<Page<Customer>>(`/api/v1/customers?${params.toString()}`);
}

/**
 * Passes of all active participants matching part of their name, phone number or e-mail
 * address, by participant and newest first.
 */
export function searchCustomerPasses(query: string, size = 20, page = 0): Promise<Page<HeldPass>> {
  const params = new URLSearchParams({ query: query.trim(), size: String(size) });
  if (page > 0) {
    params.set('page', String(page));
  }
  return apiFetch<Page<HeldPass>>(`/api/v1/customer-passes?${params.toString()}`);
}

export async function listCustomerPasses(customerId: string): Promise<CustomerPass[]> {
  const response = await apiFetch<{ items: CustomerPass[] }>(
    `/api/v1/customers/${customerId}/passes`,
  );
  return response.items;
}

export function assignCustomerPass(customerId: string, pass: AssignPass): Promise<CustomerPass> {
  return apiFetch<CustomerPass>(`/api/v1/customers/${customerId}/passes`, {
    method: 'POST',
    body: pass,
  });
}

export type ExtendPass = {
  /** The new last day, inclusive (`yyyy-MM-dd`); later than the current one. */
  validUntil: string;
  reason: string | null;
  version: number;
};

export type SuspendPass = {
  /** `yyyy-MM-dd`, today or later. */
  startsOn: string;
  /** The last suspended day, inclusive (`yyyy-MM-dd`). */
  endsOn: string;
  reason: string | null;
  version: number;
};

export function extendCustomerPass(
  customerId: string,
  passId: string,
  extension: ExtendPass,
): Promise<CustomerPass> {
  return apiFetch<CustomerPass>(`/api/v1/customers/${customerId}/passes/${passId}/extend`, {
    method: 'POST',
    body: extension,
  });
}

export function suspendCustomerPass(
  customerId: string,
  passId: string,
  suspension: SuspendPass,
): Promise<CustomerPass> {
  return apiFetch<CustomerPass>(`/api/v1/customers/${customerId}/passes/${passId}/suspend`, {
    method: 'POST',
    body: suspension,
  });
}

export function cancelPassSuspension(
  customerId: string,
  passId: string,
  suspensionId: string,
  version: number,
): Promise<CustomerPass> {
  return apiFetch<CustomerPass>(
    `/api/v1/customers/${customerId}/passes/${passId}/suspensions/${suspensionId}/cancel`,
    { method: 'POST', body: { version } },
  );
}

export function getCustomerPass(customerId: string, passId: string): Promise<CustomerPass> {
  return apiFetch<CustomerPass>(`/api/v1/customers/${customerId}/passes/${passId}`);
}

export type CorrectPassEntries = {
  entriesDelta: number;
  reason: string | null;
  version: number;
};

export function correctPassEntries(
  customerId: string,
  passId: string,
  correction: CorrectPassEntries,
): Promise<CustomerPass> {
  return apiFetch<CustomerPass>(
    `/api/v1/customers/${customerId}/passes/${passId}/correct-entries`,
    { method: 'POST', body: correction },
  );
}
