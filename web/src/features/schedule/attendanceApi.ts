import { apiFetch } from '@/shared/api/apiClient';
import type { EventKind, EventStatus } from './scheduleApi';

/** `UNMARKED` until the trainer or an administrator records it. */
export type Attendance = 'UNMARKED' | 'PRESENT' | 'ABSENT';

/** Attendance that can be recorded; it cannot be cleared again. */
export type RecordedAttendance = Exclude<Attendance, 'UNMARKED'>;

/** One change of a participant's attendance. */
export type AttendanceChange = {
  changedAt: string;
  /** `MARKED` by the trainer, `DEFAULTED` when the event was marked as held, `CORRECTED` by an admin. */
  kind: 'MARKED' | 'DEFAULTED' | 'CORRECTED';
  previousAttendance: Attendance | null;
  newAttendance: Attendance | null;
  /** Who made the change, or `null` when unknown. */
  changedBy: string | null;
  reason: string | null;
};

/** A participant with an active reservation and the history of their attendance. */
export type AttendanceRecord = {
  reservationId: string;
  customerId: string;
  kind: 'ADULT' | 'CHILD';
  firstName: string;
  lastName: string;
  displayName: string;
  bookedAt: string;
  attendance: Attendance;
  /** When the current attendance was recorded; `null` while unmarked. */
  attendanceMarkedAt: string | null;
  attendanceMarkedBy: string | null;
  /** Sent back with a correction; a stale one is rejected with `VERSION_CONFLICT`. */
  version: number;
  /** Oldest first. */
  history: AttendanceChange[];
};

export type EventAttendance = {
  eventId: string;
  eventKind: EventKind;
  eventStatus: EventStatus;
  startsAt: string;
  endsAt: string;
  /** The event has started and was not cancelled. */
  correctable: boolean;
  /** Until when the event's trainers can still record attendance themselves. */
  trainerMarkingClosesAt: string;
  /** Whether the event's trainers can still record attendance themselves now. */
  trainerMarkingOpen: boolean;
  /** Sorted by last and first name. */
  participants: AttendanceRecord[];
};

export type AttendanceCorrection = {
  attendance: RecordedAttendance;
  reason: string | null;
  version: number;
};

export const attendanceKeys = {
  event: (eventId: string) => ['schedule', 'attendance', eventId] as const,
};

export function getEventAttendance(eventId: string): Promise<EventAttendance> {
  return apiFetch<EventAttendance>(`/api/v1/schedule-events/${eventId}/attendance`);
}

export function correctAttendance(
  eventId: string,
  reservationId: string,
  correction: AttendanceCorrection,
): Promise<AttendanceRecord> {
  return apiFetch<AttendanceRecord>(
    `/api/v1/schedule-events/${eventId}/reservations/${reservationId}/correct-attendance`,
    { method: 'POST', body: correction },
  );
}
