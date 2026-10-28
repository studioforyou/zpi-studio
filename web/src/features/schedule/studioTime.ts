/**
 * Dates and times in the studio's time zone, independent of the browser's. Dates are
 * `yyyy-MM-dd` strings and times `HH:mm` strings in studio local time; instants are ISO strings.
 */

export const DEFAULT_TIME_ZONE = 'Europe/Warsaw';

const formatters = new Map<string, Intl.DateTimeFormat>();

function partsFormatter(timeZone: string): Intl.DateTimeFormat {
  let formatter = formatters.get(timeZone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    formatters.set(timeZone, formatter);
  }
  return formatter;
}

type Parts = {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
};

function parts(instant: Date, timeZone: string): Parts {
  const values: Record<string, number> = {};
  for (const part of partsFormatter(timeZone).formatToParts(instant)) {
    if (part.type !== 'literal') {
      values[part.type] = Number(part.value);
    }
  }
  return {
    year: values.year,
    month: values.month,
    day: values.day,
    hour: values.hour % 24,
    minute: values.minute,
    second: values.second,
  };
}

function pad(value: number): string {
  return String(value).padStart(2, '0');
}

export type ZonedTime = {
  /** Studio local date. */
  date: string;
  /** Minutes since the studio's local midnight. */
  minutes: number;
};

/** The studio local date and time of an instant. */
export function toZoned(instant: string | Date, timeZone: string): ZonedTime {
  const p = parts(typeof instant === 'string' ? new Date(instant) : instant, timeZone);
  return { date: `${p.year}-${pad(p.month)}-${pad(p.day)}`, minutes: p.hour * 60 + p.minute };
}

/** How far the zone is ahead of UTC at the instant, in minutes. */
function offsetMinutes(epochMillis: number, timeZone: string): number {
  const p = parts(new Date(epochMillis), timeZone);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  return Math.round((asUtc - Math.floor(epochMillis / 1000) * 1000) / 60000);
}

/**
 * The instant of a studio local date and time. A time skipped by a daylight saving change
 * moves forward, like in the backend.
 */
export function toInstant(date: string, time: string, timeZone: string): Date {
  const [year, month, day] = date.split('-').map(Number);
  const [hour, minute] = time.split(':').map(Number);
  const asUtc = Date.UTC(year, month - 1, day, hour, minute);
  const firstOffset = offsetMinutes(asUtc, timeZone);
  let result = asUtc - firstOffset * 60000;
  const secondOffset = offsetMinutes(result, timeZone);
  if (secondOffset !== firstOffset) {
    result = asUtc - secondOffset * 60000;
  }
  return new Date(result);
}

export function todayIn(timeZone: string): string {
  return toZoned(new Date(), timeZone).date;
}

function utcDate(date: string): Date {
  return new Date(`${date}T00:00:00Z`);
}

export function addDays(date: string, days: number): string {
  const result = utcDate(date);
  result.setUTCDate(result.getUTCDate() + days);
  return result.toISOString().slice(0, 10);
}

export function addMonths(date: string, months: number): string {
  const result = utcDate(`${date.slice(0, 7)}-01`);
  result.setUTCMonth(result.getUTCMonth() + months);
  return result.toISOString().slice(0, 10);
}

/** ISO weekday of a date, 1 = Monday. */
export function isoWeekday(date: string): number {
  return ((utcDate(date).getUTCDay() + 6) % 7) + 1;
}

export function startOfWeek(date: string): string {
  return addDays(date, 1 - isoWeekday(date));
}

export function startOfMonth(date: string): string {
  return `${date.slice(0, 7)}-01`;
}

/** Whole days from `from` to `to`. */
export function daysBetween(from: string, to: string): number {
  return Math.round((utcDate(to).getTime() - utcDate(from).getTime()) / 86400000);
}

export function dateRange(start: string, endExclusive: string): string[] {
  const days: string[] = [];
  for (let day = start; day < endExclusive; day = addDays(day, 1)) {
    days.push(day);
  }
  return days;
}

export function minutesToTime(minutes: number): string {
  const bounded = Math.max(0, Math.min(minutes, 24 * 60 - 1));
  return `${pad(Math.floor(bounded / 60))}:${pad(bounded % 60)}`;
}

export function timeToMinutes(time: string): number {
  const [hour, minute] = time.split(':').map(Number);
  return hour * 60 + minute;
}

/** `HH:mm` of an instant in the studio's time zone. */
export function formatTime(instant: string, timeZone: string): string {
  return minutesToTime(toZoned(instant, timeZone).minutes);
}

const dayFormat = new Intl.DateTimeFormat('pl-PL', {
  timeZone: 'UTC',
  weekday: 'short',
  day: 'numeric',
  month: 'numeric',
});
const longDayFormat = new Intl.DateTimeFormat('pl-PL', {
  timeZone: 'UTC',
  weekday: 'long',
  day: 'numeric',
  month: 'long',
  year: 'numeric',
});
const monthFormat = new Intl.DateTimeFormat('pl-PL', {
  timeZone: 'UTC',
  month: 'long',
  year: 'numeric',
});

/** `wt., 3.11` */
export function formatDayShort(date: string): string {
  return dayFormat.format(utcDate(date));
}

/** Upper-cases only the first letter, so month names inside a date stay lower case. */
function capitalizeFirst(text: string): string {
  return text.charAt(0).toLocaleUpperCase('pl') + text.slice(1);
}

/** `Wtorek, 3 listopada 2026` */
export function formatDayLong(date: string): string {
  return capitalizeFirst(longDayFormat.format(utcDate(date)));
}

/** `Listopad 2026` */
export function formatMonth(date: string): string {
  return capitalizeFirst(monthFormat.format(utcDate(date)));
}

/** `03.11.2026` */
export function formatDate(date: string): string {
  const [year, month, day] = date.split('-');
  return `${day}.${month}.${year}`;
}
