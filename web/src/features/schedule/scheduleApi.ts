import { apiFetch } from '@/shared/api/apiClient';
import type { ResourceKind } from './resourcesApi';

export const eventKinds = [
  'CLASS',
  'PERSONAL_TRAINING',
  'WORKSHOP',
  'CONSULTATION',
  'BLOCK',
] as const;
export type EventKind = (typeof eventKinds)[number];

/**
 * Kinds an administrator plans in the panel; consultations have their own story. A block, e.g. a
 * maintenance, has no service or staff and takes its rooms whole.
 */
export const plannableKinds = ['CLASS', 'WORKSHOP', 'PERSONAL_TRAINING', 'BLOCK'] as const;
export type PlannableKind = (typeof plannableKinds)[number];

export const eventStatuses = ['SCHEDULED', 'COMPLETED', 'CANCELLED'] as const;
export type EventStatus = (typeof eventStatuses)[number];

export const eventVisibilities = ['PUBLIC', 'PRIVATE'] as const;
export type EventVisibility = (typeof eventVisibilities)[number];

export type StaffRole = 'LEAD' | 'ASSISTANT';

export type AssignedStaff = {
  staffId: string;
  displayName: string | null;
  role: StaffRole;
};

export type AssignedResource = {
  resourceId: string;
  name: string;
  kind: ResourceKind;
  /** `true`: no other event may use the resource at the same time. */
  blocksWholeRoom: boolean;
};

export type ScheduleEvent = {
  id: string;
  kind: EventKind;
  status: EventStatus;
  visibility: EventVisibility;
  title: string | null;
  serviceTypeId: string | null;
  serviceTypeName: string | null;
  /** The recurring series that generated the event; `null` for a one-off event. */
  seriesId: string | null;
  /** The occurrence differs from its series' rule. */
  isSeriesException: boolean;
  trainingGroupId: string | null;
  startsAt: string;
  endsAt: string;
  /** Maximum number of participants; `null` = no limit. */
  capacity: number | null;
  staff: AssignedStaff[];
  resources: AssignedResource[];
  cancelReason: string | null;
  blockReason: string | null;
  guestName: string | null;
  version: number;
};

export type StaffMember = {
  id: string;
  firstName: string;
  lastName: string;
  displayName: string;
  calendarColor: string | null;
  isActive: boolean;
  /** Service types the staff member is qualified to run. */
  serviceTypeIds: string[];
};

export type ServiceTypeDetails = {
  id: string;
  name: string;
  category: string;
  level: string | null;
  minAge: number | null;
  maxAge: number | null;
  defaultDurationMinutes: number;
  maxParticipants: number | null;
  calendarColor: string | null;
  isActive: boolean;
};

export type TrainingGroup = {
  id: string;
  name: string;
  serviceTypeId: string;
  isActive: boolean;
};

export type Studio = { name: string; timeZone: string; currency: string };

export type StaffAssignment = { staffId: string; role: StaffRole };

export type ResourceAssignment = { resourceId: string; blocksWholeRoom: boolean };

export type EventDetailsInput = {
  kind: PlannableKind;
  /** `null` only for a block. */
  serviceTypeId: string | null;
  /** `null` = the service type's name. */
  title: string | null;
  /** `null` = the service type's limit, at most what the rooms hold. */
  capacity: number | null;
  visibility: EventVisibility;
  staff: StaffAssignment[];
  resources: ResourceAssignment[];
};

export type OneOffEventInput = EventDetailsInput & { startsAt: string; endsAt: string };

export type SeriesInput = EventDetailsInput & {
  /** ISO weekdays, 1 = Monday. */
  weekdays: number[];
  /** Studio local time, `HH:mm`. */
  localStartTime: string;
  durationMinutes: number;
  intervalWeeks: number;
  startsOn: string;
  endsOn: string;
  excludedDates: string[];
};

export const issueTypes = [
  'RESOURCE_BUSY',
  'STAFF_BUSY',
  'STAFF_ON_LEAVE',
  'STAFF_LEAVE_REQUESTED',
  'STAFF_OUTSIDE_AVAILABILITY',
  'STAFF_NOT_QUALIFIED',
] as const;
export type IssueType = (typeof issueTypes)[number];

export type ScheduleIssue = {
  type: IssueType;
  /** Whether the problem prevents saving. */
  blocking: boolean;
  staffId: string | null;
  resourceId: string | null;
  eventId: string | null;
  eventTitle: string | null;
  startsAt: string | null;
  endsAt: string | null;
};

export type OccurrencePreview = {
  /** Studio local date. */
  date: string;
  startsAt: string;
  endsAt: string;
  excluded: boolean;
  issues: ScheduleIssue[];
};

export type PlanPreview = {
  occurrences: OccurrencePreview[];
  /** Problems of the whole plan that do not block saving. */
  warnings: ScheduleIssue[];
  conflictCount: number;
};

export type RecurrenceSeries = {
  id: string;
  serviceTypeId: string;
  title: string | null;
  weekday: number;
  localStartTime: string;
  durationMinutes: number;
  intervalWeeks: number;
  startsOn: string;
  endsOn: string | null;
  capacity: number | null;
  status: 'ACTIVE' | 'ENDED';
  defaultStaffId: string | null;
  defaultResourceId: string | null;
  occurrenceCount: number;
};

export type AssignmentScope = 'THIS_EVENT' | 'THIS_AND_FOLLOWING';

export type AssignmentsChange = {
  version: number;
  scope: AssignmentScope;
  staff: StaffAssignment[];
  resources: ResourceAssignment[];
};

type ListResponse<T> = { items: T[] };

export const scheduleKeys = {
  all: ['schedule'] as const,
  events: (from: string, to: string) => [...scheduleKeys.all, 'events', from, to] as const,
  series: (id: string) => [...scheduleKeys.all, 'series', id] as const,
  staff: ['schedule', 'staff'] as const,
  serviceTypes: ['schedule', 'service-types'] as const,
  trainingGroups: ['schedule', 'training-groups'] as const,
  studio: ['studio'] as const,
};

export async function listScheduleEvents(from: string, to: string): Promise<ScheduleEvent[]> {
  const query = new URLSearchParams({ from, to });
  const response = await apiFetch<ListResponse<ScheduleEvent>>(`/api/v1/schedule-events?${query}`);
  return response.items;
}

export function createScheduleEvent(input: OneOffEventInput): Promise<ScheduleEvent> {
  return apiFetch<ScheduleEvent>('/api/v1/schedule-events', { method: 'POST', body: input });
}

export function previewScheduleEvent(input: OneOffEventInput): Promise<PlanPreview> {
  return apiFetch<PlanPreview>('/api/v1/schedule-events/preview', { method: 'POST', body: input });
}

export function createRecurrenceSeries(input: SeriesInput): Promise<{ items: RecurrenceSeries[] }> {
  return apiFetch('/api/v1/recurrence-series', { method: 'POST', body: input });
}

export function previewRecurrenceSeries(input: SeriesInput): Promise<PlanPreview> {
  return apiFetch<PlanPreview>('/api/v1/recurrence-series/preview', {
    method: 'POST',
    body: input,
  });
}

export function getRecurrenceSeries(id: string): Promise<RecurrenceSeries> {
  return apiFetch<RecurrenceSeries>(`/api/v1/recurrence-series/${id}`);
}

export function changeEventAssignments(
  eventId: string,
  change: AssignmentsChange,
): Promise<ScheduleEvent> {
  return apiFetch<ScheduleEvent>(`/api/v1/schedule-events/${eventId}/assignments`, {
    method: 'PUT',
    body: change,
  });
}

export async function listStaff(): Promise<StaffMember[]> {
  const response = await apiFetch<ListResponse<StaffMember>>('/api/v1/staff');
  return response.items;
}

/** All service types, also inactive ones, so older events still show their service. */
export async function listAllServiceTypes(): Promise<ServiceTypeDetails[]> {
  const response = await apiFetch<ListResponse<ServiceTypeDetails>>('/api/v1/service-types');
  return response.items;
}

export async function listTrainingGroups(): Promise<TrainingGroup[]> {
  const response = await apiFetch<ListResponse<TrainingGroup>>('/api/v1/training-groups');
  return response.items;
}

export function getStudio(): Promise<Studio> {
  return apiFetch<Studio>('/api/v1/studio');
}
