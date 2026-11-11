import { apiFetch } from '@/shared/api/apiClient';
import type { Period } from './reportPeriod';

export const reportEventKinds = ['CLASS', 'WORKSHOP', 'PERSONAL_TRAINING'] as const;
export type ReportEventKind = (typeof reportEventKinds)[number];

/** Kinds whose places a group shares; occupancy and attendance cover only these. */
export const groupEventKinds: readonly ReportEventKind[] = ['CLASS', 'WORKSHOP'];

/**
 * Which events a report covers. An empty list other than `kinds` leaves nothing out; an event
 * matches a list when it has one of its values (its service, a staff member, its group, a room).
 */
export type EventFilters = {
  kinds: ReportEventKind[];
  serviceTypeIds: string[];
  staffIds: string[];
  trainingGroupIds: string[];
  resourceIds: string[];
};

/** The period and the events of a report about events. */
export type EventReportQuery = Period & EventFilters;

/** Query string of a report about events: each filter value as its own repeated parameter. */
export function eventReportParams(query: EventReportQuery): URLSearchParams {
  const params = new URLSearchParams({ from: query.from, to: query.to });
  const lists: [string, string[]][] = [
    ['kind', query.kinds],
    ['serviceTypeId', query.serviceTypeIds],
    ['staffId', query.staffIds],
    ['trainingGroupId', query.trainingGroupIds],
    ['resourceId', query.resourceIds],
  ];
  for (const [name, values] of lists) {
    for (const value of [...values].sort()) {
      params.append(name, value);
    }
  }
  return params;
}

/**
 * Places of group classes and workshops that took place. A participant attended unless marked
 * absent; `unmarked` counts the attended places nobody marked.
 */
export type ClassAttendanceCounts = {
  events: number;
  /** Places not cancelled. */
  booked: number;
  attended: number;
  /** No-shows: marked absent, did not cancel. */
  absent: number;
  unmarked: number;
  /** Places offered by the classes with a limit of places. */
  places: number;
  /** Booked places of the classes with a limit; occupancy is `placesBooked / places`. */
  placesBooked: number;
  /** Participants who joined a waiting list. */
  waitlisted: number;
};

/** A recurring series with all its occurrences in the period, or a one-off event. */
export type ClassAttendanceClass = {
  id: string;
  seriesId: string | null;
  title: string;
  serviceTypeId: string | null;
  serviceTypeName: string | null;
  calendarColor: string | null;
  trainingGroupId: string | null;
  trainingGroupName: string | null;
  /** ISO weekday its occurrences mostly start on, 1 = Monday. */
  weekday: number;
  /** `HH:mm` in the studio's time zone. */
  startTime: string;
  counts: ClassAttendanceCounts;
};

/** Response of `GET /api/v1/reports/class-attendance` (#164). */
export type ClassAttendanceReport = {
  from: string;
  to: string;
  kinds: ReportEventKind[];
  totals: ClassAttendanceCounts;
  classes: ClassAttendanceClass[];
  /** Only dates with classes, earliest first. */
  days: { date: string; counts: ClassAttendanceCounts }[];
};

/** `bookings` = `attended` + `absent` + `cancelledInTime` + `cancelledLate`. */
export type BookingOutcomeCounts = {
  events: number;
  bookings: number;
  attended: number;
  absent: number;
  unmarked: number;
  cancelledInTime: number;
  cancelledLate: number;
  /** Late cancellations whose pass entry was not returned. */
  lateEntriesForfeited: number;
};

/** The service's effective cancellation rules now; they may have changed during the period. */
export type BookingOutcomePolicy = {
  cancellationAllowed: boolean | null;
  freeCancelHours: number | null;
  lateCancelAllowed: boolean | null;
  lateCancelForfeitsEntry: boolean | null;
};

export type BookingOutcomeService = {
  serviceTypeId: string | null;
  name: string | null;
  calendarColor: string | null;
  counts: BookingOutcomeCounts;
  policy: BookingOutcomePolicy | null;
};

/** Places cancelled at least `fromHours` and less than `toHours` before the start. */
export type CancellationLeadTime = {
  fromHours: number;
  toHours: number | null;
  inTime: number;
  late: number;
};

/** Response of `GET /api/v1/reports/booking-outcomes` (#166). */
export type BookingOutcomesReport = {
  from: string;
  to: string;
  kinds: ReportEventKind[];
  totals: BookingOutcomeCounts;
  /** Most bookings first. */
  services: BookingOutcomeService[];
  /** From the shortest time before the start to the longest. */
  cancellationLeadTimes: CancellationLeadTime[];
  days: { date: string; counts: BookingOutcomeCounts }[];
};

export const attendanceReportKeys = {
  all: ['attendance-reports'] as const,
  classAttendance: (query: EventReportQuery) =>
    [...attendanceReportKeys.all, 'class-attendance', eventReportParams(query).toString()] as const,
  bookingOutcomes: (query: EventReportQuery) =>
    [...attendanceReportKeys.all, 'booking-outcomes', eventReportParams(query).toString()] as const,
};

/** `query.kinds` must be group kinds only: personal training has no occupancy. */
export function getClassAttendance(query: EventReportQuery): Promise<ClassAttendanceReport> {
  return apiFetch<ClassAttendanceReport>(
    `/api/v1/reports/class-attendance?${eventReportParams(query)}`,
  );
}

export function getBookingOutcomes(query: EventReportQuery): Promise<BookingOutcomesReport> {
  return apiFetch<BookingOutcomesReport>(
    `/api/v1/reports/booking-outcomes?${eventReportParams(query)}`,
  );
}
