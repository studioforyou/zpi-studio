import {
  DEFAULT_TIME_ZONE,
  addDays,
  startOfMonth,
  toInstant,
  toZoned,
} from '@/features/schedule/studioTime';

export const periods = ['TODAY', 'WEEK', 'MONTH_30', 'THIS_MONTH', 'CUSTOM'] as const;
export type Period = (typeof periods)[number];

/** Dates `yyyy-MM-dd` of a custom period, both included. */
export type CustomRange = { from: string; to: string };

/**
 * The instants a period covers, in the studio's time zone: `from` included, `to` excluded,
 * `null` for an open end. A custom period with no dates is open on that side.
 */
export function periodRange(
  period: Period,
  custom: CustomRange,
  now: Date = new Date(),
  timeZone: string = DEFAULT_TIME_ZONE,
): { from: string | null; to: string | null } {
  const today = toZoned(now, timeZone).date;
  const start = (date: string) => toInstant(date, '00:00', timeZone).toISOString();
  switch (period) {
    case 'TODAY':
      return { from: start(today), to: null };
    case 'WEEK':
      return { from: start(addDays(today, -6)), to: null };
    case 'MONTH_30':
      return { from: start(addDays(today, -29)), to: null };
    case 'THIS_MONTH':
      return { from: start(startOfMonth(today)), to: null };
    case 'CUSTOM':
      return {
        from: custom.from ? start(custom.from) : null,
        to: custom.to ? start(addDays(custom.to, 1)) : null,
      };
  }
}
