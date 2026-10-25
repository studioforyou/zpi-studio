import { addDays, daysBetween, startOfMonth } from '@/features/schedule/studioTime';

/** Same limit as the backend's report: two years, so a whole year can be compared with the one before. */
export const MAX_PERIOD_DAYS = 731;

export const periodPresets = ['last7', 'last30', 'last90', 'thisMonth', 'thisYear'] as const;
export type PeriodPreset = (typeof periodPresets)[number];

/** Studio dates, `yyyy-MM-dd`, both inclusive. */
export type Period = { from: string; to: string };

/** The period of a preset, ending today. */
export function presetPeriod(preset: PeriodPreset, today: string): Period {
  switch (preset) {
    case 'last7':
      return { from: addDays(today, -6), to: today };
    case 'last30':
      return { from: addDays(today, -29), to: today };
    case 'last90':
      return { from: addDays(today, -89), to: today };
    case 'thisMonth':
      return { from: startOfMonth(today), to: today };
    case 'thisYear':
      return { from: `${today.slice(0, 4)}-01-01`, to: today };
  }
}

export type PeriodError = 'incomplete' | 'reversed' | 'tooLong';

/** Why a custom period cannot be reported, or `null` when it can. */
export function periodError(period: Period): PeriodError | null {
  if (!period.from || !period.to) {
    return 'incomplete';
  }
  if (period.to < period.from) {
    return 'reversed';
  }
  return daysBetween(period.from, period.to) + 1 > MAX_PERIOD_DAYS ? 'tooLong' : null;
}

/** Number of days in the period, both ends included. */
export function periodLength(period: Period): number {
  return daysBetween(period.from, period.to) + 1;
}
