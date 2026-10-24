import type {
  EventKind,
  EventStatus,
  EventVisibility,
  ScheduleEvent,
  ServiceTypeDetails,
} from './scheduleApi';
import { isoWeekday, toZoned } from './studioTime';

export const timesOfDay = ['MORNING', 'AFTERNOON', 'EVENING'] as const;
export type TimeOfDay = (typeof timesOfDay)[number];

export const recurrenceFilters = ['ALL', 'RECURRING', 'ONE_OFF', 'EXCEPTIONS'] as const;
export type RecurrenceFilter = (typeof recurrenceFilters)[number];

export const ageGroups = ['CHILDREN', 'ADULTS'] as const;
export type AgeGroup = (typeof ageGroups)[number];

export const staffingGaps = ['NO_STAFF', 'NO_ROOM'] as const;
export type StaffingGap = (typeof staffingGaps)[number];

/**
 * Criteria of the schedule view. Values of one criterion are alternatives; an event must meet
 * every criterion that has a value. Empty lists and `ALL` do not restrict anything.
 */
export type ScheduleFilters = {
  search: string;
  kinds: EventKind[];
  statuses: EventStatus[];
  visibilities: EventVisibility[];
  serviceTypeIds: string[];
  categories: string[];
  levels: string[];
  staffIds: string[];
  roomIds: string[];
  trainingGroupIds: string[];
  recurrence: RecurrenceFilter;
  /** ISO weekdays, 1 = Monday. */
  weekdays: number[];
  timesOfDay: TimeOfDay[];
  ageGroups: AgeGroup[];
  gaps: StaffingGap[];
};

export const emptyFilters: ScheduleFilters = {
  search: '',
  kinds: [],
  statuses: [],
  visibilities: [],
  serviceTypeIds: [],
  categories: [],
  levels: [],
  staffIds: [],
  roomIds: [],
  trainingGroupIds: [],
  recurrence: 'ALL',
  weekdays: [],
  timesOfDay: [],
  ageGroups: [],
  gaps: [],
};

/** Number of criteria that restrict the view. */
export function activeFilterCount(filters: ScheduleFilters): number {
  return Object.entries(filters).filter(([key, value]) => {
    if (key === 'search') {
      return (value as string).trim() !== '';
    }
    if (key === 'recurrence') {
      return value !== 'ALL';
    }
    return (value as unknown[]).length > 0;
  }).length;
}

/** Afternoon starts at 12:00 and evening at 17:00, studio local time. */
export function timeOfDay(minutes: number): TimeOfDay {
  if (minutes < 12 * 60) {
    return 'MORNING';
  }
  return minutes < 17 * 60 ? 'AFTERNOON' : 'EVENING';
}

/** A service for children has an upper age limit below 18. */
export function isForChildren(serviceType: ServiceTypeDetails): boolean {
  return serviceType.maxAge !== null && serviceType.maxAge < 18;
}

/** Folds case and Polish diacritics, so "laczka" finds "Łączka". */
export function normalizeText(text: string): string {
  return text
    .toLocaleLowerCase('pl')
    .replace(/ł/g, 'l')
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '');
}

type FilterContext = {
  timeZone: string;
  serviceTypes: ReadonlyMap<string, ServiceTypeDetails>;
};

function includesAny<T>(selected: readonly T[], values: readonly T[]): boolean {
  return values.some((value) => selected.includes(value));
}

export function matchesFilters(
  event: ScheduleEvent,
  filters: ScheduleFilters,
  context: FilterContext,
): boolean {
  const serviceType = event.serviceTypeId
    ? context.serviceTypes.get(event.serviceTypeId)
    : undefined;
  const start = toZoned(event.startsAt, context.timeZone);

  const search = normalizeText(filters.search.trim());
  if (search !== '') {
    const text = [
      event.title,
      event.serviceTypeName,
      event.guestName,
      ...event.staff.map((staff) => staff.displayName),
      ...event.resources.map((resource) => resource.name),
    ]
      .filter((value): value is string => Boolean(value))
      .map(normalizeText)
      .join(' ');
    if (!text.includes(search)) {
      return false;
    }
  }
  if (filters.kinds.length > 0 && !filters.kinds.includes(event.kind)) {
    return false;
  }
  if (filters.statuses.length > 0 && !filters.statuses.includes(event.status)) {
    return false;
  }
  if (filters.visibilities.length > 0 && !filters.visibilities.includes(event.visibility)) {
    return false;
  }
  if (
    filters.serviceTypeIds.length > 0 &&
    (event.serviceTypeId === null || !filters.serviceTypeIds.includes(event.serviceTypeId))
  ) {
    return false;
  }
  if (
    filters.categories.length > 0 &&
    (!serviceType || !filters.categories.includes(serviceType.category))
  ) {
    return false;
  }
  if (
    filters.levels.length > 0 &&
    (!serviceType?.level || !filters.levels.includes(serviceType.level))
  ) {
    return false;
  }
  if (filters.ageGroups.length > 0) {
    const group: AgeGroup | null = serviceType
      ? isForChildren(serviceType)
        ? 'CHILDREN'
        : 'ADULTS'
      : null;
    if (group === null || !filters.ageGroups.includes(group)) {
      return false;
    }
  }
  if (
    filters.staffIds.length > 0 &&
    !includesAny(
      filters.staffIds,
      event.staff.map((staff) => staff.staffId),
    )
  ) {
    return false;
  }
  if (
    filters.roomIds.length > 0 &&
    !includesAny(
      filters.roomIds,
      event.resources.map((room) => room.resourceId),
    )
  ) {
    return false;
  }
  if (
    filters.trainingGroupIds.length > 0 &&
    (event.trainingGroupId === null || !filters.trainingGroupIds.includes(event.trainingGroupId))
  ) {
    return false;
  }
  if (filters.recurrence === 'RECURRING' && event.seriesId === null) {
    return false;
  }
  if (filters.recurrence === 'ONE_OFF' && event.seriesId !== null) {
    return false;
  }
  if (filters.recurrence === 'EXCEPTIONS' && !event.isSeriesException) {
    return false;
  }
  if (filters.weekdays.length > 0 && !filters.weekdays.includes(isoWeekday(start.date))) {
    return false;
  }
  if (filters.timesOfDay.length > 0 && !filters.timesOfDay.includes(timeOfDay(start.minutes))) {
    return false;
  }
  if (filters.gaps.length > 0) {
    const gaps = staffingGapsOf(event);
    if (!includesAny(filters.gaps, gaps)) {
      return false;
    }
  }
  return true;
}

/** What an event still needs: someone to run it and a room. Blocks need neither. */
export function staffingGapsOf(event: ScheduleEvent): StaffingGap[] {
  if (event.kind === 'BLOCK' || event.status === 'CANCELLED') {
    return [];
  }
  const gaps: StaffingGap[] = [];
  if (event.staff.length === 0) {
    gaps.push('NO_STAFF');
  }
  if (event.resources.length === 0) {
    gaps.push('NO_ROOM');
  }
  return gaps;
}

export function filterEvents(
  events: ScheduleEvent[],
  filters: ScheduleFilters,
  context: FilterContext,
): ScheduleEvent[] {
  return events.filter((event) => matchesFilters(event, filters, context));
}
