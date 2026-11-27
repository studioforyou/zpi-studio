import { apiFetch } from '@/shared/api/apiClient';
import type { Period } from './reportPeriod';

export const workKinds = ['CLASS', 'WORKSHOP', 'PERSONAL_TRAINING', 'CONSULTATION'] as const;
export type WorkKind = (typeof workKinds)[number];

export type WorkCounts = {
  seconds: number;
  leadSeconds: number;
  assistantSeconds: number;
  events: number;
  unconfirmedSeconds: number;
  unconfirmedEvents: number;
};

export type WorkSession = {
  eventId: string;
  kind: WorkKind;
  title: string;
  startsAt: string;
  endsAt: string;
  role: 'LEAD' | 'ASSISTANT';
  seconds: number;
  confirmed: boolean;
  rooms: string;
};

export type InstructorHours = {
  id: string;
  displayName: string;
  active: boolean;
  counts: WorkCounts;
  sessions: WorkSession[];
};

export type TrainerHoursReport = Period & {
  timeZone: string;
  totals: WorkCounts;
  instructors: InstructorHours[];
};

export type TrainerHoursQuery = Period & {
  kind: WorkKind | null;
  staffId: string | null;
  confirmedOnly: boolean;
};

export function getTrainerHours(query: TrainerHoursQuery): Promise<TrainerHoursReport> {
  const params = new URLSearchParams({ from: query.from, to: query.to });
  if (query.kind) params.set('kind', query.kind);
  if (query.staffId) params.set('staffId', query.staffId);
  if (query.confirmedOnly) params.set('confirmedOnly', 'true');
  return apiFetch<TrainerHoursReport>(`/api/v1/reports/trainer-hours?${params}`);
}
