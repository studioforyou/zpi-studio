import { ApiError, NETWORK_ERROR } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import { formatDateTime } from '@/features/schedule/scheduleFormat';
import { formatTime, toZoned, formatDate } from '@/features/schedule/studioTime';
import type { Dispute, DisputeResolution } from './disputesApi';

export const resolutionColors: Record<DisputeResolution, string> = {
  UPHELD: 'green',
  UPHELD_MAKEUP: 'teal',
  REJECTED: 'red',
};

/** `12.11.2026, 18:00–19:00` in the studio's time zone. */
export function formatSession(dispute: Pick<Dispute, 'startsAt' | 'endsAt'>, zone: string): string {
  return `${formatDate(toZoned(dispute.startsAt, zone).date)}, ${formatTime(dispute.startsAt, zone)}–${formatTime(dispute.endsAt, zone)}`;
}

/** The session's trainers, the lead first. */
export function trainerNames(dispute: Pick<Dispute, 'staff'>): string {
  const names = [...dispute.staff]
    .sort((a, b) => Number(b.role === 'LEAD') - Number(a.role === 'LEAD'))
    .map((member) => member.displayName);
  return names.length === 0 ? pl.disputes.none : names.join(', ');
}

/** Who decided and when, e.g. `Beata Lis, 12.11.2026 18:00`. */
export function decidedBy(dispute: Dispute, zone: string): string {
  const who = dispute.resolvedByName ?? pl.disputes.studio;
  return dispute.resolvedAt ? `${who}, ${formatDateTime(dispute.resolvedAt, zone)}` : who;
}

/** The last day a make-up granted now would be valid on, `dd.mm.yyyy` in the studio's time zone. */
export function makeupValidUntil(days: number, zone: string, now: Date = new Date()): string {
  const expires = new Date(now.getTime() + days * 24 * 60 * 60 * 1000).toISOString();
  return formatDateTime(expires, zone);
}

type KnownCode = Exclude<keyof typeof pl.disputes.errors, 'network' | 'unexpected'>;

function isKnownCode(code: string): code is KnownCode {
  return code in pl.disputes.errors && code !== 'network' && code !== 'unexpected';
}

/** Polish message for an error of the dispute endpoints, chosen by its API code. */
export function disputeErrorMessage(error: Error): string {
  if (!(error instanceof ApiError)) {
    return pl.disputes.errors.unexpected;
  }
  if (error.code === NETWORK_ERROR) {
    return pl.disputes.errors.network;
  }
  if (error.code !== null && isKnownCode(error.code)) {
    return pl.disputes.errors[error.code];
  }
  return pl.disputes.errors.unexpected;
}
