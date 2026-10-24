import { ApiError, NETWORK_ERROR } from '@/shared/api/ApiError';
import { pl } from '@/shared/i18n/pl';
import type {
  EventKind,
  OccurrencePreview,
  ScheduleEvent,
  ScheduleIssue,
  ServiceTypeDetails,
  StaffMember,
} from './scheduleApi';
import { formatDate, formatTime, toZoned } from './studioTime';

export type ColorBy = 'service' | 'staff';

const kindColors: Record<EventKind, string> = {
  CLASS: '#228be6',
  PERSONAL_TRAINING: '#fd7e14',
  WORKSHOP: '#40c057',
  CONSULTATION: '#7950f2',
  BLOCK: '#868e96',
};

/** The event's colour: its service's or lead's calendar colour, or a colour of its kind. */
export function eventColor(
  event: ScheduleEvent,
  colorBy: ColorBy,
  serviceTypes: ReadonlyMap<string, ServiceTypeDetails>,
  staff: ReadonlyMap<string, StaffMember>,
): string {
  if (event.kind === 'BLOCK') {
    return kindColors.BLOCK;
  }
  const color =
    colorBy === 'staff'
      ? staff.get(event.staff[0]?.staffId ?? '')?.calendarColor
      : serviceTypes.get(event.serviceTypeId ?? '')?.calendarColor;
  return color?.trim() || kindColors[event.kind];
}

export function eventTitle(event: ScheduleEvent): string {
  return event.title || event.serviceTypeName || pl.schedule.kinds[event.kind];
}

/** `18:00–19:00` in the studio's time zone. */
export function formatTimeRange(startsAt: string, endsAt: string, timeZone: string): string {
  return `${formatTime(startsAt, timeZone)}–${formatTime(endsAt, timeZone)}`;
}

/** `03.11.2026 18:00` in the studio's time zone. */
export function formatDateTime(instant: string, timeZone: string): string {
  const zoned = toZoned(instant, timeZone);
  return `${formatDate(zoned.date)} ${formatTime(instant, timeZone)}`;
}

export type IssueNames = {
  staff: (staffId: string) => string;
  resource: (resourceId: string) => string;
};

/** A Polish description of a problem of a planned event. */
export function describeIssue(issue: ScheduleIssue, names: IssueNames, timeZone: string): string {
  const texts = pl.schedule.issues;
  const staff = names.staff(issue.staffId ?? '');
  const otherEvent = issue.eventTitle || texts.otherEvent;
  const when =
    issue.startsAt && issue.endsAt ? formatTimeRange(issue.startsAt, issue.endsAt, timeZone) : '';
  switch (issue.type) {
    case 'RESOURCE_BUSY':
      return texts.RESOURCE_BUSY(names.resource(issue.resourceId ?? ''), otherEvent, when);
    case 'STAFF_BUSY':
      return texts.STAFF_BUSY(staff, otherEvent, when);
    case 'STAFF_ON_LEAVE':
      return texts.STAFF_ON_LEAVE(staff);
    case 'STAFF_LEAVE_REQUESTED':
      return texts.STAFF_LEAVE_REQUESTED(staff);
    case 'STAFF_OUTSIDE_AVAILABILITY':
      return texts.STAFF_OUTSIDE_AVAILABILITY(staff);
    case 'STAFF_NOT_QUALIFIED':
      return texts.STAFF_NOT_QUALIFIED(staff);
  }
}

type KnownCode = Exclude<keyof typeof pl.schedule.errors, 'network' | 'unexpected'>;

function isKnownCode(code: string): code is KnownCode {
  return code in pl.schedule.errors && code !== 'network' && code !== 'unexpected';
}

/** Polish message for an error of the schedule endpoints, chosen by its API code. */
export function scheduleErrorMessage(error: Error): string {
  if (!(error instanceof ApiError)) {
    return pl.schedule.errors.unexpected;
  }
  if (error.code === NETWORK_ERROR) {
    return pl.schedule.errors.network;
  }
  if (error.code !== null && isKnownCode(error.code)) {
    return pl.schedule.errors[error.code];
  }
  return pl.schedule.errors.unexpected;
}

/** The conflicting occurrences a rejected plan or assignment change lists in `conflicts`. */
export function conflictsOf(error: Error | null): OccurrencePreview[] {
  if (!(error instanceof ApiError) || error.problem === null) {
    return [];
  }
  const conflicts = (error.problem as { conflicts?: unknown }).conflicts;
  return Array.isArray(conflicts) ? (conflicts as OccurrencePreview[]) : [];
}

/** Names for issue descriptions; an unknown id gets a generic name. */
export function issueNames(
  staffById: ReadonlyMap<string, StaffMember>,
  resourceById: ReadonlyMap<string, { name: string }>,
): IssueNames {
  return {
    staff: (staffId) => staffById.get(staffId)?.displayName ?? pl.schedule.unknownStaff,
    resource: (resourceId) => resourceById.get(resourceId)?.name ?? pl.schedule.unknownResource,
  };
}
