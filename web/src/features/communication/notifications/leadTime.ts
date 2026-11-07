export const leadTimeUnits = ['MINUTES', 'HOURS', 'DAYS'] as const;
export type LeadTimeUnit = (typeof leadTimeUnits)[number];

const unitMinutes: Record<LeadTimeUnit, number> = { MINUTES: 1, HOURS: 60, DAYS: 1440 };

/** Same limits as the backend: 5 minutes to 14 days before the start, at most 5 reminders. */
export const MIN_LEAD_MINUTES = 5;
export const MAX_LEAD_MINUTES = 14 * 1440;
export const MAX_LEAD_TIMES = 5;

export type LeadTimeValue = { amount: number | string; unit: LeadTimeUnit };

/** Splits minutes into the largest unit that divides them, e.g. 1440 → 1 day, 90 → 90 minutes. */
export function toLeadTimeValue(minutes: number): LeadTimeValue {
  if (minutes % unitMinutes.DAYS === 0) return { amount: minutes / unitMinutes.DAYS, unit: 'DAYS' };
  if (minutes % unitMinutes.HOURS === 0)
    return { amount: minutes / unitMinutes.HOURS, unit: 'HOURS' };
  return { amount: minutes, unit: 'MINUTES' };
}

/** The value in minutes, or null when the amount is not a positive whole number. */
export function toMinutes(value: LeadTimeValue): number | null {
  const amount = typeof value.amount === 'number' ? value.amount : Number(value.amount);
  if (value.amount === '' || !Number.isInteger(amount) || amount < 1) return null;
  return amount * unitMinutes[value.unit];
}

/** E.g. "1 dzień", "2 dni", "2 godz.", "1 godz. 30 min", "45 min". */
export function formatLeadTime(minutes: number): string {
  if (minutes % 1440 === 0) {
    const days = minutes / 1440;
    return days === 1 ? '1 dzień' : `${days} dni`;
  }
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) return `${rest} min`;
  return rest === 0 ? `${hours} godz.` : `${hours} godz. ${rest} min`;
}
