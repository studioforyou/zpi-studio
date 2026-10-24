import { describe, expect, test } from 'vitest';
import { layoutDay } from '@/features/schedule/eventLayout';
import type { ScheduleEvent, ServiceTypeDetails } from '@/features/schedule/scheduleApi';
import {
  activeFilterCount,
  emptyFilters,
  filterEvents,
  staffingGapsOf,
} from '@/features/schedule/scheduleFilters';
import { shiftAnchor, viewRange } from '@/features/schedule/scheduleView';
import { isoWeekday, startOfWeek, toInstant, toZoned } from '@/features/schedule/studioTime';

const ZONE = 'Europe/Warsaw';

describe('studio time', () => {
  test('converts studio local time to instants on both sides of a daylight saving change', () => {
    expect(toInstant('2026-10-24', '18:00', ZONE).toISOString()).toBe('2026-10-24T16:00:00.000Z');
    expect(toInstant('2026-10-26', '18:00', ZONE).toISOString()).toBe('2026-10-26T17:00:00.000Z');
    // 02:30 does not exist on the spring change day; like the backend, it moves forward to 03:30.
    expect(toInstant('2027-03-28', '02:30', ZONE).toISOString()).toBe('2027-03-28T01:30:00.000Z');
  });

  test('reads the studio local date and time of an instant', () => {
    expect(toZoned('2026-10-24T16:00:00Z', ZONE)).toEqual({ date: '2026-10-24', minutes: 18 * 60 });
    expect(toZoned('2026-11-02T23:30:00Z', ZONE)).toEqual({ date: '2026-11-03', minutes: 30 });
    expect(toZoned('2026-11-02T23:30:00Z', 'UTC')).toEqual({ date: '2026-11-02', minutes: 1410 });
  });

  test('computes weeks and weekdays on plain dates', () => {
    expect(startOfWeek('2026-11-05')).toBe('2026-11-02');
    expect(startOfWeek('2026-11-08')).toBe('2026-11-02');
    expect(isoWeekday('2026-11-08')).toBe(7);
  });
});

describe('view ranges', () => {
  test('cover a day, a week, whole weeks of a month and the month itself', () => {
    expect(viewRange('day', '2026-11-05')).toMatchObject({
      start: '2026-11-05',
      end: '2026-11-06',
    });
    expect(viewRange('week', '2026-11-05')).toMatchObject({
      start: '2026-11-02',
      end: '2026-11-09',
      label: '02.11.2026 – 08.11.2026',
    });
    expect(viewRange('month', '2026-11-15')).toMatchObject({
      start: '2026-10-26',
      end: '2026-12-07',
    });
    expect(viewRange('list', '2026-11-15')).toMatchObject({
      start: '2026-11-01',
      end: '2026-12-01',
    });
  });

  test('move by the length of the view', () => {
    expect(shiftAnchor('day', '2026-11-30', 1)).toBe('2026-12-01');
    expect(shiftAnchor('week', '2026-11-05', -1)).toBe('2026-10-29');
    expect(shiftAnchor('month', '2026-01-31', -1)).toBe('2025-12-01');
  });
});

describe('day layout', () => {
  test('puts overlapping events side by side and others at full width', () => {
    const placed = layoutDay([
      { item: 'a', start: 600, end: 660 },
      { item: 'b', start: 630, end: 690 },
      { item: 'c', start: 700, end: 760 },
      { item: 'd', start: 660, end: 690 },
    ]);
    const byItem = Object.fromEntries(placed.map((entry) => [entry.item, entry]));
    expect(byItem.a).toMatchObject({ column: 0, columns: 2 });
    expect(byItem.b).toMatchObject({ column: 1, columns: 2 });
    // d starts when a ends, so it reuses a's column.
    expect(byItem.d).toMatchObject({ column: 0, columns: 2 });
    expect(byItem.c).toMatchObject({ column: 0, columns: 1 });
  });
});

function event(overrides: Partial<ScheduleEvent>): ScheduleEvent {
  return {
    id: 'event',
    kind: 'CLASS',
    status: 'SCHEDULED',
    visibility: 'PUBLIC',
    title: 'Pole dance',
    serviceTypeId: 'pole',
    serviceTypeName: 'Pole dance',
    seriesId: null,
    isSeriesException: false,
    trainingGroupId: null,
    startsAt: '2026-11-03T17:00:00Z',
    endsAt: '2026-11-03T18:00:00Z',
    capacity: 10,
    staff: [{ staffId: 'anna', displayName: 'Anna Kowalska', role: 'LEAD' }],
    resources: [
      { resourceId: 'room', name: 'Sala pole dance', kind: 'ROOM', blocksWholeRoom: true },
    ],
    cancelReason: null,
    blockReason: null,
    guestName: null,
    version: 0,
    ...overrides,
  };
}

function serviceType(overrides: Partial<ServiceTypeDetails>): ServiceTypeDetails {
  return {
    id: 'pole',
    name: 'Pole dance',
    category: 'GROUP_CLASS',
    level: 'BEGINNER',
    minAge: 16,
    maxAge: null,
    defaultDurationMinutes: 60,
    maxParticipants: 10,
    calendarColor: null,
    isActive: true,
    ...overrides,
  };
}

describe('schedule filters', () => {
  const evening = event({ id: 'evening', seriesId: 'series' });
  const kids = event({
    id: 'kids',
    serviceTypeId: 'kids',
    title: 'Łączka dla dzieci',
    startsAt: '2026-11-07T09:00:00Z',
    endsAt: '2026-11-07T10:00:00Z',
    staff: [],
  });
  const personal = event({
    id: 'personal',
    kind: 'PERSONAL_TRAINING',
    serviceTypeId: 'pt',
    startsAt: '2026-11-04T12:00:00Z',
    endsAt: '2026-11-04T13:00:00Z',
    staff: [{ staffId: 'marek', displayName: 'Marek Nowak', role: 'LEAD' }],
    resources: [],
    visibility: 'PRIVATE',
  });
  const events = [evening, kids, personal];
  const context = {
    timeZone: ZONE,
    serviceTypes: new Map([
      ['pole', serviceType({})],
      ['kids', serviceType({ id: 'kids', maxAge: 12, level: 'ALL_LEVELS' })],
      ['pt', serviceType({ id: 'pt', category: 'PERSONAL_TRAINING', level: null })],
    ]),
  };
  const ids = (filters: Partial<typeof emptyFilters>) =>
    filterEvents(events, { ...emptyFilters, ...filters }, context).map((item) => item.id);

  test('without criteria shows everything', () => {
    expect(ids({})).toEqual(['evening', 'kids', 'personal']);
    expect(activeFilterCount(emptyFilters)).toBe(0);
  });

  test('match any value of one criterion and every criterion', () => {
    expect(ids({ kinds: ['CLASS'] })).toEqual(['evening', 'kids']);
    expect(ids({ kinds: ['CLASS', 'PERSONAL_TRAINING'], staffIds: ['marek'] })).toEqual([
      'personal',
    ]);
    expect(ids({ roomIds: ['room'] })).toEqual(['evening', 'kids']);
    expect(ids({ visibilities: ['PRIVATE'] })).toEqual(['personal']);
    expect(activeFilterCount({ ...emptyFilters, kinds: ['CLASS'], search: ' x ' })).toBe(2);
  });

  test('use the service type for categories, levels and age groups', () => {
    expect(ids({ categories: ['PERSONAL_TRAINING'] })).toEqual(['personal']);
    expect(ids({ levels: ['BEGINNER'] })).toEqual(['evening']);
    expect(ids({ ageGroups: ['CHILDREN'] })).toEqual(['kids']);
    expect(ids({ ageGroups: ['ADULTS'] })).toEqual(['evening', 'personal']);
  });

  test('use the studio local day and time', () => {
    expect(ids({ weekdays: [2] })).toEqual(['evening']);
    expect(ids({ timesOfDay: ['MORNING'] })).toEqual(['kids']);
    expect(ids({ timesOfDay: ['AFTERNOON', 'EVENING'] })).toEqual(['evening', 'personal']);
  });

  test('find recurring events, gaps in staffing and text without diacritics', () => {
    expect(ids({ recurrence: 'RECURRING' })).toEqual(['evening']);
    expect(ids({ recurrence: 'ONE_OFF' })).toEqual(['kids', 'personal']);
    expect(ids({ gaps: ['NO_STAFF'] })).toEqual(['kids']);
    expect(ids({ gaps: ['NO_ROOM'] })).toEqual(['personal']);
    expect(ids({ search: 'laczka' })).toEqual(['kids']);
    expect(ids({ search: 'nowak' })).toEqual(['personal']);
  });

  test('do not report gaps of blocks and cancelled events', () => {
    expect(staffingGapsOf(event({ kind: 'BLOCK', staff: [], resources: [] }))).toEqual([]);
    expect(staffingGapsOf(event({ status: 'CANCELLED', staff: [] }))).toEqual([]);
    expect(staffingGapsOf(event({ staff: [], resources: [] }))).toEqual(['NO_STAFF', 'NO_ROOM']);
  });
});
