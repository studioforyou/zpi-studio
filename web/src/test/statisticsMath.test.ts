import { expect, test } from 'vitest';
import type {
  ClassAttendanceClass,
  ClassAttendanceCounts,
} from '@/features/statistics/attendanceReportsApi';
import { eventReportParams } from '@/features/statistics/attendanceReportsApi';
import {
  bucketDays,
  bucketFor,
  bucketLabel,
  describePolicy,
  formatPercent,
  noClassCounts,
  occupancyGrid,
  occupancyStep,
  percentTicks,
  rankByOccupancy,
  sortSummaries,
  summarizeClasses,
} from '@/features/statistics/statisticsMath';

function counts(values: Partial<ClassAttendanceCounts>): ClassAttendanceCounts {
  return { ...noClassCounts, ...values };
}

function lesson(values: Partial<ClassAttendanceClass>): ClassAttendanceClass {
  return {
    id: 'class',
    seriesId: 'series',
    title: 'Pole dance',
    serviceTypeId: 'pole',
    serviceTypeName: 'Pole dance',
    calendarColor: '#E91E63',
    trainingGroupId: null,
    trainingGroupName: null,
    weekday: 2,
    startTime: '18:00',
    counts: noClassCounts,
    ...values,
  };
}

test('formats shares as whole percentages, small ones as below one percent', () => {
  expect(formatPercent(0.725)).toBe('73%');
  expect(formatPercent(0)).toBe('0%');
  expect(formatPercent(0.004)).toBe('<1%');
  expect(formatPercent(1.2)).toBe('120%');
  expect(formatPercent(null)).toBe('—');
});

test('splits longer periods into weeks and months', () => {
  expect(bucketFor(7)).toBe('day');
  expect(bucketFor(30)).toBe('week');
  expect(bucketFor(182)).toBe('week');
  expect(bucketFor(365)).toBe('month');
});

test('sums days into weeks starting on Monday and into months', () => {
  const days = [
    { date: '2026-11-03', counts: counts({ events: 1, booked: 4 }) },
    { date: '2026-11-08', counts: counts({ events: 2, booked: 6 }) },
    { date: '2026-11-10', counts: counts({ events: 1, booked: 5 }) },
    { date: '2026-12-01', counts: counts({ events: 1, booked: 1 }) },
  ];

  expect(bucketDays(days, 'week', noClassCounts)).toEqual([
    { start: '2026-11-02', counts: counts({ events: 3, booked: 10 }) },
    { start: '2026-11-09', counts: counts({ events: 1, booked: 5 }) },
    { start: '2026-11-30', counts: counts({ events: 1, booked: 1 }) },
  ]);
  expect(bucketDays(days, 'month', noClassCounts).map((bucket) => bucket.start)).toEqual([
    '2026-11-01',
    '2026-12-01',
  ]);
  expect(bucketLabel('2026-11-02', 'week')).toBe('2.11');
  expect(bucketLabel('2026-11-01', 'month')).toBe('11.2026');
});

test('sums classes per kind of classes and per fixed group', () => {
  const classes = [
    lesson({
      id: 'tue',
      trainingGroupId: 'group',
      trainingGroupName: 'Pole wt/czw',
      counts: counts({ events: 4, places: 40, placesBooked: 30 }),
    }),
    lesson({
      id: 'thu',
      weekday: 4,
      trainingGroupId: 'group',
      trainingGroupName: 'Pole wt/czw',
      counts: counts({ events: 4, places: 40, placesBooked: 10 }),
    }),
    lesson({
      id: 'workshop',
      seriesId: null,
      title: 'Warsztaty',
      serviceTypeId: 'stretch',
      serviceTypeName: 'Stretching',
      weekday: 6,
      startTime: '12:00',
      counts: counts({ events: 1, places: 0 }),
    }),
  ];

  const byClass = summarizeClasses(classes, 'classes');
  expect(byClass.map((row) => row.detail)).toEqual([
    'wt. 18:00 · Pole wt/czw',
    'czw. 18:00 · Pole wt/czw',
    'sob. 12:00 · jednorazowe',
  ]);
  const byService = summarizeClasses(classes, 'services');
  expect(byService.map((row) => [row.name, row.counts.placesBooked, row.counts.places])).toEqual([
    ['Pole dance', 40, 80],
    ['Stretching', 0, 0],
  ]);
  const byGroup = summarizeClasses(classes, 'groups');
  expect(byGroup.map((row) => [row.name, row.detail, row.counts.events])).toEqual([
    ['Pole wt/czw', 'Pole dance', 8],
    ['Bez stałej grupy', null, 1],
  ]);
});

test('sorts by occupancy with classes without a limit last either way', () => {
  const rows = summarizeClasses(
    [
      lesson({
        id: 'half',
        title: 'B',
        counts: counts({ events: 1, places: 10, placesBooked: 5 }),
      }),
      lesson({ id: 'none', title: 'A', counts: counts({ events: 1 }) }),
      lesson({
        id: 'full',
        title: 'C',
        counts: counts({ events: 1, places: 10, placesBooked: 10 }),
      }),
    ],
    'classes',
  );

  expect(sortSummaries(rows, 'occupancy', true).map((row) => row.key)).toEqual([
    'full',
    'half',
    'none',
  ]);
  expect(sortSummaries(rows, 'occupancy', false).map((row) => row.key)).toEqual([
    'half',
    'full',
    'none',
  ]);
  expect(sortSummaries(rows, 'name', false).map((row) => row.key)).toEqual([
    'none',
    'half',
    'full',
  ]);
});

test('puts classes of the same weekday and start hour into one heatmap cell', () => {
  const { hours, cells } = occupancyGrid([
    lesson({ id: 'a', startTime: '18:00', counts: counts({ places: 10, placesBooked: 8 }) }),
    lesson({
      id: 'b',
      title: 'Stretching',
      startTime: '18:30',
      counts: counts({ places: 10, placesBooked: 2 }),
    }),
    lesson({
      id: 'c',
      weekday: 6,
      startTime: '10:00',
      counts: counts({ places: 8, placesBooked: 2 }),
    }),
  ]);

  expect(hours).toEqual([10, 18]);
  expect(cells.get('2-18')?.counts.placesBooked).toBe(10);
  expect(cells.get('2-18')?.titles).toEqual(['Pole dance', 'Stretching']);
  expect(occupancyStep(0.19)).toBe(0);
  expect(occupancyStep(0.5)).toBe(2);
  expect(occupancyStep(1)).toBe(4);
  expect(occupancyStep(1.3)).toBe(4);
});

test('describes the current cancellation rules', () => {
  expect(
    describePolicy({
      cancellationAllowed: true,
      freeCancelHours: 12,
      lateCancelAllowed: true,
      lateCancelForfeitsEntry: true,
    }),
  ).toBe('bezpłatnie do 12 godzin przed, później wejście przepada');
  expect(
    describePolicy({
      cancellationAllowed: true,
      freeCancelHours: 24,
      lateCancelAllowed: true,
      lateCancelForfeitsEntry: false,
    }),
  ).toBe('bezpłatnie do 24 godzin przed, później wejście wraca');
  expect(
    describePolicy({
      cancellationAllowed: true,
      freeCancelHours: 2,
      lateCancelAllowed: false,
      lateCancelForfeitsEntry: true,
    }),
  ).toBe('bezpłatnie do 2 godzin przed, później niedozwolone');
  expect(
    describePolicy({
      cancellationAllowed: false,
      freeCancelHours: 12,
      lateCancelAllowed: true,
      lateCancelForfeitsEntry: true,
    }),
  ).toBe('anulowanie niedozwolone');
  expect(describePolicy(null)).toBe('—');
});

test('ranks classes with a limit of places at both ends without repeating one', () => {
  const classes = [10, 9, 8, 7, 6, 5, 4, 3].map((booked) =>
    lesson({ id: `c${booked}`, counts: counts({ events: 1, places: 10, placesBooked: booked }) }),
  );
  classes.push(lesson({ id: 'unlimited', counts: counts({ events: 1, booked: 20 }) }));

  const { fullest, emptiest } = rankByOccupancy(classes);

  expect(fullest.map((row) => row.id)).toEqual(['c10', 'c9', 'c8', 'c7', 'c6']);
  expect(emptiest.map((row) => row.id)).toEqual(['c3', 'c4', 'c5']);
  expect(rankByOccupancy(classes.slice(0, 2)).emptiest).toEqual([]);
});

test('puts round percentage ticks just above the highest value', () => {
  expect(percentTicks(0)).toEqual([0, 10]);
  expect(percentTicks(33.3)).toEqual([0, 10, 20, 30, 40]);
  expect(percentTicks(55)).toEqual([0, 20, 40, 60]);
  expect(percentTicks(97)).toEqual([0, 25, 50, 75, 100]);
});

test('sends every filter value as its own parameter', () => {
  expect(
    eventReportParams({
      from: '2026-10-01',
      to: '2026-10-31',
      kinds: ['WORKSHOP', 'CLASS'],
      serviceTypeIds: ['b', 'a'],
      staffIds: [],
      trainingGroupIds: ['g'],
      resourceIds: [],
    }).toString(),
  ).toBe(
    'from=2026-10-01&to=2026-10-31&kind=CLASS&kind=WORKSHOP&serviceTypeId=a&serviceTypeId=b&trainingGroupId=g',
  );
});
