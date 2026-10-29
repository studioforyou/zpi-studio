import { ApiError } from '@/shared/api/ApiError';
import { apiFetch } from '@/shared/api/apiClient';
import { pl } from '@/shared/i18n/pl';

/** A trainer currently assigned to a participant. */
export type CustomerTrainer = {
  staffId: string;
  firstName: string;
  lastName: string;
  displayName: string;
  /** Whether the trainer's staff profile is still active. */
  isActive: boolean;
  assignedBy: string;
  /** ISO instant. */
  assignedAt: string;
};

/** An active trainer that can be assigned to participants. */
export type AssignableTrainer = {
  staffId: string;
  firstName: string;
  lastName: string;
  displayName: string;
};

export const traineeKeys = {
  trainers: (customerId: string) => ['customer-trainers', customerId] as const,
  assignable: ['assignable-trainers'] as const,
};

export async function listCustomerTrainers(customerId: string): Promise<CustomerTrainer[]> {
  const response = await apiFetch<{ items: CustomerTrainer[] }>(
    `/api/v1/customers/${customerId}/trainers`,
  );
  return response.items;
}

export function assignCustomerTrainer(
  customerId: string,
  staffId: string,
): Promise<CustomerTrainer> {
  return apiFetch<CustomerTrainer>(`/api/v1/customers/${customerId}/trainers`, {
    method: 'POST',
    body: { staffId },
  });
}

/** Ends the assignment; the server keeps it as history. */
export function unassignCustomerTrainer(customerId: string, staffId: string): Promise<void> {
  return apiFetch<void>(`/api/v1/customers/${customerId}/trainers/${staffId}`, {
    method: 'DELETE',
  });
}

export async function listAssignableTrainers(): Promise<AssignableTrainer[]> {
  const response = await apiFetch<{ items: AssignableTrainer[] }>(
    '/api/v1/customers/assignable-trainers',
  );
  return response.items;
}

export function traineeErrorMessage(error: unknown): string {
  if (error instanceof ApiError && error.code !== null) {
    const known = pl.trainees.errors[error.code as keyof typeof pl.trainees.errors];
    if (known) {
      return known;
    }
  }
  return pl.trainees.errors.unknown;
}
