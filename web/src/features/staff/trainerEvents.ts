import { useQuery } from '@tanstack/react-query';
import { listScheduleEvents, scheduleKeys } from '@/features/schedule/scheduleApi';
import type { TimeOff } from './timeOffApi';

/** The schedule endpoint accepts windows of at most 93 days. */
const MAX_WINDOW_MS = 93 * 24 * 60 * 60 * 1000;

/**
 * The scheduled events a staff member runs during their time off, earliest first. Only the first
 * 93 days of a longer absence are checked (`capped`).
 */
export function useTrainerEventsDuring(
  timeOff: Pick<TimeOff, 'staffId' | 'startsAt' | 'endsAt'>,
  enabled = true,
) {
  const start = new Date(timeOff.startsAt).getTime();
  const end = new Date(timeOff.endsAt).getTime();
  const capped = end - start > MAX_WINDOW_MS;
  const from = timeOff.startsAt;
  const to = capped ? new Date(start + MAX_WINDOW_MS).toISOString() : timeOff.endsAt;
  const events = useQuery({
    queryKey: scheduleKeys.events(from, to),
    queryFn: () => listScheduleEvents(from, to),
    enabled,
  });
  const own = (events.data ?? [])
    .filter(
      (event) =>
        event.status === 'SCHEDULED' &&
        event.kind !== 'BLOCK' &&
        event.staff.some((member) => member.staffId === timeOff.staffId),
    )
    .sort((a, b) => a.startsAt.localeCompare(b.startsAt));
  return { events, own, capped };
}
