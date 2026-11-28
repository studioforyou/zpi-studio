import { apiFetch } from '@/shared/api/apiClient';
import { ApiError } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import type { Money } from '@/features/passes/passesApi';
import type { TrainerHoursReport } from './trainerHoursApi';

export type SettlementStatus = 'UNPAID' | 'PARTIAL' | 'PAID';
export type PayoutMethod = 'TRANSFER' | 'CASH' | 'OTHER';
export type SettlementSnapshot = {
  report: TrainerHoursReport;
  studioName: string;
  generatedAt: string;
};
export type SettlementPreview = {
  snapshot: SettlementSnapshot;
  currency: string;
  fingerprint: string;
};
export type SettlementSummary = {
  id: string;
  staffId: string;
  month: string;
  displayName: string;
  version: number;
  revision: number;
  amount: Money;
  paid: Money;
  remaining: Money;
  countedSeconds: number;
  unconfirmedSeconds: number;
  includeUnconfirmed: boolean;
  status: SettlementStatus;
  updatedAt: string;
};
export type SettlementRevision = {
  id: string;
  revision: number;
  amount: Money;
  includeUnconfirmed: boolean;
  snapshot: SettlementSnapshot;
  reason: string;
  actor: string;
  createdAt: string;
};
export type TrainerPayout = {
  id: string;
  amount: Money;
  paidOn: string;
  method: PayoutMethod;
  note: string;
  reversesId: string | null;
  reversed: boolean;
  actor: string;
  createdAt: string;
};
export type TrainerSettlement = {
  summary: SettlementSummary;
  revisions: SettlementRevision[];
  payments: TrainerPayout[];
};
export type SettlementApproval = {
  operationId: string;
  staffId: string;
  month: string;
  amount: Money;
  includeUnconfirmed: boolean;
  fingerprint: string;
  note: string;
};
export type SettlementCorrection = {
  operationId: string;
  version: number;
  amount: Money;
  includeUnconfirmed: boolean;
  reason: string;
  fingerprint: string | null;
};
export type PayoutRequest = {
  operationId: string;
  version: number;
  amount: Money;
  paidOn: string;
  method: PayoutMethod;
  note: string;
};

export const settlementKeys = {
  all: ['trainer-settlements'] as const,
  month: (month: string) => ['trainer-settlements', 'month', month] as const,
  detail: (id: string) => ['trainer-settlements', 'detail', id] as const,
};
const root = '/api/v1/trainer-settlements';
export const listSettlements = (month: string) =>
  apiFetch<SettlementSummary[]>(`${root}?${new URLSearchParams({ month })}`);
export const getSettlement = (id: string) => apiFetch<TrainerSettlement>(`${root}/${id}`);
export const exportSettlements = (month: string) =>
  apiFetch<TrainerSettlement[]>(`${root}/export?${new URLSearchParams({ month })}`);
export const previewSettlement = (month: string, staffId: string) =>
  apiFetch<SettlementPreview>(`${root}/preview?${new URLSearchParams({ month, staffId })}`);
export const previewSettlementRevision = (id: string) =>
  apiFetch<SettlementPreview>(`${root}/${id}/preview`);
export const approveSettlement = (body: SettlementApproval) =>
  apiFetch<TrainerSettlement>(root, { method: 'POST', body });
export const correctSettlement = (id: string, body: SettlementCorrection) =>
  apiFetch<TrainerSettlement>(`${root}/${id}/revisions`, { method: 'POST', body });
export const recordPayout = (id: string, body: PayoutRequest) =>
  apiFetch<TrainerSettlement>(`${root}/${id}/payouts`, { method: 'POST', body });
export const reversePayout = (
  id: string,
  payoutId: string,
  body: { operationId: string; version: number; reason: string },
) =>
  apiFetch<TrainerSettlement>(`${root}/${id}/payouts/${payoutId}/reversal`, {
    method: 'POST',
    body,
  });

/** Parse typed money as integer cents; no floating point multiplication or silent rounding. */
export function settlementAmount(value: string): number | null {
  if (!/^\d{1,8}([,.]\d{0,2})?$/.test(value.trim())) return null;
  const [whole, fraction = ''] = value.trim().replace(',', '.').split('.');
  return Number(whole) * 100 + Number(fraction.padEnd(2, '0'));
}

export function settlementError(error: unknown): string {
  if (error instanceof ApiError) {
    return (
      pl.statistics.settlements.errors[
        error.code as keyof typeof pl.statistics.settlements.errors
      ] ?? pl.statistics.settlements.saveError
    );
  }
  return pl.statistics.settlements.saveError;
}
