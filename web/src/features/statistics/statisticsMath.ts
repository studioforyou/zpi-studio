import { formatDate, startOfMonth, startOfWeek } from '@/features/schedule/studioTime';
import { pl } from '@/shared/i18n/pl';
import type {
  BookingOutcomeCounts,
  BookingOutcomePolicy,
  ClassAttendanceClass,
  ClassAttendanceCounts,
} from './attendanceReportsApi';

const texts = pl.statistics;

/** `part / whole`, or `null` when there is nothing to compare with. */
export function ratio(part: number, whole: number): number | null {
  return whole > 0 ? part / whole : null;
}

/** A ratio as a whole percentage, `<1%` for a small non-zero one and `—` without a ratio. */
export function formatPercent(value: number | null): string {
  if (value === null) {
    return '—';
  }
  const percent = Math.round(value * 100);
  if (percent === 0 && value > 0) {
    return '<1%';
  }
  return `${percent}%`;
}

export function formatNumber(value: number, fractionDigits = 0): string {
  return value.toLocaleString('pl-PL', {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  });
}

/** Adds counts field by field. */
export function addCounts<T extends Record<string, number>>(a: T, b: T): T {
  const sum = { ...a };
  for (const key of Object.keys(b) as (keyof T)[]) {
    sum[key] = ((a[key] ?? 0) + b[key]) as T[keyof T];
  }
  return sum;
}

export const noClassCounts: ClassAttendanceCounts = {
  events: 0,
  booked: 0,
  attended: 0,
  absent: 0,
  unmarked: 0,
  places: 0,
  placesBooked: 0,
  waitlisted: 0,
};

export const noOutcomeCounts: BookingOutcomeCounts = {
  events: 0,
  bookings: 0,
  attended: 0,
  absent: 0,
  unmarked: 0,
  cancelledInTime: 0,
  cancelledLate: 0,
  lateEntriesForfeited: 0,
};

export function occupancy(counts: ClassAttendanceCounts): number | null {
  return ratio(counts.placesBooked, counts.places);
}

export function attendanceRate(counts: ClassAttendanceCounts): number | null {
  return ratio(counts.attended, counts.booked);
}

export function averageAttendees(counts: ClassAttendanceCounts): number | null {
  return ratio(counts.attended, counts.events);
}

/** How finely a period of `days` days is split on charts over time. */
export type Bucket = 'day' | 'week' | 'month';

export function bucketFor(days: number): Bucket {
  if (days <= 14) {
    return 'day';
  }
  return days <= 182 ? 'week' : 'month';
}

/** First day of the bucket a studio date falls in; weeks start on Monday. */
export function bucketStart(date: string, bucket: Bucket): string {
  switch (bucket) {
    case 'day':
      return date;
    case 'week':
      return startOfWeek(date);
    case 'month':
      return startOfMonth(date);
  }
}

/** Day rows summed into buckets, earliest first; buckets without any day are left out. */
export function bucketDays<T extends Record<string, number>>(
  days: { date: string; counts: T }[],
  bucket: Bucket,
  empty: T,
): { start: string; counts: T }[] {
  const buckets = new Map<string, T>();
  for (const day of days) {
    const start = bucketStart(day.date, bucket);
    buckets.set(start, addCounts(buckets.get(start) ?? empty, day.counts));
  }
  return [...buckets.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([start, counts]) => ({ start, counts }));
}

/** Short axis label of a bucket: `3.11` for a day or the Monday of a week, `11.2026` for a month. */
export function bucketLabel(start: string, bucket: Bucket): string {
  const [year, month, day] = start.split('-');
  if (bucket === 'month') {
    return `${month}.${year}`;
  }
  return `${Number(day)}.${month}`;
}

/** Full name of a bucket for tooltips. */
export function bucketTitle(start: string, bucket: Bucket): string {
  switch (bucket) {
    case 'day':
      return formatDate(start);
    case 'week':
      return `tydzień od ${formatDate(start)}`;
    case 'month':
      return `${start.slice(5, 7)}.${start.slice(0, 4)}`;
  }
}

export type ClassGrouping = 'classes' | 'services' | 'groups';

/** One row of the classes table: a class, a kind of classes or a fixed group. */
export type ClassSummary = {
  key: string;
  name: string;
  detail: string | null;
  color: string | null;
  counts: ClassAttendanceCounts;
};

const NONE = 'none';

/** The classes, or their sums per service type or per fixed group. */
export function summarizeClasses(
  classes: ClassAttendanceClass[],
  grouping: ClassGrouping,
): ClassSummary[] {
  if (grouping === 'classes') {
    return classes.map((row) => ({
      key: row.id,
      name: row.title,
      detail: classDetail(row),
      color: row.calendarColor,
      counts: row.counts,
    }));
  }
  const summaries = new Map<string, ClassSummary & { services: Set<string> }>();
  for (const row of classes) {
    const key = (grouping === 'services' ? row.serviceTypeId : row.trainingGroupId) ?? NONE;
    const existing = summaries.get(key);
    const service = row.serviceTypeName ?? texts.attendance.noService;
    if (existing) {
      existing.counts = addCounts(existing.counts, row.counts);
      existing.services.add(service);
    } else {
      summaries.set(key, {
        key,
        name:
          grouping === 'services'
            ? (row.serviceTypeName ?? texts.attendance.noService)
            : (row.trainingGroupName ?? texts.attendance.noGroup),
        detail: null,
        color: grouping === 'services' ? row.calendarColor : null,
        counts: row.counts,
        services: new Set([service]),
      });
    }
  }
  return [...summaries.values()].map(({ services, ...summary }) => ({
    ...summary,
    // A fixed group shows the kinds of classes it trains.
    detail: grouping === 'groups' && summary.key !== NONE ? [...services].join(', ') : null,
  }));
}

/** `wt. 18:00 · Pole dance początkujący wt/czw 18:00`, `sob. 12:00 · jednorazowe` */
function classDetail(row: ClassAttendanceClass): string {
  const parts = [`${texts.attendance.weekdaysShort[row.weekday - 1]} ${row.startTime}`];
  if (row.trainingGroupName) {
    parts.push(row.trainingGroupName);
  }
  if (row.seriesId === null) {
    parts.push(texts.attendance.oneOff);
  }
  return parts.join(' · ');
}

/** How many classes each end of the occupancy ranking shows. */
const RANKING_SIZE = 5;

/**
 * Classes with a limit of places at both ends of occupancy: the fullest first, and the emptiest
 * (from the emptiest up) among the rest, so a class never shows in both.
 */
export function rankByOccupancy(classes: ClassAttendanceClass[]): {
  fullest: ClassAttendanceClass[];
  emptiest: ClassAttendanceClass[];
} {
  const limited = classes
    .filter((row) => row.counts.places > 0)
    .sort((a, b) => (occupancy(b.counts) ?? 0) - (occupancy(a.counts) ?? 0));
  const fullest = limited.slice(0, RANKING_SIZE);
  const emptiest = limited.slice(fullest.length).reverse().slice(0, RANKING_SIZE);
  return { fullest, emptiest };
}

/** Round percentage ticks from 0 to just above `max`: every 10% up to 40%, then 20% or 25%. */
export function percentTicks(max: number): number[] {
  const top = Math.min(100, Math.max(10, Math.ceil(max / 10) * 10));
  const step = top <= 40 ? 10 : top <= 80 ? 20 : 25;
  const ticks: number[] = [];
  for (let tick = 0; tick < top + step; tick += step) {
    ticks.push(tick);
  }
  return ticks;
}

/** Share as a percentage with one decimal, so small shares still show on a chart. */
export function sharePercent(part: number, whole: number): number {
  return whole > 0 ? Math.round((part / whole) * 1000) / 10 : 0;
}

export type ClassSortKey =
  'name' | 'events' | 'attendees' | 'occupancy' | 'attendance' | 'waitlisted';

/** Rows in the chosen order; rows without a value (e.g. no limit of places) always go last. */
export function sortSummaries(
  rows: ClassSummary[],
  key: ClassSortKey,
  descending: boolean,
): ClassSummary[] {
  const value = (row: ClassSummary): number | null => {
    switch (key) {
      case 'name':
        return null;
      case 'events':
        return row.counts.events;
      case 'attendees':
        return averageAttendees(row.counts);
      case 'occupancy':
        return occupancy(row.counts);
      case 'attendance':
        return attendanceRate(row.counts);
      case 'waitlisted':
        return row.counts.waitlisted;
    }
  };
  const byName = (a: ClassSummary, b: ClassSummary) => a.name.localeCompare(b.name, 'pl');
  return [...rows].sort((a, b) => {
    if (key === 'name') {
      return descending ? byName(b, a) : byName(a, b);
    }
    const left = value(a);
    const right = value(b);
    if (left === null || right === null) {
      return left === right ? byName(a, b) : left === null ? 1 : -1;
    }
    return (descending ? right - left : left - right) || byName(a, b);
  });
}

/** Classes starting in one hour of one weekday, for the occupancy heatmap. */
export type HeatCell = {
  weekday: number;
  hour: number;
  counts: ClassAttendanceCounts;
  titles: string[];
};

/** Start hours that have classes, and the classes summed per weekday and start hour. */
export function occupancyGrid(classes: ClassAttendanceClass[]): {
  hours: number[];
  cells: Map<string, HeatCell>;
} {
  const cells = new Map<string, HeatCell>();
  for (const row of classes) {
    const hour = Number(row.startTime.slice(0, 2));
    const key = heatKey(row.weekday, hour);
    const cell = cells.get(key);
    if (cell) {
      cell.counts = addCounts(cell.counts, row.counts);
      cell.titles.push(row.title);
    } else {
      cells.set(key, { weekday: row.weekday, hour, counts: row.counts, titles: [row.title] });
    }
  }
  const hours = [...new Set([...cells.values()].map((cell) => cell.hour))].sort((a, b) => a - b);
  return { hours, cells };
}

export function heatKey(weekday: number, hour: number): string {
  return `${weekday}-${hour}`;
}

/** Step 0-4 of the occupancy colour scale: below 20%, 20-40%, 40-60%, 60-80%, 80% and more. */
export function occupancyStep(value: number): number {
  return Math.max(0, Math.min(4, Math.floor(value * 5)));
}

/** The service's current cancellation rules in a few words. */
export function describePolicy(policy: BookingOutcomePolicy | null): string {
  const rules = texts.outcomes.rules;
  if (policy === null) {
    return rules.unknown;
  }
  if (policy.cancellationAllowed === false) {
    return rules.notAllowed;
  }
  const free = rules.freeUntil(texts.outcomes.hours(policy.freeCancelHours ?? 0));
  const late =
    policy.lateCancelAllowed === false
      ? rules.lateNotAllowed
      : policy.lateCancelForfeitsEntry === false
        ? rules.lateReturns
        : rules.lateForfeits;
  return `${free}, ${late}`;
}
