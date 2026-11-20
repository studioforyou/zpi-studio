import { apiFetch } from '@/shared/api/apiClient';

/** What the automatic cancellation rule will do with the class. */
export type AutoCancellationState =
  /** The participants will be counted at `checkAt`; too few cancel the class. */
  | 'PENDING'
  /** An administrator forced the class to run. */
  | 'FORCED'
  /** The rule does not apply: off for the service or the studio, the group or the class. */
  | 'OFF'
  /** Nothing more happens: already counted, started or not scheduled. */
  | 'CLOSED';

export type EventAutoCancellation = {
  eventId: string;
  state: AutoCancellationState;
  /** The rule applies to the class; forcing it to run does not change this. */
  ruleEnabled: boolean;
  forceRun: boolean;
  trainingGroupId: string | null;
  /** The rule is switched off for every class of the fixed group. */
  disabledForGroup: boolean;
  /** Participants with an active reservation. */
  participants: number;
  /** `null` when the rule does not apply. */
  minimumParticipants: number | null;
  /** When the participants are counted; `null` when the rule does not apply. */
  checkAt: string | null;
};

export type TrainingGroupAutoCancellation = {
  trainingGroupId: string;
  disabled: boolean;
};

export const autoCancellationKeys = {
  /**
   * Under the class's reservations, so booking or cancelling a place also refreshes the
   * participant count.
   */
  event: (eventId: string) => ['schedule', 'reservations', eventId, 'auto-cancellation'] as const,
};

const eventPath = (eventId: string) => `/api/v1/schedule-events/${eventId}/auto-cancellation`;

export function getEventAutoCancellation(eventId: string): Promise<EventAutoCancellation> {
  return apiFetch<EventAutoCancellation>(eventPath(eventId));
}

/** Forces the class to run whatever its participants (`true`), or lets the rule decide again. */
export function changeForceRun(eventId: string, forceRun: boolean): Promise<EventAutoCancellation> {
  return apiFetch<EventAutoCancellation>(eventPath(eventId), {
    method: 'PUT',
    body: { forceRun },
  });
}

/** Switches the rule off for every class of the group (`true`), or back to the service's rule. */
export function changeGroupAutoCancellation(
  groupId: string,
  disabled: boolean,
): Promise<TrainingGroupAutoCancellation> {
  return apiFetch<TrainingGroupAutoCancellation>(
    `/api/v1/training-groups/${groupId}/auto-cancellation`,
    { method: 'PUT', body: { disabled } },
  );
}
