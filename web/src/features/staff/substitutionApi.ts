import { apiFetch } from '@/shared/api/apiClient';
import { ApiError, NETWORK_ERROR } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import { scheduleErrorMessage } from '@/features/schedule/scheduleFormat';

export type SubstitutionStatus =
  'OPEN' | 'CLAIMED' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED';
export type Substitution = {
  id: string;
  eventId: string;
  version: number;
  eventVersion: number;
  status: SubstitutionStatus;
  kind: string;
  title: string;
  rooms: string;
  startsAt: string;
  endsAt: string;
  originalStaffId: string;
  originalTrainer: string;
  substituteStaffId: string | null;
  substituteTrainer: string | null;
  approvalRequired: boolean;
  decisionNote: string | null;
};

export type SubstitutionCandidate = {
  staffId: string;
  displayName: string;
  available: boolean;
  reason: string | null;
  warnings: string[];
};

export const substitutionKeys = {
  all: ['substitutions'] as const,
  list: (from: string, to: string) => ['substitutions', 'list', from, to] as const,
  detail: (id: string) => ['substitutions', 'detail', id] as const,
  candidates: (id: string) => ['substitutions', 'candidates', id] as const,
};

const path = '/api/v1/substitution-requests';
export async function listSubstitutions(from: string, to: string): Promise<Substitution[]> {
  const result = await apiFetch<{ items: Substitution[] }>(
    `${path}?${new URLSearchParams({ from, to })}`,
  );
  return result.items;
}

export function getSubstitution(id: string): Promise<Substitution> {
  return apiFetch<Substitution>(`${path}/${id}`);
}

export async function substitutionCandidates(id: string): Promise<SubstitutionCandidate[]> {
  return (await apiFetch<{ items: SubstitutionCandidate[] }>(`${path}/${id}/candidates`)).items;
}

export function decideSubstitution(
  offer: Substitution,
  action: 'approve' | 'reject' | 'assign' | 'close',
  note: string,
  staffId?: string,
): Promise<Substitution> {
  return apiFetch<Substitution>(`${path}/${offer.id}/${action}`, {
    method: 'POST',
    body: { version: offer.version, note: note.trim() || null, ...(staffId ? { staffId } : {}) },
  });
}

export function substitutionError(error: Error): string {
  if (error instanceof ApiError) {
    if (error.code === NETWORK_ERROR) return pl.substitutions.networkError;
    if (error.code === 'VERSION_CONFLICT' || error.code === 'SUBSTITUTION_ALREADY_DECIDED')
      return pl.substitutions.changedError;
    if (error.code === 'SUBSTITUTION_NOT_ELIGIBLE') return pl.substitutions.unavailableError;
  }
  return scheduleErrorMessage(error);
}

export const substitutionColors: Record<SubstitutionStatus, string> = {
  OPEN: 'blue',
  CLAIMED: 'orange',
  APPROVED: 'green',
  REJECTED: 'red',
  CANCELLED: 'gray',
  EXPIRED: 'gray',
};
