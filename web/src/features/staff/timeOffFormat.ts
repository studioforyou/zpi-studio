import { ApiError, NETWORK_ERROR } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import {
  addDays,
  daysBetween,
  formatDate,
  minutesToTime,
  toZoned,
} from '@/features/schedule/studioTime';
import type { TimeOff, TimeOffKind, TimeOffStatus } from './timeOffApi';

/**
 * The period in the studio's time zone: whole days when it starts and ends at midnight,
 * otherwise with hours. The end is exclusive, so whole days end the day before `endsAt`.
 */
export function formatPeriod(startsAt: string, endsAt: string, timeZone: string): string {
  const texts = pl.timeOff.period;
  const start = toZoned(startsAt, timeZone);
  const end = toZoned(endsAt, timeZone);
  if (start.minutes === 0 && end.minutes === 0) {
    const count = daysBetween(start.date, end.date);
    const lastDay = addDays(end.date, -1);
    return count <= 1
      ? texts.wholeDay(formatDate(start.date))
      : texts.days(formatDate(start.date), formatDate(lastDay), count);
  }
  if (start.date === end.date) {
    return texts.sameDay(
      formatDate(start.date),
      minutesToTime(start.minutes),
      minutesToTime(end.minutes),
    );
  }
  return texts.range(
    `${formatDate(start.date)} ${minutesToTime(start.minutes)}`,
    `${formatDate(end.date)} ${minutesToTime(end.minutes)}`,
  );
}

export const kindColors: Record<TimeOffKind, string> = {
  VACATION: 'orange',
  SICK_LEAVE: 'violet',
  UNAVAILABLE: 'gray',
};

export const statusColors: Record<TimeOffStatus, string> = {
  REQUESTED: 'yellow',
  APPROVED: 'green',
  REJECTED: 'red',
  CANCELLED: 'gray',
};

/** Requested or approved time off that has not ended can still be cancelled. */
export function isCancellable(timeOff: TimeOff, now: Date = new Date()): boolean {
  return (
    (timeOff.status === 'REQUESTED' || timeOff.status === 'APPROVED') &&
    new Date(timeOff.endsAt).getTime() > now.getTime()
  );
}

type KnownCode = Exclude<keyof typeof pl.timeOff.errors, 'network' | 'unexpected'>;

function isKnownCode(code: string): code is KnownCode {
  return code in pl.timeOff.errors && code !== 'network' && code !== 'unexpected';
}

/** Polish message for an error of the time-off endpoints, chosen by its API code. */
export function timeOffErrorMessage(error: Error): string {
  if (!(error instanceof ApiError)) {
    return pl.timeOff.errors.unexpected;
  }
  if (error.code === NETWORK_ERROR) {
    return pl.timeOff.errors.network;
  }
  if (error.code !== null && isKnownCode(error.code)) {
    return pl.timeOff.errors[error.code];
  }
  return pl.timeOff.errors.unexpected;
}
